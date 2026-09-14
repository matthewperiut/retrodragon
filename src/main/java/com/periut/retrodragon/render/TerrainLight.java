package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;

/**
 * Sky light as a uniform instead of a rebuild, which is how every renderer since 1.8 has done it.
 *
 * <h2>What beta does, and what it costs</h2>
 *
 * Beta has no lightmap. {@code BlockRenderManager} multiplies a block's colour by
 * {@code Dimension.lightLevelToLuminance[max(skyLight - ambientDarkness, blockLight)]} and writes
 * the product into the vertex, so the time of day is BAKED INTO THE MESH. When
 * {@code World.ambientDarkness} steps -- 12 values, walked twice a day cycle and again for every
 * rain and thunder transition -- {@code WorldRenderer.notifyAmbientDarknessChanged} marks every
 * sky-lit section dirty and the whole visible world is re-meshed. That is ~22 full rebuild storms
 * per day, each one a stutter, for a change that is one number.
 *
 * <p>This moves that number into the uniform block and gives the vertex what it needs to apply it:
 * the section is meshed once and the shader does the rest of the day.
 *
 * <h2>The two bytes</h2>
 *
 * <pre>
 *   light.x   the vertex's luminance at FULL DAYLIGHT (ambientDarkness == 0)
 *   light.y   the luminance BLOCK light alone gives it, which no time of day can take away
 * </pre>
 *
 * <p>The shader inverts x through {@link #level} to recover the sky level that produced it,
 * subtracts the uniform darkness, re-applies the curve, and takes the larger of that and y:
 *
 * <pre>lum(D) = max(luminance(level(x) - D), y)</pre>
 *
 * <p>At {@code D == 0} that is exactly {@code x} again, so noon is vanilla's own colour to within
 * the byte it is stored in. At night a sky-lit vertex falls off the curve exactly as beta's table
 * would, and a torch-lit one is pinned by {@code y} and does not move at all.
 *
 * <p>It is not bit-exact in one place, and only one: beta averages {@code max(sky, block)} over the
 * four corners of a smooth-lit vertex, while this averages sky and block separately and takes the
 * max afterwards. The two agree unless a single vertex has one corner the sun reaches and another a
 * torch reaches at a comparable level, where this is at most a few percent bright. Nothing else
 * about smooth lighting changes: both bytes are the renderer's own blend, so the gradients are the
 * ones it drew.
 *
 * <h2>How the two bytes are obtained</h2>
 *
 * Not by reimplementing lighting -- that is {@code renderBlockWithAmbientOcclusion}, 700 lines of
 * corner cases, and a content API's own renderer would not be covered by a copy of it. Every
 * terrain luminance in the game comes from ONE method, {@code BlockView.getNaturalBrightness}, so
 * the section is meshed up to three times with that method answering differently:
 *
 * <ul>
 * <li><b>{@link Pass#TINT}</b> returns {@code 1.0}. Every blend a renderer performs is a weighted
 *     average whose weights sum to one, so what comes out is the block's tint times its face shade
 *     with no light in it at all -- which is exactly what the vertex should now store.
 * <li><b>{@link Pass#DAYLIGHT}</b> returns the luminance the position has at
 *     {@code ambientDarkness == 0}. Dividing this walk's colour by the tint walk's gives
 *     {@code light.x}, whatever the renderer did in between.
 * <li><b>{@link Pass#BLOCK_FLOOR}</b> returns the luminance block light alone gives it, for
 *     {@code light.y}. SKIPPED for a section with no block light anywhere in it -- the common case
 *     outdoors -- where the floor is the table's own minimum and no walk is needed to learn that.
 * </ul>
 *
 * <p>Dividing one walk by another is what makes this renderer-agnostic. Whatever a renderer does
 * with a luminance -- average four of them under arbitrary weights, scale the result by a face
 * shade, lerp it toward one for an emissive quad -- it does the same thing in both walks, and
 * everything but the luminance itself cancels. There is no assumption here about HOW the blend is
 * built, which is what lets the same two bytes come out of beta's own renderer and out of
 * StationAPI's Arsenic, whose smooth lighting weights nothing like beta's.
 *
 * <p>The colours are captured as floats, before the Tessellator's {@code (int)(c * 255)}: the
 * quotient of two bytes is worth about as much as the smaller of them.
 *
 * <h2>Where it does not apply</h2>
 *
 * <ul>
 * <li><b>{@code -Dretroperf.terrain=false}.</b> That hands the world back to vanilla's display-list
 *     rebuild, which bakes the light in the way beta always did.
 * <li><b>{@code -Dretroperf.shader=false}, or a GL driver that cannot compile
 *     {@code terrain.vsh}.</b> Fixed function has nowhere to apply a lightmap. The first is known
 *     before anything meshes; the second is not, so {@link #fallBack()} turns the feature off and
 *     asks for the world to be built again -- see {@code TerrainShader}.
 * </ul>
 */
