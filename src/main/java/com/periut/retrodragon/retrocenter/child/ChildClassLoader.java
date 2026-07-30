package com.periut.retrodragon.retrocenter.child;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Isolating classloader for in-process child Minecraft instances.
 *
 * A child instance is a fresh fabric-loader (Knot) boot inside its own
 * classloader tree: game classes, mods, mixin and fabric-loader itself are
 * loaded fresh (self-first) so the child gets its own statics and its own
 * mod set. A small set of packages is delegated parent-first so both trees
 * share ONE copy:
 *
 * - org.lwjgl.* -- the windowing facade + LWJGL3. Sharing it is what makes
 *   the window handoff work (shared Display/Mouse/Keyboard statics) and is
 *   also mandatory: JNI natives can bind to only one classloader per JVM.
 * - com.periut.retrodragon.retrocenter.bridge.* -- the hub/child coordination
 *   statics (HubBridge, ChildConfig).
 * - logging + netty -- stateless-ish infrastructure, avoids double init.
 *
 * NOTE: the child's classpath must NOT contain the full RetroDragon artifact
 * (its org.lwjgl package would be self-loaded by the child's Knot and break
 * the sharing). The child instead gets the "childshim" jar -- RetroDragon minus
 * org/lwjgl/** and natives/** -- in its mods folder. See InProcessChildLauncher.
 */
public final class ChildClassLoader extends URLClassLoader {

	static {
		ClassLoader.registerAsParallelCapable();
	}

	private static final String[] SHARED_PREFIXES = {
			"java.",
			"javax.",
			"jdk.",
			"sun.",
			"com.sun.",
			"org.lwjgl.",
			"com.periut.retrodragon.retrocenter.bridge.",
			"org.apache.logging.",
			"org.slf4j.",
			"io.netty.",
	};

	/** SHARED_PREFIXES in resource-path form (dots → slashes). */
	private static final String[] SHARED_RESOURCE_PREFIXES;

	static {
		SHARED_RESOURCE_PREFIXES = new String[SHARED_PREFIXES.length];
		for (int i = 0; i < SHARED_PREFIXES.length; i++) {
			SHARED_RESOURCE_PREFIXES[i] = SHARED_PREFIXES[i].replace('.', '/');
		}
	}

	/** This loader's child instance id (assigned by the bridge). */
	public final int instanceId;

	public ChildClassLoader(URL[] classpath, ClassLoader parent) {
		super("retrocenter-child", classpath, parent);
		this.instanceId = com.periut.retrodragon.retrocenter.bridge.HubBridge.registerChildLoader(this);
	}

