package com.periut.retrodragon;

/**
 * Per-domain kill switches. Each one reverts that domain to stock beta rendering, so a
 * regression can be attributed without rebuilding, and a mod that fights our owned GL state
 * has an escape hatch.
 */
public final class Config {
	/** {@code -Dretroperf.terrain=false} -- chunk geometry back to vanilla display lists. */
	public static final boolean TERRAIN = !"false".equals(System.getProperty("retroperf.terrain"));

	/**
	 * {@code -Dretroperf.noAutoPause=true} -- ignore focus loss: no pause menu, no idle sleep.
	 * Required for unattended screenshots and for any benchmark run in a background window.
	 */
	public static final boolean NO_AUTO_PAUSE = Boolean.getBoolean("retroperf.noAutoPause");

	/**
	 * {@code -Dretroperf.msaa=N} -- multisample count for the whole frame. **DEFAULT OFF (0).**
	 *
	 * MSAA anti-aliases every quad boundary, not just silhouettes, and beta's terrain is thousands
	 * of adjacent coplanar 1x1 quads. Wherever those shared edges are not bit-identical, partial
	 * sample coverage lets the background bleed through -- a one-pixel seam on the edge of EVERY
	 * block. Measured: 11215 seam pixels at 4x, 0 with MSAA off. Not caused by alpha-to-coverage
	 * (11173 with it disabled), nor by mipmaps, nor by the shader -- all were ruled out separately.
	 *
	 * Post-process AA (FXAA/SMAA) is the right path here: it works on the resolved image and so
	 * cannot open seams between adjacent quads.
	 */
	public static final int MSAA_SAMPLES = Integer.getInteger("retroperf.msaa", 0);

	/**
	 * {@code -Dretroperf.alphaToCoverage=false} to disable. Turns cutout alpha into sample coverage
	 * so leaves, grass and glass get anti-aliased interiors -- MSAA alone cannot do this, because
	 * the aliasing comes from the alpha test discarding fragments inside the triangle.
	 */
	public static final boolean ALPHA_TO_COVERAGE = !"false".equals(System.getProperty("retroperf.alphaToCoverage"));

	/**
	 * {@code -Dretroperf.mipmap=false} to disable. **ON by default** -- it is the only thing that
	 * actually fixes shimmer, which is what "aliasing" means in motion on this game.
	 *
	 * Measured on the foliage half of the bench vantage, shimmer = mean pixel change under a
	 * SUB-PIXEL (0.041 deg) camera rotation, sharpness = mean neighbouring-pixel gradient:
	 *
	 *   vanilla        shimmer 9.52  sharpness 11.50
	 *   fxaa + rgss    shimmer 7.62  sharpness  5.08   <- the old default: 56% of the detail gone
	 *   rgss alone     shimmer 9.56  sharpness  8.72   <- does NOTHING for shimmer
	 *   mipmap + rgss  shimmer 5.05  sharpness  7.30   <- the default now
	 *   mipmap alone   shimmer 7.16  sharpness  5.34
	 *
	 * So mipmaps halve the shimmer and RGSS buys back the sharpness they cost. This is also what
	 * modern Minecraft does (26.1.2 Fancy = terrain mipmaps + RGSS, no screen-space pass), which is
	 * why it looks stable in motion where this did not.
	 *
	 * Applies to terrain.png ONLY. An earlier version keyed on image dimensions and so also
	 * mipmapped the font sheet and the GUI sheets, which broke text and the crosshair.
	 *
	 * Beta ships a mipmap generator disabled behind a static nothing sets. It is left disabled: it
	 * builds an incomplete chain (levels 1..4 of a 256x256 atlas, no GL_TEXTURE_MAX_LEVEL) which
	 * samples as flat white. `TerrainMipmaps` replaces it with a correct chain plus a solidify
	 * pre-pass (beta stores black RGB under transparent texels, which a linear mean drags in as
	 * dark fringes) and Castano alpha-coverage rescale (otherwise averaged foliage falls under the
	 * 0.5 cutoff and thins out with distance).
	 */
	public static final boolean MIPMAP = !"false".equals(System.getProperty("retroperf.mipmap"));

	/**
	 * {@code -Dretroperf.shader=false} to disable. **On by default.** Draws terrain through
	 * RetroDragon's own GLSL 120 program instead of fixed function.
	 *
	 * Verified appearance-identical to fixed function: signal 5.98% against a 7.44% same-config
	 * floor, i.e. below the noise. It reproduces beta's fog exactly, including the EXP modes beta
	 * uses underwater (density 0.1) and in lava (density 2.0) -- see {@link FogState}. Costs ~4% at
	 * 854x480 and is the prerequisite for RGSS and for correct mipmapping.
	 */
	public static final boolean SHADER = !"false".equals(System.getProperty("retroperf.shader"));

	/**
	 * {@code -Dretroperf.fxaa=true} to enable. **OFF by default.** Post-process anti-aliasing;
	 * works where MSAA cannot, because it runs on the resolved image and so cannot open seams
	 * between beta's adjacent coplanar block quads (verified: 0 seam pixels, vs 11215 under 4x MSAA).
	 *
	 * Off because it is the wrong tool here. It is a SPATIAL edge filter with no temporal term, so
	 * it cannot fix shimmer -- measured, it removed only 20% of the shimmer while costing 56% of the
	 * image sharpness (see {@link #MIPMAP}). That trade is why the world looked blurry. Mipmaps do
	 * the job properly. Kept for anyone who wants softened silhouettes.
	 */
	public static final boolean FXAA = Boolean.getBoolean("retroperf.fxaa");

