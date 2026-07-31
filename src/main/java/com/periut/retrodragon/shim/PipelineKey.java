package com.periut.retrodragon.shim;

/**
 * The pipeline-defining half of fixed-function state, packed into one long.
 *
 * WebGPU bakes blend, depth, cull and topology into an immutable pipeline object, while GL lets
 * beta toggle them between any two draws. So the shim splits state in two: whatever changes a
 * pipeline goes in this key and selects a cached pipeline; everything else (matrices, colour, fog,
 * alpha reference) is a uniform write. Getting that split wrong is the difference between a handful
 * of pipelines and a stall on every draw.
 *
 * Packed into a primitive long rather than an object so lookups do not allocate -- this is consulted
 * once per draw, and beta issues on the order of a hundred non-terrain draws a frame.
 *
 * The alpha TEST is deliberately NOT here. It is a uniform plus a `discard` in the shader, so
 * changing the reference value does not need a new pipeline. Putting it in the key would multiply
 * the pipeline count for no benefit.
 */
public final class PipelineKey {
	// Bit budget, low to high. Widths are sized to the GL enums beta actually uses.
	private static final int BLEND_SRC_BITS = 4;
	private static final int BLEND_DST_BITS = 4;
	private static final int DEPTH_FUNC_BITS = 3;
	private static final int CULL_BITS = 2;
	/**
	 * Signed width for each glPolygonOffset term, so a field spans -32..31.
	 *
	 * <p>Rounded to whole numbers deliberately. GL's offset is {@code factor * dz + units * r},
	 * where {@code r} is the smallest resolvable depth difference -- a scale on which beta's only
	 * non-zero use, the block-breaking overlay's {@code glPolygonOffset(-3, -3)}, is already coarse.
	 * Keeping the field small keeps the pipeline count small, which matters: every distinct key is
	 * a separate compiled pipeline, and an earlier version of this key exploded to 36864 of them.
	 */
	private static final int OFFSET_BITS = 6;

	private static final int TOPOLOGY_BITS = 3;
	/**
	 * Six, not four. The three built-in programs left eleven spare slots, which was ample while the
	 * engine owned every shader -- but a shader mod registers a program per draw family (sky, sunset
	 * fan, sun, moon, clouds, weather, entities, particles, hand, water, damaged block, spider eyes,
	 * shadow casters, plus its own terrain pair), and two mods coexisting doubles that. Sixty-four
	 * costs two bits of a long that has 27 to spare and removes the ceiling as a design concern.
	 */
	private static final int PROGRAM_BITS = 6;

	/** How many distinct programs a key can address; see {@link #PROGRAM_BITS}. */
	public static final int MAX_PROGRAMS = 1 << PROGRAM_BITS;

	/**
	 * Which shader the draw uses. Part of the key because a pipeline bakes in its shader module, so
	 * two draws with identical blend and depth state still need separate pipelines if they run
	 * different programs.
	 */
	public static final int PROGRAM_FIXED_FUNCTION = 0;
	/** Terrain: adds tile-clamped LOD selection and rotated-grid supersampling. */
	public static final int PROGRAM_TERRAIN = 1;
	/**
	 * Terrain with the alpha test compiled out, so the shader contains no {@code discard} and the
	 * driver can keep early depth testing on.
	 *
	 * <p>Same source as {@link #PROGRAM_TERRAIN} with one region stripped -- see {@code terrain.wgsl}
	 * -- so the two cannot drift apart in anything but the alpha test.
	 */
	public static final int PROGRAM_TERRAIN_OPAQUE = 2;

	public static final int CULL_NONE = 0;
	public static final int CULL_BACK = 1;
	public static final int CULL_FRONT = 2;

	/**
	 * Topologies WebGPU has natively. Beta's {@code GL_QUADS} and {@code GL_TRIANGLE_FAN} are absent
	 * from every modern API and are drawn as indexed {@link #TOPOLOGY_TRIANGLES} instead -- see
	 * {@code render/QuadIndices}. Keeping them out of the key is what lets quads, which are nearly
	 * everything beta draws, share one pipeline with real triangles.
	 */
	public static final int TOPOLOGY_TRIANGLES = 0;
	public static final int TOPOLOGY_LINES = 1;
	public static final int TOPOLOGY_POINTS = 2;
	public static final int TOPOLOGY_TRIANGLE_STRIP = 3;
	public static final int TOPOLOGY_LINE_STRIP = 4;

	private PipelineKey() {
	}

