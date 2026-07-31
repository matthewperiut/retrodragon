package com.periut.retrodragon.render;

/**
 * The terrain vertex layout -- the one stream where the size of a vertex is worth arguing about.
 *
 * <h2>Why terrain gets its own layout at all</h2>
 *
 * Every other consumer of {@code fixedfunc.wgsl} draws hundreds or thousands of vertices a frame.
 * Terrain draws millions, and it draws them from buffers that persist across frames, so its vertex
 * size sets both the per-frame fetch bandwidth and the resident footprint of the whole shared
 * arena. It is also the only stream whose shader is ours end to end, so it is the only one whose
 * layout can change without a matching change somewhere in beta.
 *
 * <h2>The two layouts</h2>
 *
 * <pre>
 *   LEGACY   32B   pos 3xf32 @0   uv 2xf32 @12   colour unorm8x4 @20   normal snorm8x4 @24 + pad
 *   COMPACT  20B   pos 3xf32 @0   uv 2xunorm16 @12   colour unorm8x4 @16
 * </pre>
 *
 * Two things go, and neither is a compromise:
 *
 * <ul>
 * <li><b>The normal, and its pad byte, are dead weight.</b> Beta bakes terrain lighting into the
 *     vertex colour in the block renderer; {@code terrain.wgsl} never reads a normal and never did.
 *     Eight bytes of every terrain vertex were fetched to be ignored.
 * <li><b>The texture coordinate does not need 32 bits per axis.</b> It addresses a 256-texel atlas
 *     and never leaves 0..1, so unorm16 resolves it to 1/256 of a texel -- two orders of magnitude
 *     finer than the nearest-neighbour sampling this game does anything with. On a 512-texel sheet
 *     (RetroAPI) it is still 1/128 of a texel.
 * </ul>
 *
 * The position stays full float. It is tempting to quantise it to the section the way Sodium does,
 * but Sodium can: its vertices are section-relative and a per-draw uniform supplies the origin.
 * Here the whole point of the shared arena is that ADJACENT SECTIONS MERGE INTO ONE DRAW, so there
 * is no per-draw origin to add back -- a merged run spans many sections under one uniform block.
 * Quantising would mean giving that up, and the merge is worth more than four bytes.
 *
 * <h2>Why one shader serves both</h2>
 *
 * A vertex format is a conversion applied on fetch, not a type the shader sees. {@code Unorm16x2}
 * and {@code Float32x2} both arrive as {@code vec2<f32>}, so {@code terrain.wgsl} is identical for
 * either and the choice is purely a pipeline-side one. That is what makes the layout switchable at
 * runtime instead of a fork of the program.
 *
 * <p>COMPACT is WebGPU-only. The GL backend draws these buffers through
 * {@code glTexCoordPointer}, whose integer formats are not normalised in fixed function, so there
 * is no GL 2.1 spelling of a unorm16 texture coordinate. GL keeps LEGACY; nothing is lost, because
 * GL is the fallback path and its ceiling is elsewhere.
 *
 * <h2>What it is worth, and why the offscreen bench said otherwise</h2>
 *
 * <b>In game: 1.77 ms to 1.57 ms median, about 1.13x</b> at the standard bench vantage.
 * <b>Offscreen: nothing at all</b> -- 1.75 ms against 1.74 ms.
 *
 * The offscreen figure is the misleading one, and the reason is worth keeping. {@code WebGpuBench}
 * allocates its sections once, in order, into an arena sized exactly to hold them, and then draws
 * them in that same order every frame. That is a perfectly sequential read of a small buffer, which
 * prefetches perfectly and never misses; halving the stride of a stream like that changes nothing,
 * because bandwidth was never what bound it. A real world allocates and frees sections continuously
 * as chunks load and blocks change, scatters them across a 128 MB arena, and draws whatever the
 * frustum happens to contain. There the stride is felt.
 *
 * So the bench measures per-vertex COST well and per-vertex SIZE badly, and a layout change has to
 * be confirmed in game. That is not a flaw to fix by making the bench allocate randomly -- it would
 * stop being a stable measurement of anything else -- it is a limit to remember it has.
 */
public final class TerrainVertex {
	/** Beta's own layout: what the Tessellator packs and what {@code fixedfunc.wgsl} declares. */
	public static final int LEGACY_STRIDE = 32;
	/** Normal dropped, texture coordinate to unorm16. */
	public static final int COMPACT_STRIDE = 20;