	/**
	 * {@code -Dretroperf.webgpu=false} falls back to the OpenGL path.
	 *
	 * On by default. Where Dawn is unavailable -- an old driver, a platform whose native we do not
	 * ship -- {@code GpuBackend} degrades to OpenGL on its own, so defaulting to on costs nothing
	 * beyond a device creation that fails fast.
	 */
	public static final boolean WEBGPU = !"false".equals(System.getProperty("retroperf.webgpu"));

	/**
	 * {@code -Dretroperf.quadVertices=false} -- store quads as beta does, six vertices each.
	 *
	 * **On by default, WebGPU only.** Beta's Tessellator expands every quad into two triangles on
	 * the CPU because {@code TRIANGLE_MODE} is set unconditionally in its static initialiser. The
	 * WebGPU backend already expands quads with a static index buffer at draw time, so the CPU
	 * expansion is the same work done twice -- and it costs 50% more vertices everywhere in the game.
	 *
	 * Measured 1.4-1.55x on the offscreen terrain bench for identical pixels. See
	 * {@link com.periut.retrodragon.render.QuadVertices}.
	 */
	public static final boolean QUAD_VERTICES = !"false".equals(System.getProperty("retroperf.quadVertices"));

	/** Relative luma contrast needed before FXAA blends. Higher = preserves more pixel-art detail. */
	public static final float FXAA_THRESHOLD =
		Float.parseFloat(System.getProperty("retroperf.fxaaThreshold", "0.166"));

	/** Absolute luma floor, stops dark regions shimmering. */
	public static final float FXAA_THRESHOLD_MIN =
		Float.parseFloat(System.getProperty("retroperf.fxaaThresholdMin", "0.0833"));

	/**
	 * {@code -Dretroperf.occlusion=true} -- **OFF by default, pending a re-measurement.**
	 *
	 * BFS visibility over the section graph. Backend-agnostic: {@code SectionDrawer} consults it on
	 * both the GL and WebGPU paths, so turning it on affects both.
	 *
	 * The bug this was disabled for is FIXED. {@link com.periut.retrodragon.render.OcclusionCuller}
	 * used to mark a section visited on first arrival and never re-expand it when it was later
	 * reached through a different entry face -- a sight line entering from another direction can leave
	 * through faces the first path could not, so reachability was under-approximated and visible
	 * terrain got culled. It now tracks visited per (section, entry direction).
	 *
	 * That fix is guarded by {@code ./gradlew occlusionTest}, which walks a corridor lattice where
	 * the target is reachable ONLY by entering the corner a second time. The corridor matters: an
	 * earlier version of the check used an open lattice, where the target is reachable half a dozen
	 * other ways and the test passed with the bug deliberately reintroduced.
	 *
	 * **Measured, and it should stay off -- for a completely different reason than it was turned off
	 * for.** It is not a marginal win with a correctness worry. It is 2.5x SLOWER:
	 *
	 * <pre>
	 *   vantage      occlusion off   occlusion on
	 *   eye  2       1.42 ms         3.52 ms      median frame time
	 *   eye 24       1.67 ms         4.47 ms
	 * </pre>
	 *
	 * The BFS runs once per frame over the whole section graph, on the render thread, tracking
	 * visited per (section, entry direction) -- six times the state of a plain visited set. What it
	 * saves is the vertex transform of the sections it culls. On this GPU that is a bad trade twice
	 * over: overdraw is already free (Apple's TBDR rejects hidden fragments in hardware, measured at
	 * 64x overdraw for 0.10 ms), and the arena has already collapsed the pass to a handful of draws,
	 * so culling a section removes neither fill nor a draw call.
	 *
	 * It would need to become a fraction of its current cost -- cached across frames, or moved off the
	 * render thread -- before the question is worth reopening. The earlier 20.29% over-cull note is
	 * superseded: a same-vantage pixel comparison with ticks frozen now puts it at 2.53% of pixels
	 * differing by more than 16/255 against a 1.06% floor, so some divergence remains, but the
	 * performance number settles it without needing to resolve that.
	 *
	 * An earlier, worse bug is also fixed: the monotonic-outward test was inverted, which pruned the
	 * valid outward directions and kept the invalid ones (67% of the frame went missing).
	 */
	public static final boolean OCCLUSION = Boolean.getBoolean("retroperf.occlusion");

	/**
	 * {@code -Dretroperf.rgss=false} to disable. **On by default.** Rotated-grid supersampling of
	 * the terrain texture -- it sharpens distant detail by supersampling rather than blurring it
	 * away like mipmaps, so it is the texture-AA option that keeps beta's crisp look. Requires
	 * the terrain shader. Four taps on a grid rotated off the pixel axes, which resolves
	 * near-horizontal and near-vertical detail much better than an axis-aligned box filter.
	 */
	public static final boolean RGSS = !"false".equals(System.getProperty("retroperf.rgss"));

	private Config() {
	}
}