public final class TerrainLight {
	/** Beta's {@code Dimension.initBrightnessTable} floor: the luminance of light level 0. */
	public static final float MIN = 0.05F;

	/**
	 * {@code -Dretroperf.terrainLight=false} reverts to beta's baked light and its rebuilds.
	 *
	 * <p>Worth a switch rather than a deletion for the same reason the compact vertex has one: this
	 * changes what a lit block looks like at every time of day but noon, and a suspected difference
	 * wants a one-flag comparison against the game that produced it.
	 */
	public static final boolean ENABLED =
		!"false".equalsIgnoreCase(System.getProperty("retroperf.terrainLight"));

	private static volatile boolean resolved;
	private static volatile boolean reloadWanted;

	/** What a walk asks {@code getNaturalBrightness} for; see the class notes. */
	public enum Pass {
		/** Not meshing, or meshing normally: beta's own luminance at the current time of day. */
		NONE,
		/** {@code 1.0} for every lookup, so the vertex colour comes out as pure tint. */
		TINT,
		/** The luminance at {@code ambientDarkness == 0}. */
		DAYLIGHT,
		/** The luminance block light alone gives the position. */
		BLOCK_FLOOR
	}

	/**
	 * Per-thread, because sections mesh on workers and each is in its own pass.
	 *
	 * <p>An array rather than a {@code ThreadLocal<Pass>} so the mixin's read is one load off a
	 * cached reference: it sits in {@code getNaturalBrightness}, which a single section calls tens
	 * of thousands of times.
	 */
	private static final ThreadLocal<Pass[]> PASS =
		ThreadLocal.withInitial(() -> new Pass[] { Pass.NONE });

	private TerrainLight() {
	}

	/**
	 * Called once from {@link RenderBackend}, before anything meshes.
	 *
	 * @param webgpu false on the GL backend, which needs its own terrain program to apply a lightmap
	 */
	public static void select(boolean webgpu) {
		// Config.TERRAIN off means vanilla's display-list rebuild is what builds the world, and that
		// bakes the light in as beta always did. Applying a lightmap on top of it would darken the
		// world twice, and cancelling the ambient sweep would leave it stuck at whatever time of day
		// each section happened to be built.
		//
		// Config.SHADER off is the same story on GL: the fixed-function path has no vertex stage to
		// apply the pair in. The WebGPU backend has no such fallback -- its terrain pipeline IS the
		// shader -- so it does not care.
		resolved = ENABLED && Config.TERRAIN && (webgpu || Config.SHADER);
	}

	/** Test seam: fixes the decision outright, without a backend to ask. */
	static void selectForTest(boolean on) {
		resolved = on;
	}

	/** Whether terrain vertices carry a light pair and the shader applies the darkness uniform. */
	public static boolean enabled() {
		return resolved;
	}

