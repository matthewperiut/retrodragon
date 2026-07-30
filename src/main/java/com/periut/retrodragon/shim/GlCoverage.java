package com.periut.retrodragon.shim;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every GL entry point b1.7.3 calls, against what the WebGPU shim actually implements.
 * {@code ./gradlew glCoverage}.
 *
 * <p>This exists because of how the shim fails. {@code GlPlugin} rewrites {@code GL11}: a method
 * {@link GlBridge} has a matching name AND descriptor for is forwarded, and <b>everything else is
 * given an empty body</b>. That is the right default -- a call reaching a driver that is not there
 * would crash rather than mis-draw -- but it means a missing entry point is perfectly silent. It
 * does not throw, log, or fail a device validation check; the game just quietly stops doing one
 * thing.
 *
 * <p>Four bugs arrived that way before this check existed, each found only by someone noticing a
 * picture was wrong: {@code glPolygonOffset} (the block-breaking overlay z-fought its own block),
 * {@code glReadPixels} (screenshots saved black), {@code glLight} (entities lit from underneath)
 * and {@code glTexParameteri} (entity shadows tiled across the ground). All four were a call beta
 * had been making all along into a method that silently did nothing.
 *
 * <p>So the interesting output is not the count, it is the DIFF: anything beta calls that the shim
 * neither implements nor has deliberately excused below. New entries fail the build.
 */
public final class GlCoverage {
	private GlCoverage() {
	}

	/**
	 * Entry points that are correctly ignored, and why.
	 *
	 * <p>Every line here is a claim that doing nothing is right. They divide into three kinds:
	 * state the shim reconstructs from somewhere better, the vertex-array path the Tessellator
	 * capture replaces wholesale, and things WebGPU genuinely cannot express.
	 */
	private static final Map<String, String> EXCUSED = new LinkedHashMap<>();

	static {
		// The Tessellator's own draw path. retroperf intercepts Tessellator.draw() and captures the
		// packed buffer directly, so the pointer/clientstate/drawArrays dance never has to happen.
		for (String m : new String[] { "glVertexPointer", "glColorPointer", "glNormalPointer",
			"glTexCoordPointer", "glDrawArrays", "glEnableClientState", "glDisableClientState" }) {
			EXCUSED.put(m, "Tessellator vertex-array path; its buffer is captured directly instead");
		}
		EXCUSED.put("glPixelStorei", "pack/unpack alignment 1; readback is already tightly packed");
		EXCUSED.put("glColorMaterial",
			"only ever FRONT_AND_BACK/AMBIENT_AND_DIFFUSE, which is what the shader already does");
		EXCUSED.put("glClearDepth", "only ever 1.0, which is the pass's depthClearValue");
		// Genuinely not expressible. Recorded rather than silently accepted.
		EXCUSED.put("glShadeModel",
			"flat vs smooth is a WGSL @interpolate decision, not pipeline state; always smooth");
	}

	public static void main(String[] args) throws Exception {
		Set<String> implemented = new TreeSet<>();
		for (Method m : GlBridge.class.getDeclaredMethods()) {
			if (Modifier.isStatic(m.getModifiers()) && m.getName().startsWith("gl")) {
				implemented.add(m.getName() + descriptorOf(m));
			}
		}

		Map<String, Integer> used = new TreeMap<>();
		Map<String, Set<String>> callers = new TreeMap<>();
		int scanned = scanGameClasses(used, callers);
		if (scanned == 0) {
			System.err.println("GL coverage: found no game classes to scan; classpath wrong?");
			System.exit(1);
		}

		List<String> missing = new ArrayList<>();
		int covered = 0;
		int excused = 0;
		for (Map.Entry<String, Integer> e : used.entrySet()) {
			String signature = e.getKey();
			String name = signature.substring(0, signature.indexOf('('));
			if (implemented.contains(signature)) {
				covered++;
			} else if (EXCUSED.containsKey(name)) {
				excused++;
			} else {
				missing.add(signature);
			}
		}

		System.out.println("GL coverage: " + scanned + " game classes, "
			+ used.size() + " distinct GL11 entry points used");
		System.out.println("  implemented = " + covered);
		System.out.println("  excused     = " + excused + "  (documented no-ops)");
		System.out.println("  UNHANDLED   = " + missing.size());

		for (String signature : missing) {
			System.out.println("    " + signature + "  x" + used.get(signature)
				+ "  <- " + String.join(", ", callers.get(signature)));
		}

		if (!missing.isEmpty()) {
			System.err.println("GL COVERAGE FAILED: the entry points above are called by the game and"
				+ " rewritten to an empty body. Implement them in GlBridge, or add them to EXCUSED"
				+ " with the reason doing nothing is correct.");
			System.exit(1);
		}
		System.out.println("GL coverage OK: every GL11 entry point b1.7.3 calls is handled or excused");
	}

	/** @return how many game classes were read */
	private static int scanGameClasses(Map<String, Integer> used, Map<String, Set<String>> callers)
			throws Exception {

		int scanned = 0;
		for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
			Path path = Path.of(entry);
			if (!Files.exists(path) || !entry.endsWith(".jar")) {
				continue;
			}
			try (JarFile jar = new JarFile(path.toFile())) {
				// The Minecraft jar is the one with the client's own classes in it. Identified by
				// content rather than by name, which varies with the mappings hash.
				if (jar.getJarEntry("net/minecraft/client/Minecraft.class") == null) {
					continue;
				}
				for (JarEntry e : java.util.Collections.list(jar.entries())) {
					if (!e.getName().endsWith(".class")) {
						continue;
					}
					try (InputStream in = jar.getInputStream(e)) {
						scan(new ClassReader(in.readAllBytes()), used, callers);
					}
					scanned++;
				}
				return scanned;
			}
		}
		return scanned;
	}

	private static void scan(ClassReader reader, Map<String, Integer> used,
			Map<String, Set<String>> callers) {

		String owner = reader.getClassName().replace('/', '.');
		reader.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String target, String method,
							String methodDescriptor, boolean isInterface) {
						// GL11 only: GlPlugin forwards nothing from the extension classes, and beta
						// reaches those solely through paths retroperf replaces outright.
						if (!"org/lwjgl/opengl/GL11".equals(target)) {
							return;
						}
						String key = method + methodDescriptor;
						used.merge(key, 1, Integer::sum);
						callers.computeIfAbsent(key, k -> new TreeSet<>()).add(shortName(owner));
					}
				};
			}
		}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
	}

	private static String shortName(String className) {
		return className.substring(className.lastIndexOf('.') + 1);
	}

	private static String descriptorOf(Method m) {
		StringBuilder out = new StringBuilder("(");
		for (Class<?> p : m.getParameterTypes()) {
			out.append(typeDescriptor(p));
		}
		return out.append(')').append(typeDescriptor(m.getReturnType())).toString();
	}

	private static String typeDescriptor(Class<?> type) {
		if (type == void.class) return "V";
		if (type == boolean.class) return "Z";
		if (type == byte.class) return "B";
		if (type == char.class) return "C";
		if (type == short.class) return "S";
		if (type == int.class) return "I";
		if (type == long.class) return "J";
		if (type == float.class) return "F";
		if (type == double.class) return "D";
		if (type.isArray()) return "[" + typeDescriptor(type.getComponentType());
		return "L" + type.getName().replace('.', '/') + ";";
	}
}
