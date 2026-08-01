package com.periut.retrodragon.mixin;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.render.RenderBackend;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Replaces LWJGL's GL entry points with the WebGPU shim, when -- and only when -- WebGPU owns the
 * window.
 *
 * <h2>Why this exists instead of ordinary mixin injectors</h2>
 *
 * LWJGL 3.4 declares 274 of {@code GL11}'s methods {@code native}, which is most of the
 * fixed-function API including {@code glPushMatrix}, {@code glBegin} and {@code glCallList}. A
 * native method has no instructions, so {@code @Inject} cannot attach to it -- the injector fails
 * with a null instruction node rather than reporting a missing target, which is a genuinely
 * confusing way to find this out.
 *
 * <p>A mixin config plugin, though, gets the raw {@link ClassNode} before mixins are applied and may
 * do anything to it. So each GL method is given a real body: a forwarding call to
 * {@code shim/GlBridge} where one matches by name and descriptor, and an empty body where none
 * does. An empty body is the right answer for the rest -- the process has no GL context at all, so a
 * call that reached the driver would throw rather than misdraw.
 *
 * <h2>Why the GL backend is unaffected</h2>
 *
 * The rewrite is gated on {@link RenderBackend#isWebGpu()}, which is decided before the window is
 * created and cannot change afterwards. Under GL, {@link #preApply} returns without touching
 * anything and the game runs on the same bytecode it always did.
 */
public class GlPlugin implements IMixinConfigPlugin {
	private static final String BRIDGE = "com/periut/retrodragon/shim/GlBridge";
	private static final String BRIDGE_CLASS = "com.periut.retrodragon.shim.GlBridge";

	/**
	 * GL classes to neutralise. {@code GL11} carries beta's whole renderer; the ARB extensions are
	 * beta's optional occlusion-query and VBO paths, which have no WebGPU meaning and would
	 * otherwise reach a driver that is not there.
	 */
	private static final Set<String> TARGETS = Set.of(
		"org.lwjgl.opengl.GL11",
		"org.lwjgl.opengl.GL12",
		"org.lwjgl.opengl.GL13",
		"org.lwjgl.opengl.GL14",
		"org.lwjgl.opengl.GL15",
		"org.lwjgl.opengl.GL20",
		"org.lwjgl.opengl.GL30",
		"org.lwjgl.opengl.ARBOcclusionQuery",
		"org.lwjgl.opengl.ARBVertexBufferObject",
		"org.lwjgl.opengl.ARBShaderObjects");

	/** Signatures GlBridge implements, as {@code name+descriptor}. */
	private static Set<String> bridgeMethods;
	private static boolean reported;

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// The mixins under .mixin.stapi target StationAPI's own classes, which are absent from every
		// runtime this mod ships on by default. Without this gate Mixin fails to find the target and
		// the config is `required`, so a plain runClient would die on a mod that is not installed.
		if (mixinClassName.contains(".mixin.stapi.")) {
			return net.fabricmc.loader.api.FabricLoader.getInstance()
				.isModLoaded("station-renderer-api-v0");
		}
		// The MC-73186 fix patches a constant inside beta's own held-item extrusion, and StationAPI's
		// Arsenic renderer @Overwrites the whole method it lives in -- HeldItemRenderer.renderItem
		// becomes a single call to ArsenicOverlayRenderer.renderItem3D, with beta's four side-wall
		// loops and the 15.99 texture zoom gone with it. There is nothing left to patch.
		//
		// Worse than pointless: Mixin refuses an injection into a method another mixin MERGED unless
		// the injector outranks it, so at equal priority this is a hard failure during APPLY, and the
		// config is `required`. Every StationAPI install crashed on startup in 0.1.2 because of it.
		// Raising our priority would clear the refusal and then fail the `require = 1` instead, since
		// the constant genuinely is not there any more.
		//
		// So the honest gate is "does Arsenic own held-item rendering", and if it does this is not our
		// method. Any gap in Arsenic's own renderItem3D is a separate bug in a separate renderer.
		if (mixinClassName.endsWith(".HeldItemGapMixin")) {
			return !net.fabricmc.loader.api.FabricLoader.getInstance()
				.isModLoaded("station-renderer-arsenic");
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
			IMixinInfo mixinInfo) {
		if (!TARGETS.contains(targetClassName) || !RenderBackend.isWebGpu()) {
			return;
		}
		int forwarded = 0;
		int stubbed = 0;
		for (MethodNode method : targetClass.methods) {
			// Only the API surface. LWJGL's own plumbing (constructors, static init, capability
			// creation) has to keep working: it is what loads the class in the first place.
			if (!method.name.startsWith("gl") || (method.access & Opcodes.ACC_STATIC) == 0) {
				continue;
			}
			if (rewrite(method, forwards(targetClassName, method))) {
				if (forwards(targetClassName, method)) {
					forwarded++;
				} else {
					stubbed++;
				}
			}
		}
		if (!reported || forwarded > 0) {
			reported = true;
			RetroDragon.detail("{}: {} entry points routed to the WebGPU shim, {} neutralised",
				targetClassName, forwarded, stubbed);
		}
	}

	/** Only GL11 is translated; the extension classes are neutralised wholesale. */
	private static boolean forwards(String targetClassName, MethodNode method) {
		return "org.lwjgl.opengl.GL11".equals(targetClassName)
			&& bridgeMethods().contains(method.name + method.desc);
	}

	private static boolean rewrite(MethodNode method, boolean forward) {
		InsnList body = new InsnList();
		Type[] arguments = Type.getArgumentTypes(method.desc);
		Type returnType = Type.getReturnType(method.desc);
		int locals = 0;

		if (forward) {
			for (Type argument : arguments) {
				body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), locals));
				locals += argument.getSize();
			}
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, method.name, method.desc, false));
			body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
		} else {
			for (Type argument : arguments) {
				locals += argument.getSize();
			}
			pushDefault(body, returnType);
			body.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
		}

		method.access &= ~Opcodes.ACC_NATIVE;
		method.instructions = body;
		if (method.tryCatchBlocks != null) {
			method.tryCatchBlocks.clear();
		}
		if (method.localVariables != null) {
			method.localVariables.clear();
		}
		method.maxLocals = Math.max(locals, 1);
		// Arguments are pushed one at a time onto an otherwise empty stack, so the depth never
		// exceeds their combined size; the +2 covers a wide default return value on a no-arg method.
		method.maxStack = Math.max(locals, 2);
		return true;
	}

	private static void pushDefault(InsnList body, Type returnType) {
		switch (returnType.getSort()) {
			case Type.VOID -> {
				// nothing to push
			}
			case Type.LONG -> body.add(new InsnNode(Opcodes.LCONST_0));
			case Type.FLOAT -> body.add(new InsnNode(Opcodes.FCONST_0));
			case Type.DOUBLE -> body.add(new InsnNode(Opcodes.DCONST_0));
			case Type.OBJECT, Type.ARRAY -> body.add(new InsnNode(Opcodes.ACONST_NULL));
			default -> body.add(new InsnNode(Opcodes.ICONST_0));
		}
	}

	/**
	 * Reflected rather than listed by hand, so a bridge method with a signature that does not exist
	 * in {@code GL11} is simply never matched instead of silently forwarding the wrong overload.
	 */
	private static Set<String> bridgeMethods() {
		if (bridgeMethods != null) {
			return bridgeMethods;
		}
		Set<String> signatures = new HashSet<>();
		try {
			Class<?> bridge = Class.forName(BRIDGE_CLASS);
			for (Method method : bridge.getDeclaredMethods()) {
				if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())) {
					signatures.add(method.getName() + Type.getMethodDescriptor(method));
				}
			}
		} catch (Throwable t) {
			RetroDragon.LOGGER.error("could not read GlBridge; WebGPU will have no GL translation", t);
		}
		bridgeMethods = signatures;
		return signatures;
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
			IMixinInfo mixinInfo) {
	}
}