	/**
	 * @param blendSrc   GL blend source factor, already mapped to a small dense index
	 * @param blendDst   GL blend destination factor, likewise
	 * @param depthFunc  GL depth function as a dense index
	 * @param depthWrite glDepthMask
	 * @param depthTest  GL_DEPTH_TEST
	 * @param blend      GL_BLEND
	 * @param cull       one of CULL_*
	 * @param topology   one of TOPOLOGY_*
	 * @param program    one of PROGRAM_*
	 */
	public static long of(int blendSrc, int blendDst, int depthFunc, boolean depthWrite,
			boolean depthTest, boolean blend, int cull, int topology, int program) {
		return of(blendSrc, blendDst, depthFunc, depthWrite, depthTest, blend, cull, topology,
			program, 0, 0);
	}

	/**
	 * @param offsetUnits glPolygonOffset's units, rounded; 0 when GL_POLYGON_OFFSET_FILL is off
	 * @param offsetSlope glPolygonOffset's factor, rounded; 0 when GL_POLYGON_OFFSET_FILL is off
	 */
	public static long of(int blendSrc, int blendDst, int depthFunc, boolean depthWrite,
			boolean depthTest, boolean blend, int cull, int topology, int program,
			int offsetUnits, int offsetSlope) {
		long key = 0L;
		key |= mask(blendSrc, BLEND_SRC_BITS) << BLEND_SRC_SHIFT;
		key |= mask(blendDst, BLEND_DST_BITS) << BLEND_DST_SHIFT;
		key |= mask(depthFunc, DEPTH_FUNC_BITS) << DEPTH_FUNC_SHIFT;
		key |= mask(cull, CULL_BITS) << CULL_SHIFT;
		key |= mask(topology, TOPOLOGY_BITS) << TOPOLOGY_SHIFT;
		key |= mask(program, PROGRAM_BITS) << PROGRAM_SHIFT;
		key |= (depthWrite ? 1L : 0L) << DEPTH_WRITE_SHIFT;
		key |= (depthTest ? 1L : 0L) << DEPTH_TEST_SHIFT;
		key |= (blend ? 1L : 0L) << BLEND_SHIFT;
		// Depth bias is PIPELINE state in WebGPU, not a call like glPolygonOffset, so it has to be
		// part of the key or two draws that differ only by offset would share one pipeline.
		key |= mask(offsetUnits, OFFSET_BITS) << OFFSET_UNITS_SHIFT;
		key |= mask(offsetSlope, OFFSET_BITS) << OFFSET_SLOPE_SHIFT;
		return key;
	}

	// Field positions, derived from the widths rather than written out.
	//
	// They used to be spelled as literals in the accessors while `of` walked a running shift, so the
	// two descriptions of the layout could disagree -- and widening PROGRAM_BITS is exactly the edit
	// that makes them disagree silently: every accessor above the program field would read the
	// neighbouring field's bits, and a key would decode as a different pipeline rather than fail.
	private static final int BLEND_SRC_SHIFT = 0;
	private static final int BLEND_DST_SHIFT = BLEND_SRC_SHIFT + BLEND_SRC_BITS;
	private static final int DEPTH_FUNC_SHIFT = BLEND_DST_SHIFT + BLEND_DST_BITS;
	private static final int CULL_SHIFT = DEPTH_FUNC_SHIFT + DEPTH_FUNC_BITS;
	private static final int TOPOLOGY_SHIFT = CULL_SHIFT + CULL_BITS;
	private static final int PROGRAM_SHIFT = TOPOLOGY_SHIFT + TOPOLOGY_BITS;
	private static final int DEPTH_WRITE_SHIFT = PROGRAM_SHIFT + PROGRAM_BITS;
	private static final int DEPTH_TEST_SHIFT = DEPTH_WRITE_SHIFT + 1;
	private static final int BLEND_SHIFT = DEPTH_TEST_SHIFT + 1;
	private static final int OFFSET_UNITS_SHIFT = BLEND_SHIFT + 1;
	private static final int OFFSET_SLOPE_SHIFT = OFFSET_UNITS_SHIFT + OFFSET_BITS;

	/** Sign-extends a field packed by {@link #mask}. */
	private static int signed(long key, int shift, int bits) {
		int value = (int) (key >>> shift & (1L << bits) - 1L);
		int signBit = 1 << bits - 1;
		return (value & signBit) != 0 ? value - (1 << bits) : value;
	}

	private static long mask(int value, int bits) {
		return value & (1L << bits) - 1L;
	}

	private static int field(long key, int shift, int bits) {
		return (int) (key >>> shift & (1L << bits) - 1L);
	}

