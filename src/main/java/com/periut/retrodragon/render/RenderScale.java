package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.RetroOptions;
import com.periut.retrodragon.window.sdl.Sdl3Window;

/**
 * The resolution the WORLD is rendered at, as a multiple of the window's logical size, and the
 * filter used to resolve it back to the window.
 *
 * <h2>What 1.0 means, and where retina went</h2>
 *
 * <b>1.0 is the window's LOGICAL size</b> -- 854x480 on a default window, on every machine and
 * whatever the display's scale factor is. Retina is not a separate concept here: a HiDPI window
 * whose drawable is 1708x960 is simply scale 2.0, and {@link #defaultScale()} seeds the setting from
 * {@code Sdl3Window.pixelsPerPoint()} so an untouched install renders exactly what it rendered
 * before this class existed.
 *
 * <p>That is the only definition under which a mod can say "give me half resolution" and mean it.
 * Basing 1.0 on the DRAWABLE would make the same call mean 427x240 on one machine and 854x480 on
 * another, which is not a setting, it is a lottery.
 *
 * <h2>The GUI is not scaled</h2>
 *
 * Only the world. beta draws the HUD immediately after the world into the same target, so the two
 * are separated at the seam the renderer already has: {@code AntiAliasMixin} resolves the offscreen
 * frame at {@code InGameHud.render}, and {@code DrawList.firstNonWorldBatch} finds the same boundary
 * for the WebGPU replay. Text and the hotbar stay at the drawable's native resolution no matter how
 * low the world goes, which is the entire reason for doing it this way rather than scaling the whole
 * frame.
 *
 * <h2>Filters are an open registry, not an enum</h2>
 *
 * {@link com.periut.retrodragon.api.RetroSettings} takes and returns filter names as {@code String},
 * so a mod compiled against {@code "bicubic"} keeps working when a temporal upscaler is added later.
 * An enum in the signature would make that a breaking change, and this API exists to be called from
 * another mod by reflection.
 *
 * <p>Availability is not fixed: it depends on the backend, the platform, and which DIRECTION the
 * scale goes. {@link #FSR1} is an upsampler and is meaningless above 1.0, where the job is
 * reconstructing a downsample; {@link #METALFX} needs Metal, so it exists only on macOS under
 * WebGPU. A mod is expected to ask {@link #availableFilters(boolean)} rather than assume, which is
 * why that method takes the direction.
 */
public final class RenderScale {

	// --- filter names -----------------------------------------------------------------------------
	// Lowercase, hyphenated, stable forever. These strings are API.

	/** Point sampling. Crisp, and the right default for pixel art at a whole-number ratio. */
	public static final String NEAREST = "nearest";
	/**
	 * Point sampling constrained to a whole-number ratio, with the remainder letterboxed.
	 *
	 * <p>Plain nearest at a fractional ratio duplicates some rows and columns and not others, which
	 * on a 16x16 texel grid reads as the texture wobbling. Snapping the ratio trades a border for an
	 * evenly scaled image.
	 */
	public static final String INTEGER = "integer";
	/** The conventional smooth resolve. Cheap; soft on pixel art. */
	public static final String BILINEAR = "bilinear";
	/**
	 * Catmull-Rom. The good choice ABOVE 1.0, where a supersampled frame is being reduced and
	 * bilinear throws away most of what the extra samples bought.
	 */
	public static final String BICUBIC = "bicubic";
	/**
	 * AMD FidelityFX Super Resolution 1: edge-adaptive spatial upsampling plus contrast-adaptive
	 * sharpening. Spatial only, so unlike every later FSR it needs no motion vectors, no depth and no
	 * jitter -- which is what makes it usable here at all.
	 *
	 * <p>Below 1.0 only. It is an upsampler; asking it to downsample a supersampled frame is not what
	 * the kernel does.
	 */
	public static final String FSR1 = "fsr1";
	/**
	 * Apple MetalFX spatial upscaling. macOS, WebGPU backend, below 1.0 only.
	 *
	 * <p>Reachable where DLSS and XeSS are not: {@code MTLFXSpatialScaler} needs no motion vectors,
	 * and Dawn can share textures with Metal through IOSurface. Quality is in the same class as
	 * {@link #FSR1}, so this exists as a platform-native option rather than a reason to prefer macOS.
	 */
	public static final String METALFX = "metalfx-spatial";