	/**
	 * {@code -Dretroperf.compactTerrain=false} reverts to the 32-byte layout on both backends.
	 *
	 * <p>Kept as a switch rather than deleted because the two layouts differ in what they can
	 * represent, and a texture-coordinate artifact that appears only in the world is exactly the
	 * kind of thing that wants a one-flag comparison rather than a rebuild.
	 */
	public static final boolean COMPACT =
		!"false".equalsIgnoreCase(System.getProperty("retroperf.compactTerrain"));

	private static boolean resolved;

	private TerrainVertex() {
	}

	/**
	 * Called once from {@link RenderBackend}, before any geometry is built.
	 *
	 * <p>A plain static rather than asking {@link WebGpuFrame} each time. The mesher runs on worker
	 * threads and would otherwise be able to trigger renderer initialisation from one of them; and
	 * more importantly the packing has to be a decision the whole process already agrees on, since
	 * the pipeline that will read these bytes is built somewhere else entirely.
	 *
	 * <p>Left false in an offscreen harness that never selects a backend, so the tests exercise
	 * beta's layout unless they ask for the other one explicitly.
	 */
	public static void select(boolean webgpu) {
		resolved = COMPACT && webgpu;
	}

	/** Whether terrain buffers are packed compactly, which only the WebGPU backend can consume. */
	public static boolean compact() {
		return resolved;
	}

	/** Bytes per terrain vertex under the layout {@code compact} selects. */
	public static int stride(boolean compact) {
		return compact ? COMPACT_STRIDE : LEGACY_STRIDE;
	}

	/** Ints per terrain vertex; both strides are a whole number of them. */
	public static int strideInts(boolean compact) {
		return stride(compact) / 4;
	}

	/**
	 * Packs a texture coordinate axis into unorm16.
	 *
	 * <p>Scaled by 65536, NOT by 65535, even though the hardware decodes a unorm16 as {@code p/65535}.
	 * The two do not have to agree, and making them disagree is what makes a TILE BOUNDARY exact.
	 *
	 * <p>Every terrain coordinate beta emits is a multiple of {@code 1/256} -- a tile edge is
	 * {@code k/16} -- and no multiple of {@code 1/256} is representable as {@code p/65535}. Rounding
	 * to the nearest one moves the coordinate off the boundary by up to half a step, and which SIDE
	 * it lands on depends on {@code k}: {@code 13/16} packs to 53247, which decodes to 0.8124971 --
	 * a hair BELOW the boundary, so the terrain program's {@code floor(uv / tileSize)} reads the
	 * outermost sliver of that tile as belonging to the PREVIOUS one and samples a neighbouring
	 * texture. Beta's still-water tile is 13 across and 12 down, and every tile around it is the
	 * unused magenta placeholder, so on water the slip shows as bright specks along the -X and -Z
	 * edge of each surface quad; elsewhere it lands on another block texture and mostly hides.
	 *
	 * <p>Scaling by 65536 makes every {@code k/65536} -- so every {@code k/256} and every tile edge --
	 * land on an integer, which the decode then reproduces as exactly {@code p * 65536/65535}: a
	 * clean SCALE of the whole atlas rather than a per-coordinate rounding error. A scale is
	 * something the shader can be told about, and {@link #shaderAtlasTexels} is where it is told.
	 * 65536 itself is the only value that cannot be encoded; {@code uv == 1} clamps to 65535 and
	 * lands 1/256 of a texel short of the sheet's edge, which no fragment can reach.
	 *
	 * <p>Clamped rather than wrapped. A terrain coordinate outside 0..1 is not a tiling request --
	 * the atlas has no meaningful texel there -- so the honest reproduction of what the sampler
	 * would have done with a clamped address is to clamp here, where it costs nothing.
	 */
	public static int packUv(float uv) {
		int packed = Math.round(uv * 65536.0F);
		return packed < 0 ? 0 : Math.min(packed, 65535);
	}

	/**
	 * The atlas width the terrain program must be told, given how this layout's UVs decode.
	 *
	 * <p>{@link #packUv} leaves a compact coordinate scaled by {@code 65536/65535}, so the shader
	 * sees a sheet that is fractionally LARGER than the real one. Shrinking the texel count it is
	 * given by the same factor puts every quantity it derives -- the tile size, the tile origin, the
	 * per-mip inset, the tap footprint -- back into the coordinate space the vertices actually
	 * arrive in. Nothing else has to change, and the sampler still addresses the real sheet: the
	 * clamp keeps the tap half a texel inside its tile, which the residual 1/256 of a texel cannot
	 * push out.
	 *
	 * <p>The legacy layout stores the coordinate as a float and needs no correction, which is why
	 * this is a function of the layout rather than a constant folded into the shader.
	 */
	public static float shaderAtlasTexels(float texels) {
		return compact() ? texels * (65535.0F / 65536.0F) : texels;
	}
}