	public static int blendSrc(long key) {
		return field(key, BLEND_SRC_SHIFT, BLEND_SRC_BITS);
	}

	public static int blendDst(long key) {
		return field(key, BLEND_DST_SHIFT, BLEND_DST_BITS);
	}

	public static int depthFunc(long key) {
		return field(key, DEPTH_FUNC_SHIFT, DEPTH_FUNC_BITS);
	}

	public static int cull(long key) {
		return field(key, CULL_SHIFT, CULL_BITS);
	}

	public static int topology(long key) {
		return field(key, TOPOLOGY_SHIFT, TOPOLOGY_BITS);
	}

	public static int program(long key) {
		return field(key, PROGRAM_SHIFT, PROGRAM_BITS);
	}

	/** The same key with a different program -- what a shadow or deferred replay needs. */
	public static long withProgram(long key, int program) {
		long cleared = key & ~(((1L << PROGRAM_BITS) - 1L) << PROGRAM_SHIFT);
		return cleared | mask(program, PROGRAM_BITS) << PROGRAM_SHIFT;
	}

	/**
	 * The key a batch is re-drawn under when it is replayed into a shadow map.
	 *
	 * <p>Keeps only what describes the GEOMETRY -- topology, and the vertex layout implied by the
	 * program -- and forces everything about how it lands in the framebuffer:
	 *
	 * <ul>
	 * <li>Depth test and write ON with {@code LESS_EQUAL}. A batch captured with depth writes off
	 *     (any translucent draw) would contribute nothing to a depth-only pass, so its caster would
	 *     silently vanish from the map.</li>
	 * <li>Blending OFF. There is no colour attachment to blend into, and a blend state on a
	 *     depth-only pipeline is a validation error rather than a no-op.</li>
	 * <li>Culling OFF. The light sees the faces the camera culls; a shadow map built with the
	 *     camera's back-face culling is hollow, and the light leaks through every wall.</li>
	 * <li>No depth bias. The offset that lifts the block-breaking overlay off its face has nothing
	 *     to do with the shadow map, and a pack's own bias belongs in its shader where it can be
	 *     normal-aware.</li>
	 * </ul>
	 *
	 * <p>Collapsing all of that is also what keeps the shadow cache small: the whole world's worth of
	 * captured states reduces to one pipeline per topology.
	 */
	public static long forShadow(long key, int program) {
		return of(GL_BLEND_ZERO_DENSE, GL_BLEND_ZERO_DENSE, DEPTH_LEQUAL_DENSE, true, true, false,
			CULL_NONE, topology(key), program);
	}

	// Spelled out here rather than referenced from GlShim, which depends on this class.
	private static final int GL_BLEND_ZERO_DENSE = 0;
	private static final int DEPTH_LEQUAL_DENSE = 3;

	public static boolean depthWrite(long key) {
		return (key >>> DEPTH_WRITE_SHIFT & 1L) != 0L;
	}

	public static boolean depthTest(long key) {
		return (key >>> DEPTH_TEST_SHIFT & 1L) != 0L;
	}

	public static boolean blend(long key) {
		return (key >>> BLEND_SHIFT & 1L) != 0L;
	}

	public static int offsetUnits(long key) {
		return signed(key, OFFSET_UNITS_SHIFT, OFFSET_BITS);
	}

	public static int offsetSlope(long key) {
		return signed(key, OFFSET_SLOPE_SHIFT, OFFSET_BITS);
	}