	private static final String[] ALL = { NEAREST, INTEGER, BILINEAR, BICUBIC, FSR1, METALFX };

	/** {@code -Dretrodragon.renderScale=<float>} seeds the initial value; 0 means "follow retina". */
	private static final float FORCED_SCALE =
		Float.parseFloat(System.getProperty("retrodragon.renderScale", "0"));

	/** Clamped hard: below this the world is unreadable, above it the cost is quartic and pointless. */
	public static final float MIN_SCALE = 0.1F;
	public static final float MAX_SCALE = 4.0F;

	/**
	 * {@code -Dretrodragon.scaleFilter=<name>} picks the starting filter.
	 *
	 * <p>Validated lazily rather than here: {@link #isAvailable} can depend on the backend, which is
	 * not chosen yet when this class initialises.
	 */
	private static final String FORCED_FILTER =
		System.getProperty("retrodragon.scaleFilter", "").trim().toLowerCase(java.util.Locale.ROOT);

	private static volatile float scale;
	private static volatile String filter = FORCED_FILTER.isEmpty() ? NEAREST : FORCED_FILTER;
	private static volatile boolean seeded;

	private RenderScale() {
	}

	/**
	 * What the scale is when nothing has set one: the window's current pixels-per-point.
	 *
	 * <p>This is what makes the change invisible by default. Retina on gives 2.0, so the world renders
	 * at the full drawable exactly as it always did; retina off gives 1.0, and the drawable IS the
	 * logical size, so again nothing moves. The setting only starts doing something once a mod or a
	 * property asks it to.
	 */
	public static float defaultScale() {
		if (FORCED_SCALE > 0.0F) {
			return clamp(FORCED_SCALE);
		}
		float perPoint = Sdl3Window.pixelsPerPoint();
		return perPoint > 0.0F ? clamp(perPoint) : 1.0F;
	}

	public static float scale() {
		if (!seeded) {
			// Seeded lazily rather than in a static initialiser: pixelsPerPoint is 1.0 until the
			// window exists, and this class can be touched by a mod's preLaunch before it does.
			seed();
		}
		return scale;
	}

	private static synchronized void seed() {
		if (!seeded && Sdl3Window.isCreated()) {
			scale = defaultScale();
			seeded = true;
		} else if (!seeded) {
			scale = 1.0F;
		}
	}

	/** @return the value actually adopted, which may be clamped. */
	public static float setScale(float wanted) {
		float applied = clamp(wanted);
		seeded = true;
		scale = applied;
		if (applied != wanted) {
			RetroDragon.LOGGER.warn("renderScale {} clamped to {} (valid range {} to {})",
				wanted, applied, MIN_SCALE, MAX_SCALE);
		}
		return applied;
	}

	private static float clamp(float value) {
		if (Float.isNaN(value)) {
			return 1.0F;
		}
		return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}

	// --- resolved sizes ---------------------------------------------------------------------------

	/**
	 * The window's logical size, which is what scale 1.0 means.
	 *
	 * <p>Derived by dividing the drawable by the density rather than asking SDL for the logical size
	 * directly, so it stays consistent with {@link Sdl3Window#width()}'s cache and cannot disagree
	 * with it by a rounding step mid-resize.
	 */
	public static int logicalWidth() {
		float[] m = Sdl3Window.metricsSnapshot();
		return Math.max(1, Math.round(m[0] / Math.max(0.01F, m[2])));
	}

	public static int logicalHeight() {
		float[] m = Sdl3Window.metricsSnapshot();
		return Math.max(1, Math.round(m[1] / Math.max(0.01F, m[2])));
	}

	/**
	 * Never allocate a target bigger than this on either axis.
	 *
	 * <p>Not a preference, a backstop. These numbers size a GPU texture, and a resize is precisely
	 * when they can be briefly wrong -- a fullscreen transition changes the drawable and the density
	 * together, and anything derived from both can catch them mid-change. 8192 is comfortably above
	 * any real display at scale 4.0 and comfortably below the point where a driver refuses or dies.
	 */
	private static final int MAX_TARGET = 8192;

	/** The framebuffer the WORLD renders into. */
	public static int worldWidth() {
		return sized(logicalWidth());
	}