	/**
	 * Gives up on the lightmap for the rest of the session and asks for the world to be rebuilt.
	 *
	 * <p>Called when the GL terrain program fails to compile or link, which is the one way the
	 * decision {@link #select} made can turn out to be wrong: fixed function has no vertex stage, so
	 * every section already meshed is carrying its light in a pair nothing will ever read, and would
	 * draw at its unlit tint -- a world with the lighting simply missing.
	 *
	 * <p>The rebuild is not done here. This is reached from inside the section draw loop, which is
	 * iterating the very meshes a reload would free. {@link #takeReload()} hands it to
	 * {@code WorldRendererMixin} to do before the next build instead.
	 *
	 * <p>Nothing has to change about the vertex LAYOUT: on GL the pair rides in beta's unread pad
	 * word, so a 32-byte vertex is a 32-byte vertex either way and the meshes already uploaded are
	 * the right shape. Only their colours are wrong, and rebuilding is what fixes those.
	 */
	public static void fallBack() {
		if (!resolved) {
			return;
		}
		resolved = false;
		reloadWanted = true;
	}

	/** True once, for the world renderer to act on; see {@link #fallBack()}. */
	public static boolean takeReload() {
		if (!reloadWanted) {
			return false;
		}
		reloadWanted = false;
		return true;
	}

	/** The pass this thread is meshing in. Read by {@code WorldRegionLightMixin}. */
	public static Pass pass() {
		return PASS.get()[0];
	}

	public static void pass(Pass pass) {
		PASS.get()[0] = pass;
	}

	/**
	 * Beta's brightness table as a continuous curve.
	 *
	 * <p>{@code Dimension.initBrightnessTable} fills 16 entries with
	 * {@code (1 - t) / (3t + 1) * 0.95 + 0.05} for {@code t = 1 - level / 15}; substituting
	 * {@code u = level / 15} gives this, which reproduces every one of those entries exactly and
	 * interpolates between them the way a smooth-lit vertex needs.
	 *
	 * @param u the light level divided by 15, clamped to 0..1 by the caller
	 */
	public static float luminance(float u) {
		return u / (4.0F - 3.0F * u) * 0.95F + MIN;
	}

	/** The inverse of {@link #luminance}: the {@code level / 15} that produces this luminance. */
	public static float level(float lum) {
		float y = lum - MIN;
		return 4.0F * y / (0.95F + 3.0F * y);
	}

	/**
	 * The two bytes for one vertex, from the walks' colours.
	 *
	 * <p>Each is the SUM of a colour's three channels rather than one of them. The light is the same
	 * scalar in all three, so any channel would do, and the sum is the one that cannot be the
	 * channel a block happens to have no colour in.
	 *
	 * <p>A vertex whose colour never went through a luminance -- a content API writing a constant,
	 * or an emissive quad a renderer pinned at full brightness -- divides to 1.0 in both, and comes
	 * out with a daylight of 1 and a floor to match: pinned, drawn at the brightness it was built
	 * with, untouched by the time of day. Which is what a constant colour asked for.
	 *
	 * @param tintSum     the tint walk's colour: the vertex with no light in it
	 * @param daylightSum the daylight walk's colour
	 * @param floorSum    the block-floor walk's colour, or {@code MIN * tintSum} when that walk was
	 *                    skipped because the section has no block light in it
	 * @return the two bytes, x in the low 8 bits and y in the next 8
	 */
	public static int bytes(float tintSum, float daylightSum, float floorSum) {
		if (!(tintSum > 0.0F)) {
			// A black tint carries no light either way, and dividing by it would invent one.
			return 0xFF | 0xFF << 8;
		}
		float daylight = daylightSum / tintSum;
		float floor = floorSum / tintSum;
		// The floor cannot exceed the daylight value -- it is the same lookup with the sky taken
		// away -- and letting it would pin a vertex brighter than noon. A renderer that scales the
		// two differently is the only way to get here, and clamping is the honest reading of that.
		if (!(floor <= daylight)) {
			floor = daylight;
		}
		return unorm8(daylight) | unorm8(floor) << 8;
	}

	private static int unorm8(float v) {
		int b = Math.round(v * 255.0F);
		return b < 0 ? 0 : Math.min(b, 255);
	}