	/** {@code java com.periut.retrodragon.shim.PipelineKey} */
	public static void main(String[] args) {
		// Every field round-trips, and none of them bleed into a neighbour.
		long key = of(9, 10, 3, true, true, true, CULL_BACK, TOPOLOGY_LINE_STRIP, 5);
		check(blendSrc(key) == 9, "blendSrc");
		check(blendDst(key) == 10, "blendDst");
		check(depthFunc(key) == 3, "depthFunc");
		check(cull(key) == CULL_BACK, "cull");
		check(topology(key) == TOPOLOGY_LINE_STRIP, "topology");
		check(program(key) == 5, "program");
		check(depthWrite(key), "depthWrite");
		check(depthTest(key), "depthTest");
		check(blend(key), "blend");

		// Booleans are independent -- a packing slip would couple them.
		long none = of(0, 0, 0, false, false, false, CULL_NONE, TOPOLOGY_TRIANGLES, 0);
		check(none == 0L, "all-zero state must be key 0, got " + none);
		check(!depthWrite(none) && !depthTest(none) && !blend(none), "flags clear");

		long depthOnly = of(0, 0, 0, true, false, false, CULL_NONE, TOPOLOGY_TRIANGLES, 0);
		check(depthWrite(depthOnly) && !depthTest(depthOnly) && !blend(depthOnly), "depthWrite alone");

		// Distinct states must not collide -- that would silently render with the wrong pipeline.
		check(of(1, 0, 0, false, false, false, 0, 0, 0) != of(0, 1, 0, false, false, false, 0, 0, 0),
			"src/dst must not collide");
		check(of(0, 0, 0, true, false, false, 0, 0, 0) != of(0, 0, 0, false, true, false, 0, 0, 0),
			"depthWrite/depthTest must not collide");

		// Maximum field values still fit without overflow into the next field.
		long full = of(15, 15, 7, true, true, true, 3, 7, MAX_PROGRAMS - 1);
		check(blendSrc(full) == 15 && blendDst(full) == 15 && depthFunc(full) == 7
			&& cull(full) == 3 && topology(full) == 7 && program(full) == MAX_PROGRAMS - 1,
			"max values fit");

		// withProgram swaps ONLY the program. A shadow replay reuses each batch's key with the
		// caster program substituted, so anything else it disturbed would silently change the
		// blend or depth state the map is rendered with.
		long swapped = withProgram(key, MAX_PROGRAMS - 1);
		check(program(swapped) == MAX_PROGRAMS - 1, "withProgram sets the program");
		check(withProgram(swapped, program(key)) == key, "withProgram round-trips");
		check(blendSrc(swapped) == blendSrc(key) && blendDst(swapped) == blendDst(key)
			&& depthFunc(swapped) == depthFunc(key) && cull(swapped) == cull(key)
			&& topology(swapped) == topology(key) && depthWrite(swapped) == depthWrite(key)
			&& depthTest(swapped) == depthTest(key) && blend(swapped) == blend(key)
			&& offsetUnits(swapped) == offsetUnits(key) && offsetSlope(swapped) == offsetSlope(key),
			"withProgram must not disturb any other field");

		// glPolygonOffset. Signed, because beta's only use is negative -- an unsigned field would
		// read -3 back as 61 and push the block-breaking overlay AWAY from the viewer, making the
		// z-fighting worse rather than fixing it.
		long offset = of(0, 0, 0, false, false, false, 0, 0, 0, -3, -3);
		check(offsetUnits(offset) == -3, "offsetUnits round-trip, got " + offsetUnits(offset));
		check(offsetSlope(offset) == -3, "offsetSlope round-trip, got " + offsetSlope(offset));

		long offsetExtremes = of(0, 0, 0, false, false, false, 0, 0, 0, -32, 31);
		check(offsetUnits(offsetExtremes) == -32 && offsetSlope(offsetExtremes) == 31,
			"offset field extremes");

		// The two terms are independent, and neither disturbs the fields below them.
		check(of(0, 0, 0, false, false, false, 0, 0, 0, 1, 0)
			!= of(0, 0, 0, false, false, false, 0, 0, 0, 0, 1), "offset units/slope must not collide");
		long biased = of(9, 10, 3, true, true, true, CULL_BACK, TOPOLOGY_LINE_STRIP, 5, -3, -3);
		check(blendSrc(biased) == 9 && blendDst(biased) == 10 && depthFunc(biased) == 3
			&& cull(biased) == CULL_BACK && topology(biased) == TOPOLOGY_LINE_STRIP
			&& program(biased) == 5 && depthWrite(biased) && depthTest(biased) && blend(biased),
			"a depth offset must not corrupt any other field");

		// No offset is the common case and must stay key-identical to before, or every pipeline in
		// the game would be rebuilt under a new key.
		check(of(9, 10, 3, true, true, true, CULL_BACK, TOPOLOGY_LINE_STRIP, 5, 0, 0) == key,
			"a zero offset must not change the key");

		// Exhaustive: no two distinct combinations may produce the same key.
		java.util.HashSet<Long> seen = new java.util.HashSet<>();
		int count = 0;
		for (int src = 0; src < 16; src++) {
			for (int dst = 0; dst < 16; dst++) {
				for (int df = 0; df < 8; df++) {
					for (int c = 0; c < 4; c++) {
						for (int t = 0; t < 8; t++) {
							long k = of(src, dst, df, false, false, false, c, t, 0);
							check(seen.add(k), "collision at " + src + "/" + dst + "/" + df);
							count++;
						}
					}
				}
			}
		}
		System.out.println("PipelineKey self-check OK (" + count + " distinct states, no collisions)");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