	public static int worldHeight() {
		return sized(logicalHeight());
	}

	private static int sized(int logical) {
		return Math.max(1, Math.min(MAX_TARGET, Math.round(logical * scale())));
	}

	/**
	 * Whether the world target differs from the drawable at all.
	 *
	 * <p>When it does not, every backend skips the extra target and the resolve pass entirely and
	 * draws as it did before. That is the default case, so it has to be free.
	 *
	 * <p>Both sizes come from ONE metrics snapshot. Comparing a freshly computed target against a
	 * separately read drawable can straddle a resize and answer for two different window sizes, which
	 * during a fullscreen transition means claiming a target matches when it does not.
	 */
	public static boolean active() {
		float[] m = Sdl3Window.metricsSnapshot();
		return !matchesDrawable(m);
	}

	/** True when the world target is SMALLER than the drawable, so the resolve is an upscale. */
	public static boolean upscaling() {
		float[] m = Sdl3Window.metricsSnapshot();
		return sized(logicalFrom(m, 0)) < (int) m[0];
	}

	private static boolean matchesDrawable(float[] m) {
		return sized(logicalFrom(m, 0)) == (int) m[0] && sized(logicalFrom(m, 1)) == (int) m[1];
	}

	private static int logicalFrom(float[] m, int axis) {
		return Math.max(1, Math.round(m[axis] / Math.max(0.01F, m[2])));
	}

	// --- filters ----------------------------------------------------------------------------------

	public static String filter() {
		return filter;
	}

	/**
	 * @return the filter actually adopted. An unknown or currently-unavailable name falls back to
	 *     {@link #NEAREST} with a warning rather than throwing, because this is called from another
	 *     mod and a bad string there should not take the game down.
	 */
	public static String setFilter(String wanted) {
		String name = wanted == null ? "" : wanted.trim().toLowerCase(java.util.Locale.ROOT);
		if (!isKnown(name)) {
			RetroDragon.LOGGER.warn("unknown scale filter '{}'; keeping {}", wanted, filter);
			return filter;
		}
		if (!isAvailable(name)) {
			RetroDragon.LOGGER.warn("scale filter '{}' is not available on this run; using {}",
				name, NEAREST);
			filter = NEAREST;
			return filter;
		}
		filter = name;
		return filter;
	}

	private static boolean isKnown(String name) {
		for (String candidate : ALL) {
			if (candidate.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a filter can run at all in this process, ignoring the current scale direction.
	 *
	 * <p>Separate from direction so a mod building a settings screen can grey an entry out for the
	 * right reason: "your machine cannot do this" and "this does not apply at this scale" are
	 * different messages.
	 */
	public static boolean isAvailable(String name) {
		if (METALFX.equals(name)) {
			return MetalFxScaler.isSupported();
		}
		return isKnown(name);
	}

	/**
	 * The filters worth offering for a given direction.
	 *
	 * @param upscale true for a world target smaller than the window, false for supersampling
	 */
	public static String[] availableFilters(boolean upscale) {
		java.util.List<String> out = new java.util.ArrayList<>(ALL.length);
		for (String name : ALL) {
			if (!isAvailable(name) || !appliesTo(name, upscale)) {
				continue;
			}
			out.add(name);
		}
		return out.toArray(new String[0]);
	}

	/**
	 * Direction applicability.
	 *
	 * <p>{@link #FSR1} and {@link #METALFX} are upsamplers and are not offered above 1.0.
	 * {@link #BICUBIC} is offered in both directions but is the one that earns its cost downsampling.
	 * {@link #INTEGER} only means anything when enlarging.
	 */
	public static boolean appliesTo(String name, boolean upscale) {
		return switch (name) {
			case FSR1, METALFX, INTEGER -> upscale;
			default -> true;
		};
	}

	/** The filter to actually use this frame, after direction and availability are applied. */
	public static String effectiveFilter() {
		String current = filter;
		boolean up = upscaling();
		if (!isAvailable(current) || !appliesTo(current, up)) {
			// Not a warning: the scale can be changed without the filter being changed with it, and
			// nagging every frame about a combination the caller may be about to fix is noise.
			return up ? NEAREST : BILINEAR;
		}
		return current;
	}
}