	private static boolean isShared(String name) {
		for (String prefix : SHARED_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSharedResource(String name) {
		for (String prefix : SHARED_RESOURCE_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Resource isolation mirrors class isolation. Without this, isolated
	 * classes visible through BOTH this loader's URLs and the parent chain
	 * show up twice in getResources() -- fabric-loader's
	 * LoaderUtil.verifyClasspath sees "duplicate fabric loader classes" and
	 * refuses to boot the child.
	 *
	 * SHARED .class resources are hidden entirely: the child's Knot gates
	 * parent-visible class resources by code source ("hasn't been exposed
	 * to the game") and refuses BEFORE delegating. With the resource
	 * invisible, Knot falls back to parent loadClass -- which is exactly the
	 * shared-class path (org.lwjgl, the bridge). Non-class shared resources
	 * (logging configs etc.) stay visible.
	 */
	@Override
	public URL getResource(String name) {
		if (isSharedResource(name)) {
			return name.endsWith(".class") ? null : super.getResource(name);
		}
		URL own = findResource(name);
		return own != null ? own : super.getResource(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		if (isSharedResource(name)) {
			return name.endsWith(".class")
					? java.util.Collections.emptyEnumeration()
					: super.getResources(name);
		}
		Enumeration<URL> own = findResources(name);
		if (own.hasMoreElements()) {
			return own; // self-only: exactly one copy of isolated classes
		}
		return super.getResources(name);
	}

	/**
	 * Ornithe's flap javaagent INJECTS a ModVariantRemapper call into
	 * FabricLoaderImpl at class-definition time -- instrumentation sees every
	 * classloader, including this one. The real flap can serve neither tree
	 * twice: the system copy type-links the app loader's ModCandidateImpl,
	 * and a child-local copy can't init (it registers a jimfs filesystem
	 * under a fixed JVM-global name the hub already owns). Children don't
	 * need variant remapping (b1.7.3-era mods aren't variant jars), so the
	 * injected call lands in this generated no-op stub instead.
	 */
	private static final String FLAP_REMAPPER = "net.ornithemc.flap.ModVariantRemapper";

	private Class<?> defineFlapStub() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FLAP_REMAPPER.replace('.', '/'), null, "java/lang/Object", null);
		for (String method : new String[]{"transformModCandidatesFabric", "transformModCandidatesQuilt"}) {
			MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					method, "(Ljava/util/List;)V", null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 1);
			mv.visitEnd();
		}
		writer.visitEnd();
		byte[] bytes = writer.toByteArray();
		return defineClass(FLAP_REMAPPER, bytes, 0, bytes.length);
	}

	/**
	 * The child's applet wrapper must NEVER show its AWT frame -- the hub's
	 * window is the only window. fabric-loader's applet classes are loaded
	 * by this loader, so their setVisible(true)/show() calls are neutralized
	 * at definition time: zero frames of flash, not "hidden quickly after".
	 */
	private static final String APPLET_WRAPPER_PKG = "net.fabricmc.loader.impl.game.minecraft.applet.";

	private Class<?> defineHeadlessAppletClass(String name) {
		return defineTransformed(name, original -> {
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] ex) {
					return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, mName, desc, sig, ex)) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String iName, String iDesc, boolean itf) {
							if (opcode == Opcodes.INVOKEVIRTUAL && "setVisible".equals(iName) && "(Z)V".equals(iDesc)) {
								super.visitInsn(Opcodes.POP); // boolean arg
								super.visitInsn(Opcodes.POP); // receiver
								return;
							}
							if (opcode == Opcodes.INVOKEVIRTUAL && "show".equals(iName) && "()V".equals(iDesc)) {
								super.visitInsn(Opcodes.POP); // receiver
								return;
							}
							super.visitMethodInsn(opcode, owner, iName, iDesc, itf);
						}
					};
				}
			}, 0);
			return writer.toByteArray();
		});
	}

	/**
	 * The child Knot's last-resort fallback for classes it can't see as
	 * resources is the PLATFORM classloader -- which knows nothing about the
	 * hub. Patch KnotClassDelegate.loadClass so that fallback goes to its
	 * parentClassLoader field instead (this loader!), restoring the intended
	 * topology: child Knot → ChildClassLoader → hub Knot → system. java.*
	 * still resolves fine through our parent-first shared handling.
	 */
	private static final String KNOT_DELEGATE = "net.fabricmc.loader.impl.launch.knot.KnotClassDelegate";

	/**
	 * Reads a class' original bytes (with its jar's certificates -- the
	 * loader jar is SIGNED, and a certless definition in a signed package
	 * throws "signer information does not match"), applies a bytecode
	 * transform, and defines the result under the original code source.
	 */
	private Class<?> defineTransformed(String name, java.util.function.UnaryOperator<byte[]> transform) {
		String resource = name.replace('.', '/') + ".class";
		URL url = findResource(resource);
		if (url == null) {
			url = getResource(resource);
		}
		if (url == null) {
			return null;
		}
		try {
			java.net.URLConnection connection = url.openConnection();
			byte[] original;
			try (java.io.InputStream in = connection.getInputStream()) {
				original = in.readAllBytes();
			}
			java.security.cert.Certificate[] certificates = null;
			URL sourceUrl = url;
			if (connection instanceof java.net.JarURLConnection jarConnection) {
				certificates = jarConnection.getCertificates(); // valid after full read
				sourceUrl = jarConnection.getJarFileURL();
			}
			byte[] transformed = transform.apply(original);
			java.security.CodeSource codeSource = new java.security.CodeSource(sourceUrl, certificates);
			java.security.ProtectionDomain domain = new java.security.ProtectionDomain(codeSource, null, this, null);
			return defineClass(name, transformed, 0, transformed.length, domain);
		} catch (IOException e) {
			return null; // fall through to normal loading
		}
	}

	private Class<?> defineParentDelegatingKnotDelegate() {
		return defineTransformed(KNOT_DELEGATE, original -> {
			ClassReader reader = new ClassReader(original);
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String mName, String desc, String sig, String[] ex) {
					MethodVisitor mv = super.visitMethod(access, mName, desc, sig, ex);
					if (!"loadClass".equals(mName)) {
						return mv;
					}
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
							if (opcode == Opcodes.GETSTATIC
									&& "PLATFORM_CLASS_LOADER".equals(fName)
									&& "Ljava/lang/ClassLoader;".equals(fDesc)) {
								super.visitVarInsn(Opcodes.ALOAD, 0);
								super.visitFieldInsn(Opcodes.GETFIELD,
										KNOT_DELEGATE.replace('.', '/'),
										"parentClassLoader", "Ljava/lang/ClassLoader;");
								return;
							}
							super.visitFieldInsn(opcode, owner, fName, fDesc);
						}
					};
				}
			}, 0);
			return writer.toByteArray();
		});
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> loaded = findLoadedClass(name);
			if (loaded != null) {
				return loaded;
			}
			if (FLAP_REMAPPER.equals(name)) {
				return defineFlapStub();
			}
			if (KNOT_DELEGATE.equals(name)) {
				Class<?> patched = defineParentDelegatingKnotDelegate();
				if (patched != null) {
					return patched;
				}
			}
			if (name.startsWith(APPLET_WRAPPER_PKG)) {
				Class<?> headless = defineHeadlessAppletClass(name);
				if (headless != null) {
					return headless;
				}
			}
			if (isShared(name)) {
				// parent-first: one copy for hub + all children
				return super.loadClass(name, resolve);
			}
			// self-first: isolate the child's loader/game/mod classes
			try {
				Class<?> c = findClass(name);
				if (resolve) {
					resolveClass(c);
				}
				return c;
			} catch (ClassNotFoundException e) {
				try {
					return super.loadClass(name, resolve);
				} catch (ClassNotFoundException e2) {
					// Last resort: the system loader sees things neither we
					// nor the hub's knot do -- e.g. -javaagent jars. Never
					// reached for isolated classes (those are in our URLs).
					return ClassLoader.getSystemClassLoader().loadClass(name);
				}
			}
		}
	}
}