	/**
	 * The light pair positioned in the vertex word it SHARES with the stitched-atlas sprite size.
	 *
	 * <p>The two live in one 4-byte word -- sprite in the first byte, the pair in the third and
	 * fourth -- because that is what holds the compact vertex at 24 bytes with both fields present,
	 * and the legacy one at beta's own 32 with both riding in its unread pad word. A vertex format
	 * reads the pair from the LOWER address first, and the word reaches memory in the machine's own
	 * byte order, so on a little-endian machine the pair is the high half and on a big-endian one it
	 * is the low half. Same dance as the packed colour, for the same reason.
	 */
	public static int word(float tintSum, float daylightSum, float floorSum) {
		return position(bytes(tintSum, daylightSum, floorSum));
	}

	/**
	 * {@link #bytes} moved into the half of the shared word the attribute reads it from.
	 *
	 * <p>The pair sits at the third and fourth BYTE of the word, x first. A little-endian int puts
	 * its low byte at the lowest address, so those are the top two bytes of the value; a big-endian
	 * one puts its high byte there, so they are the bottom two -- and in the other order.
	 */
	public static int position(int packed) {
		int x = packed & 0xFF;
		int y = packed >> 8 & 0xFF;
		if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
			return x << 16 | y << 24;
		}
		return x << 8 | y;
	}

	/** The bits of the shared word the light pair owns; the rest is the sprite size. */
	public static final int WORD_MASK = position(0xFFFF);

	/**
	 * Full daylight and a floor to match, which the shader reads as "do not touch this".
	 *
	 * <p>What a vertex gets when its light could not be recovered, and what {@link VertexSink}
	 * leaves in the slot before the walks fill it in. Chosen so that an unpatched vertex is the
	 * colour it was built with rather than black.
	 */
	public static final int UNLIT_WORD = position(0xFF | 0xFF << 8);

	/**
	 * Self-check: the curve is beta's table, and the division recovers the light through whatever a
	 * renderer does to it on the way.
	 *
	 * <p>The second half is the load-bearing claim -- that this works for a renderer nobody here has
	 * read -- so it is checked against the things renderers actually do between the luminance lookup
	 * and the vertex: blend several under arbitrary weights and scale the result by a face shade.
	 * Both are supposed to cancel exactly, and a change that breaks the cancellation is a world lit
	 * by whatever the tint happened to be.
	 */
	public static void main(String[] args) {
		int failures = 0;

		// The curve must reproduce beta's table exactly -- it IS the table, written as a function.
		for (int level = 0; level <= 15; level++) {
			float t = 1.0F - level / 15.0F;
			float beta = (1.0F - t) / (t * 3.0F + 1.0F) * 0.95F + 0.05F;
			float ours = luminance(level / 15.0F);
			if (Math.abs(beta - ours) > 1e-6) {
				System.out.println("FAIL: level " + level + " luminance " + ours + ", beta's table "
					+ beta);
				failures++;
			}
			float back = level(ours) * 15.0F;
			if (Math.abs(back - level) > 1e-3) {
				System.out.println("FAIL: luminance " + ours + " inverts to level " + back
					+ ", expected " + level);
				failures++;
			}
		}

		java.util.Random random = new java.util.Random(11);
		for (int trial = 0; trial < 20000 && failures <= 10; trial++) {
			// A vertex: some tint, some face shade, and a blend of up to four corner luminances
			// under weights that sum to one but are otherwise arbitrary.
			float tint = 0.05F + random.nextFloat() * 0.95F;
			float shade = new float[] { 0.5F, 0.6F, 0.8F, 1.0F }[random.nextInt(4)];
			int corners = 1 + random.nextInt(4);
			float[] weight = new float[corners];
			float total = 0.0F;
			for (int i = 0; i < corners; i++) {
				weight[i] = random.nextFloat() + 0.01F;
				total += weight[i];
			}
			float wantDay = 0.0F;
			float wantFloor = 0.0F;
			for (int i = 0; i < corners; i++) {
				weight[i] /= total;
				int block = random.nextInt(16);
				int sky = random.nextInt(16);
				wantDay += weight[i] * luminance(Math.max(sky, block) / 15.0F);
				wantFloor += weight[i] * luminance(block / 15.0F);
			}

			// What the walks hand back: the same everything, around a different luminance.
			float tintSum = 3.0F * tint * shade;
			float daySum = 3.0F * tint * shade * wantDay;
			float floorSum = 3.0F * tint * shade * wantFloor;

			int packed = bytes(tintSum, daySum, floorSum);
			float gotDay = (packed & 0xFF) / 255.0F;
			float gotFloor = (packed >> 8 & 0xFF) / 255.0F;
			if (Math.abs(gotDay - wantDay) > 1.0F / 255.0F) {
				System.out.println("FAIL: daylight " + gotDay + ", expected " + wantDay
					+ " -- the tint or the face shade did not cancel");
				failures++;
			}
			if (Math.abs(gotFloor - wantFloor) > 1.0F / 255.0F) {
				System.out.println("FAIL: block floor " + gotFloor + ", expected " + wantFloor);
				failures++;
			}
		}

		// A fully emissive quad -- a renderer pinning every corner at 1.0 -- must come out pinned
		// here too, not merely bright: it is the case where the division is 1/1 and the fallback in
		// bytes() is what decides, and getting it wrong makes glowing blocks go dark at night.
		int emissive = bytes(3.0F, 3.0F, 3.0F);
		if ((emissive & 0xFF) != 255 || (emissive >> 8 & 0xFF) != 255) {
			System.out.println("FAIL: a fully emissive quad packed to 0x"
				+ Integer.toHexString(emissive) + ", expected both bytes at full");
			failures++;
		}

		// The shader's arithmetic, against beta's own for a vertex with no block light: at every
		// ambient darkness the game can reach, the lit colour must be the table entry beta baked.
		for (int sky = 0; sky <= 15 && failures <= 10; sky++) {
			int packed = bytes(3.0F, 3.0F * luminance(sky / 15.0F), 3.0F * MIN);
			float day = (packed & 0xFF) / 255.0F;
			float floor = (packed >> 8 & 0xFF) / 255.0F;
			for (int darkness = 0; darkness <= 11; darkness++) {
				float u = Math.max(level(day) - darkness / 15.0F, 0.0F);
				float ours = Math.max(luminance(u), floor);
				float beta = luminance(Math.max(sky - darkness, 0) / 15.0F);
				if (Math.abs(ours - beta) > 2.0F / 255.0F) {
					System.out.println("FAIL: sky " + sky + " at darkness " + darkness + " gives "
						+ ours + ", beta gives " + beta);
					failures++;
				}
			}
		}

		// The two fields share a word, so neither may write over the other -- and each must land on
		// the byte its attribute reads, which is not the same bit position on both endiannesses.
		int pair = position(0xAB | 0xCD << 8);
		int shared = TerrainVertex.spriteBits(37) | pair;
		if ((shared & ~WORD_MASK) != TerrainVertex.spriteBits(37)
				|| (shared & WORD_MASK) != pair) {
			System.out.println("FAIL: the sprite size and the light pair overlap in their word");
			failures++;
		}
		java.nio.ByteBuffer word = java.nio.ByteBuffer
			.allocate(4).order(java.nio.ByteOrder.nativeOrder());
		word.putInt(0, shared);
		if ((word.get(0) & 0xFF) != 37 || (word.get(2) & 0xFF) != 0xAB
				|| (word.get(3) & 0xFF) != 0xCD) {
			System.out.println("FAIL: the shared word reads back as sprite " + (word.get(0) & 0xFF)
				+ ", light (" + (word.get(2) & 0xFF) + ", " + (word.get(3) & 0xFF)
				+ "), expected 37 and (171, 205) at the bytes the attributes declare");
			failures++;
		}

		if (failures > 0) {
			System.out.println("TerrainLight self-check FAILED (" + failures + ")");
			System.exit(1);
		}
		System.out.println("TerrainLight self-check OK: the curve is beta's table, the tint, the"
			+ " face shade and any blend cancel between walks, and sky light falls off exactly as"
			+ " beta's rebuild would have drawn it");
	}
}
