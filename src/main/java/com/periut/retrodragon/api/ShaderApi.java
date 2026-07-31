package com.periut.retrodragon.api;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.shim.PipelineKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The engine's extension point for shader mods.
 *
 * <p>Everything a shader mod does goes through here: install itself, register programs, ask for a
 * world target and a shadow pass, and read the state the game was in when a frame was captured.
 * Nothing else in {@code com.periut.retrodragon} is meant to be called from outside, and the classes
 * this exposes -- {@link ShaderResources}, {@link ShaderFrame} -- are the whole contract.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 * <li>A mod calls {@link #install} from its initialiser. Nothing GPU-side exists yet.</li>
 * <li>The renderer comes up, on the first GL call after the window is created, and calls
 *     {@link ShaderExtension#onStart} for each installed extension in priority order. Programs are
 *     registered here.</li>
 * <li>Every frame: the game draws, and each batch is stamped with a phase and routed to a program;
 *     then the frame is replayed and the render hooks fire between passes.</li>
 * </ol>
 *
 * <p>Static because the callers are mixins on GL entry points and per-draw capture paths, which have
 * nowhere to hold an instance and are called from every corner of the game. Render thread only --
 * beta's client is single-threaded, and so is this.
 */
public final class ShaderApi {
	private static final List<ShaderExtension> EXTENSIONS = new ArrayList<>();
	private static final List<ProgramSpec> PROGRAMS = new ArrayList<>();

	/**
	 * Snapshot of {@link #EXTENSIONS} as an array, rebuilt on install.
	 *
	 * <p>Iterated once per captured batch -- a busy frame asks it a thousand times -- and an
	 * ArrayList iterator allocates. An array does not, and the list never changes during a frame.
	 */
	private static ShaderExtension[] active = new ShaderExtension[0];

	private static boolean started;

	private static int phase = DrawPhase.NONE;

	private static ShaderResources resources;

	private static ShaderExtension worldTargetOwner;
	private static int worldTargetAux;
	private static int worldTargetFormat;

	private ShaderApi() {
	}

	// --- installation ----------------------------------------------------------------------------

	/**
	 * Registers a shader mod. Call from the mod's initialiser, before any world is loaded.
	 *
	 * <p>Installing after the renderer has started is allowed and calls {@link
	 * ShaderExtension#onStart} immediately, so a mod enabled from a config screen works -- but the
	 * frame-wide resources are settled at first start, so a late arrival cannot take the world target
	 * from whoever already owns it.
	 */
	public static synchronized void install(ShaderExtension extension) {
		if (extension == null) {
			throw new IllegalArgumentException("extension must not be null");
		}
		for (ShaderExtension present : EXTENSIONS) {
			if (present.id().equals(extension.id())) {
				RetroDragon.LOGGER.warn("shader extension '{}' installed twice; ignoring the second",
					extension.id());
				return;
			}
		}
		EXTENSIONS.add(extension);
		// Highest priority first, and stable within a priority, so install order breaks ties
		// predictably rather than by whichever way the sort happened to fall.
		EXTENSIONS.sort(Comparator.comparingInt(ShaderExtension::priority).reversed());
		active = EXTENSIONS.toArray(new ShaderExtension[0]);
		RetroDragon.LOGGER.info("shader extension '{}' installed (priority {})",
			extension.id(), extension.priority());
		if (started) {
			start(extension);
		}
	}

	public static boolean hasExtensions() {
		return active.length > 0;
	}

	private static int outputFormat;

	/**
	 * The swapchain's texture format.
	 *
	 * <p>Available from {@link ShaderExtension#onStart} onward, because a composite pass has to be
	 * built against the format it writes and a pipeline bakes that in. It is not a constant: the
	 * swapchain is BGRA on Metal and RGBA elsewhere, so a pack that hard-codes one compiles fine and
	 * is rejected at draw time on half the platforms it runs on.
	 */
	public static int outputFormat() {
		return outputFormat;
	}

	/** Engine-owned: calls {@code onStart} on everything installed. */
	public static synchronized void startAll(ShaderResources shared, int surfaceFormat) {
		resources = shared;
		outputFormat = surfaceFormat;
		started = true;
		for (ShaderExtension extension : active) {
			start(extension);
		}
	}

	private static void start(ShaderExtension extension) {
		try {
			extension.onStart();
		} catch (Throwable t) {
			RetroDragon.LOGGER.error("shader extension '{}' failed to start; disabling it",
				extension.id(), t);
			remove(extension);
		}
	}

	/** Engine-owned: drops an extension that threw, without disturbing the others. */
	public static synchronized void remove(ShaderExtension extension) {
		if (EXTENSIONS.remove(extension)) {
			active = EXTENSIONS.toArray(new ShaderExtension[0]);
			if (worldTargetOwner == extension) {
				worldTargetOwner = null;
			}
		}
	}

	public static synchronized void stopAll() {
		for (ShaderExtension extension : active) {
			try {
				extension.onStop();
			} catch (Throwable t) {
				RetroDragon.LOGGER.warn("shader extension '{}' threw while stopping", extension.id(), t);
			}
		}
		started = false;
		resources = null;
		worldTargetOwner = null;
		worldColorClearClaimed = false;
		PROGRAMS.clear();
	}

	/** Engine-owned: the installed extensions, highest priority first. */
	public static ShaderExtension[] extensions() {
		return active;
	}

	// --- programs --------------------------------------------------------------------------------

	/**
	 * Adds a program and returns the id draws are routed to it with.
	 *
	 * <p>The WGSL is not compiled here. It is compiled the first time a draw actually selects the
	 * program, which is what keeps an extension that registers twenty programs from spending twenty
	 * shader compiles on a world where three of them are reached.
	 *
	 * @throws IllegalStateException if the program table is full; see {@link PipelineKey#MAX_PROGRAMS}
	 */
	public static synchronized int registerProgram(ProgramSpec spec) {
		if (spec.source() == null || spec.source().isEmpty()) {
			throw new IllegalArgumentException("program '" + spec.name() + "' has no source");
		}
		int id = builtInCount() + PROGRAMS.size();
		if (id >= PipelineKey.MAX_PROGRAMS) {
			throw new IllegalStateException("no room for program '" + spec.name() + "': the key packs "
				+ PipelineKey.MAX_PROGRAMS + " and they are all taken");
		}
		PROGRAMS.add(spec);
		RetroDragon.detail("shader program '{}' registered as id {}", spec.name(), id);
		return id;
	}

	/** How many program ids the engine itself owns; extension ids start above this. */
	public static int builtInCount() {
		return PipelineKey.PROGRAM_TERRAIN_OPAQUE + 1;
	}

	/** The spec for an extension program, or null for a built-in or an unknown id. */
	public static ProgramSpec program(int id) {
		int index = id - builtInCount();
		return index >= 0 && index < PROGRAMS.size() ? PROGRAMS.get(index) : null;
	}

	public static int programCount() {
		return builtInCount() + PROGRAMS.size();
	}

	// --- per-draw routing ------------------------------------------------------------------------

	/**
	 * The phase the game is currently drawing in; see {@link DrawPhase}.
	 *
	 * <p>Set by the engine's own mixins on the methods that define each phase. An extension may read
	 * it but should not write it -- doing so would leave the phase wrong for every draw after the one
	 * it meant to affect, since nothing resets it but the next mixin.
	 */
	public static int phase() {
		return phase;
	}

	/** Engine-owned. */
	public static void setPhase(int value) {
		phase = value;
	}

	/**
	 * Records the draws made until {@link #endCasterOnly} as shadow casters that are never drawn.
	 *
	 * <p>For geometry the game does not render but a shadow needs -- the first-person player body,
	 * above all. Beta skips the camera entity in first person, so without this the player is the one
	 * thing in the world that casts nothing.
	 *
	 * <p>Must be paired. The phase in force is restored, not reset, so this can be used from inside
	 * the entity sweep without the draws after it losing their phase.
	 */
	public static int beginCasterOnly() {
		int previous = phase;
		phase = DrawPhase.CASTER_ONLY;
		return previous;
	}

	/** @param previous the value {@link #beginCasterOnly} returned */
	public static void endCasterOnly(int previous) {
		phase = previous;
	}

	/**
	 * Which program a draw runs, asking each extension in priority order.
	 *
	 * @return {@link ShaderExtension#NO_PROGRAM} when nobody claimed it, so the caller keeps the
	 *         program the engine would have used
	 */
	public static int routeProgram(int texture, boolean lit) {
		ShaderExtension[] all = active;
		for (int i = 0; i < all.length; i++) {
			int program = all[i].routeProgram(phase, texture, lit);
			if (program != ShaderExtension.NO_PROGRAM) {
				return program;
			}
		}
		return ShaderExtension.NO_PROGRAM;
	}

	/** Whether any extension wants this draw dropped. */
	public static boolean isVisible(int texture) {
		ShaderExtension[] all = active;
		for (int i = 0; i < all.length; i++) {
			if (!all[i].isVisible(phase, texture)) {
				return false;
			}
		}
		return true;
	}

	// --- frame-wide resources --------------------------------------------------------------------

	/**
	 * Asks for the world to be rendered into a target the extension owns rather than the swapchain.
	 *
	 * <p>This is what makes a composite chain possible: the world lands in a float target with
	 * headroom above 1.0, the extension tonemaps it in {@link ShaderExtension#onComposite}, and the
	 * GUI is drawn on the result. Without it the world is written straight to an 8-bit swapchain and
	 * every highlight is clamped before anything can look at it.
	 *
	 * @param auxTargets extra colour attachments the world pass writes alongside the scene -- a
	 *                   G-buffer. 0 for none.
	 * @param format     the WebGPU texture format for target 0; {@code RGBA16Float} is the usual
	 *                   choice, and the engine falls back to it for an unsupported one.
	 * @return true if this extension got it; false if a higher-priority one already had it, in which
	 *         case {@link ShaderExtension#onSuperseded} has been called
	 */
	public static synchronized boolean requestWorldTarget(ShaderExtension self, int auxTargets,
			int format) {
		if (worldTargetOwner != null && worldTargetOwner != self) {
			self.onSuperseded("world target", worldTargetOwner);
			return false;
		}
		worldTargetOwner = self;
		worldTargetAux = Math.max(0, auxTargets);
		worldTargetFormat = format;
		return true;
	}

	/** Engine-owned: who owns the world redirect, or null when nobody asked. */
	public static ShaderExtension worldTargetOwner() {
		return worldTargetOwner;
	}

	public static int worldTargetAux() {
		return worldTargetAux;
	}

	public static int worldTargetFormat() {
		return worldTargetFormat;
	}

	private static boolean worldColorClearClaimed;

	/**
	 * Declares that the extension paints every world pixel itself, so the engine should not clear
	 * colour before the world.
	 *
	 * <p>For a pack drawing a skybox in {@link ShaderExtension#onWorldBegin}. The DEPTH clear still
	 * happens -- the world needs it and a skybox does not write depth.
	 *
	 * <p>Getting this wrong is visible rather than subtle: claim it and paint nothing, and the world
	 * is composited over last frame's image.
	 */
	public static void claimWorldColorClear(boolean claim) {
		worldColorClearClaimed = claim;
	}

	/** Engine-owned. */
	public static boolean worldColorClearClaimed() {
		return worldColorClearClaimed;
	}

	/**
	 * The shared {@code @group(1)} resources: uniforms, textures, the shadow map.
	 *
	 * <p>Shared deliberately. Two extensions cannot both own the frame's look anyway -- one of them
	 * is going to lose the world target -- so giving each its own resource group would mean a second
	 * bind group layout, a second pipeline layout, and a bind per draw to switch between them, for a
	 * configuration that does not usefully exist.
	 */
	public static ShaderResources resources() {
		return resources;
	}

	// --- what the game was doing -----------------------------------------------------------------

	/**
	 * The modelview in force right now, column-major, 16 floats -- the live array, not a copy.
	 *
	 * <p>Valid during {@link ShaderExtension#onWorldFrame}, which is called from inside world
	 * rendering with beta's own camera transform loaded. Copy what you need; the array is the shim's
	 * matrix stack top and the next {@code glPopMatrix} rewrites it.
	 */
	public static float[] modelView() {
		return com.periut.retrodragon.shim.ShimTracker.shim().state().modelView();
	}

	public static float[] projection() {
		return com.periut.retrodragon.shim.ShimTracker.shim().state().projection();
	}

	/**
	 * The client singleton, whose own field is private.
	 *
	 * <p>Here so an extension does not need a mixin of its own to reach the world, the camera and
	 * the options -- which is otherwise the first thing every extension would have to write, for a
	 * field that is private only by accident of beta's era.
	 */
	public static net.minecraft.client.Minecraft client() {
		return com.periut.retrodragon.mixin.MinecraftAccessor.retroperf$instance();
	}
}
