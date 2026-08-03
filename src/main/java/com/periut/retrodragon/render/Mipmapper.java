package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builds a mip chain for beta's block/item atlases, on the CPU, backend-agnostically.
 *
 * <p>Extracted from {@link TerrainMipmaps} so the GL and WebGPU paths produce byte-identical mips.
 * They have to: any divergence here shows up as a difference in how distant terrain looks between
 * backends, which is exactly the kind of thing a screenshot comparison would blame on the renderer.
 *
 * <p>Two corrections make this more than a box filter, and both are necessary:
 *
 * <ul>
 *   <li><b>Solidify</b> -- beta's atlases store leftover key colour under alpha 0, so a plain average
 *       drags magenta into every mip touching a transparent edge. Before averaging, each transparent
 *       texel takes the colour of a nearby opaque neighbour; alpha stays 0, so level 0 looks
 *       unchanged and only what the filter can see is different.</li>
 *   <li><b>Coverage restore</b> (Castaño) -- averaging alpha moves the fraction of texels that clear
 *       the alpha test, so a cutout changes shape with distance. After each downsample, alpha is
 *       remapped until the surviving fraction matches level 0 again.</li>
 * </ul>
 *
 * <h2>Which way coverage drifts, and why it is measured PER TILE</h2>
 *
 * <p>Both halves of that second correction were wrong before, and they compounded:
 *
 * <ul>
 *   <li>Coverage was measured over the WHOLE SHEET. The atlas is mostly solid tiles and empty slots,
 *       neither of which changes under a box filter, so the sheet-wide fraction barely moves however
 *       far the cutouts drift -- the correction it computed was very nearly {@code x1.0} and did
 *       nothing. Coverage is a property of one texture, so it is now restored per tile, which is also
 *       what {@link AnimatedMipmaps} has always got by handing a single tile in.</li>
 *   <li>Coverage was counted the wrong side of the test. Averaging four texels of a hard-edged cutout
 *       DILATES it whenever the test threshold sits below the halfway mark: at beta's own
 *       {@code glAlphaFunc(GL_GREATER, 0.1)} a texel with one opaque corner out of four still clears
 *       it, so every level grew the cutout by a texel. Measured on vanilla terrain.png, glass went
 *       27.7% covered at level 0 to 57.8%, 93.8% and then solid -- a window you cannot see through
 *       from any distance. Leaves went 60.5% to 100%.</li>
 * </ul>
 *
 * <p>So the cut is at {@link #CUTOFF}, the halfway point, which is the only threshold a filtered
 * alpha can be tested against without a systematic bias in one direction or the other, and
 * {@link SectionDrawer} raises the cutout pass's alpha ref to match. Beta's 0.1 stands everywhere
 * else, including the translucent pass, which is what water and ice are drawn in.
 *
 * <p>A 2x2 box cannot cross a tile boundary as long as the TILE stays at least two texels across at
 * every level, which is what bounds the chain -- see {@link BlockAtlas#mipLevels()}. The pitch is a
 * parameter rather than beta's 16 because RetroAPI grows the sheet around unchanged tiles, so the
 * grid there is 32 or 64 tiles per axis with the same 16-texel pitch.
 */
public final class Mipmapper {
	/**
	 * The largest alpha that must NOT survive the alpha test, as a byte.
	 *
	 * <p>Half of 255, rounded down: a filtered texel survives when more than half of what it averaged
	 * was solid. {@code SectionDrawer.CUTOUT_ALPHA_REF} is the same number as the fraction GL compares
	 * against, and the two have to stay in step -- the whole point of the pass below is to fix where
	 * the cut falls, which is worth nothing if the test cuts somewhere else.
	 */
	static final int CUTOFF = 127;

	/**
	 * 4x4 ordered dither, used to split a run of equal alphas across the cut without clumping.
	 *
	 * <p>Ordered rather than random because the chain is rebuilt on every texture-pack switch and on
	 * every frame of an animated tile: noise would crawl, a fixed pattern does not.
	 */
	private static final int[] BAYER = {
		0, 8, 2, 10,
		12, 4, 14, 6,
		3, 11, 1, 9,
		15, 7, 13, 5,
	};

	private Mipmapper() {
	}

	/**
	 * @param argb       level 0, ARGB, row-major; MUTATED in place by the solidify pass
	 * @param tileTexels the grid pitch; the filter never averages across a multiple of it, and
	 *     coverage is restored within each one. Anything that does not divide the sheet is treated as
	 *     a single tile.
	 * @param levels     how many mip levels BELOW level 0 to generate
	 * @return {@code levels + 1} arrays, index 0 being the solidified level 0
	 */
	public static int[][] build(int[] argb, int width, int height, int tileTexels, int levels) {
		solidify(argb, width, height);

		int usable = 0;
		int pitch = tileTexels;
		while (usable < levels && pitch >= 2) {
			pitch /= 2;
			usable++;
		}

		int[][] chain = new int[usable + 1][];
		chain[0] = argb;

		boolean gridded = tileTexels >= 2 && width % tileTexels == 0 && height % tileTexels == 0;
		int tilePitch = gridded ? tileTexels : width;
		int tilesX = gridded ? width / tileTexels : 1;
		int tilesY = gridded ? height / tileTexels : 1;
		float[] targets = coverage(argb, width, tilePitch, tilesX, tilesY);
		int[] histogram = new int[256];
		int[] tieRanks = new int[BAYER.length];

		// The FILTER reads the box-averaged level, not the coverage-corrected one. Correcting alpha
		// pushes it towards 0 and 255 by design, and feeding that back in would let each level's
		// correction compound into the next; the box filter itself stays exactly the average of the
		// average, which is what the levels are supposed to be.
		int[] src = argb;
		int size = width;
		for (int level = 1; level <= usable; level++) {
			int next = size / 2;
			int[] dst = new int[next * next];
			for (int y = 0; y < next; y++) {
				for (int x = 0; x < next; x++) {
					dst[x + y * next] = average(
						src[x * 2 + y * 2 * size],
						src[x * 2 + 1 + y * 2 * size],
						src[x * 2 + (y * 2 + 1) * size],
						src[x * 2 + 1 + (y * 2 + 1) * size]);
				}
			}
			int[] corrected = dst.clone();
			restoreCoverage(corrected, next, tilePitch >> level, tilesX, tilesY, targets,
				histogram, tieRanks);
			chain[level] = corrected;
			src = dst;
			size = next;
		}
		return chain;
	}

	/** Gives transparent texels a sensible colour so averaging cannot drag key colour into a mip. */
	public static void solidify(int[] pixels, int width, int height) {
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int index = x + y * width;
				if (pixels[index] >>> 24 != 0) {
					continue;
				}
				int r = 0;
				int g = 0;
				int b = 0;
				int found = 0;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = x + dx;
						int ny = y + dy;
						if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
							continue;
						}
						int neighbour = pixels[nx + ny * width];
						if (neighbour >>> 24 == 0) {
							continue;
						}
						r += neighbour >> 16 & 0xFF;
						g += neighbour >> 8 & 0xFF;
						b += neighbour & 0xFF;
						found++;
					}
				}
				pixels[index] = found > 0
					? (r / found) << 16 | (g / found) << 8 | b / found
					: 0;
			}
		}
	}

	private static int average(int a, int b, int c, int d) {
		int alpha = ((a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24)) / 4;
		int red = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF)) / 4;
		int green = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF)) / 4;
		int blue = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) / 4;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	/** The fraction of each tile that clears the alpha test at level 0 -- what every level restores. */
	private static float[] coverage(int[] pixels, int width, int pitch, int tilesX, int tilesY) {
		float[] targets = new float[tilesX * tilesY];
		for (int ty = 0; ty < tilesY; ty++) {
			for (int tx = 0; tx < tilesX; tx++) {
				int above = 0;
				for (int y = ty * pitch; y < (ty + 1) * pitch; y++) {
					for (int x = tx * pitch; x < (tx + 1) * pitch; x++) {
						if (pixels[x + y * width] >>> 24 > CUTOFF) {
							above++;
						}
					}
				}
				targets[tx + ty * tilesX] = above / (float) (pitch * pitch);
			}
		}
		return targets;
	}

	/**
	 * Moves each tile's alpha so the same fraction of it survives the alpha test as at level 0.
	 *
	 * <p>Not a scale, which is how this is usually written. A scale that has to REMOVE coverage --
	 * which is the direction a box filter drifts, since averaging spreads a hard edge outwards -- has
	 * to divide every alpha in the tile down towards the threshold, so a solid texel ends up sitting a
	 * hair above the cut. That reads fine through a plain alpha test and falls apart under everything
	 * that filters alpha further: RGSS averages four taps, alpha-to-coverage turns alpha into a sample
	 * mask, and a "solid" texel at 0.51 loses half of itself to both.
	 *
	 * <p>Instead the tile's own alpha histogram gives the threshold {@code t} that already leaves the
	 * right number of texels standing, and alpha is remapped so {@code t} lands exactly on the cut:
	 * {@code [0, t]} onto {@code [0, CUTOFF]} and {@code (t, 255]} onto {@code (CUTOFF, 255]}. Both
	 * ends are fixed points, so a solid texel stays 255, an empty one stays 0, and only the texels
	 * that were ambiguous move. When the box filter already put the cut in the right place the map is
	 * the identity, which is the common case -- a solid tile is never touched at all.
	 */
	private static void restoreCoverage(int[] pixels, int width, int pitch, int tilesX, int tilesY,
			float[] targets, int[] histogram, int[] tieRanks) {
		if (pitch < 1) {
			return;
		}
		int texels = pitch * pitch;
		for (int ty = 0; ty < tilesY; ty++) {
			for (int tx = 0; tx < tilesX; tx++) {
				int want = Math.round(targets[tx + ty * tilesX] * texels);
				java.util.Arrays.fill(histogram, 0);
				for (int y = ty * pitch; y < (ty + 1) * pitch; y++) {
					for (int x = tx * pitch; x < (tx + 1) * pitch; x++) {
						histogram[pixels[x + y * width] >>> 24]++;
					}
				}
				// The largest threshold that still leaves `want` texels above it. want == 0 keeps 255,
				// which discards the whole tile -- correct, and what a cutout smaller than one texel
				// has to do. Bottoming out at 0 means the tile has fewer non-transparent texels than
				// level 0 had surviving ones, and every one of them is kept.
				int threshold = 255;
				int surviving = 0;
				while (threshold > 0 && surviving < want) {
					surviving += histogram[threshold];
					threshold--;
				}
				// Averaging four texels of a hard-edged cutout leaves very few distinct alphas, so a
				// whole RUN of them crosses the threshold together and no threshold lands on `want`.
				// Sugar cane at level 1 is the extreme: one step of the histogram is the difference
				// between 14% of the tile and all of it, against a target of 55%. So the run that
				// straddles the cut is split by an ordered dither -- exactly as many of its texels are
				// demoted as it takes to hit `want`, spread evenly rather than in a clump. Coverage
				// then matches level 0 exactly at every level, for every tile.
				int tie = threshold < 255 ? threshold + 1 : 256;
				int demote = tie < 256 ? Math.min(surviving - want, histogram[tie]) : 0;
				int rankCut = 16;
				int partial = 0;
				if (demote > 0) {
					java.util.Arrays.fill(tieRanks, 0);
					for (int y = ty * pitch; y < (ty + 1) * pitch; y++) {
						for (int x = tx * pitch; x < (tx + 1) * pitch; x++) {
							if (pixels[x + y * width] >>> 24 == tie) {
								tieRanks[BAYER[(x & 3) + (y & 3) * 4]]++;
							}
						}
					}
					// Highest dither rank goes first, so what survives is the pattern's densest part.
					int taken = 0;
					rankCut = 15;
					while (rankCut > 0 && taken + tieRanks[rankCut] <= demote) {
						taken += tieRanks[rankCut--];
					}
					partial = demote - taken;
				}
				if (threshold == CUTOFF && demote == 0) {
					continue;
				}
				for (int y = ty * pitch; y < (ty + 1) * pitch; y++) {
					for (int x = tx * pitch; x < (tx + 1) * pitch; x++) {
						int index = x + y * width;
						int alpha = pixels[index] >>> 24;
						if (alpha == tie && demote > 0) {
							int rank = BAYER[(x & 3) + (y & 3) * 4];
							if (rank > rankCut || (rank == rankCut && partial-- > 0)) {
								// One below the cut: the run is half-covered either way, so this is the
								// smallest move that changes which side of the test it falls on.
								pixels[index] = CUTOFF << 24 | pixels[index] & 0x00FFFFFF;
								continue;
							}
						}
						pixels[index] = remap(alpha, threshold) << 24 | pixels[index] & 0x00FFFFFF;
					}
				}
			}
		}
	}

	/** Alpha through the piecewise-linear map that puts {@code threshold} on the alpha test's cut. */
	private static int remap(int alpha, int threshold) {
		if (alpha <= threshold) {
			return threshold == 0 ? 0 : alpha * CUTOFF / threshold;
		}
		int span = 254 - threshold;
		return span < 1 ? 255 : CUTOFF + 1 + (alpha - threshold - 1) * (254 - CUTOFF) / span;
	}

	/**
	 * ARGB ints to the R,G,B,A byte order both {@code GL_RGBA/GL_UNSIGNED_BYTE} and WebGPU's
	 * {@code RGBA8Unorm} expect in memory.
	 */
	public static ByteBuffer toRgbaBytes(int[] pixels, ByteBuffer reuse) {
		int bytes = pixels.length * 4;
		ByteBuffer out = reuse != null && reuse.capacity() >= bytes
			? reuse
			: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
		out.clear();
		for (int pixel : pixels) {
			out.put((byte) (pixel >> 16 & 0xFF));
			out.put((byte) (pixel >> 8 & 0xFF));
			out.put((byte) (pixel & 0xFF));
			out.put((byte) (pixel >>> 24));
		}
		out.flip();
		return out;
	}

	/**
	 * {@code ./gradlew mipmapperTest} -- the coverage property, without a game or an atlas.
	 *
	 * <p>What it pins down is the thing that was silently wrong before: a cutout has to occupy the
	 * same FRACTION of its tile at every level, and that fraction has to be counted at the threshold
	 * the alpha test actually uses. Both halves are invisible in a screenshot until a window three
	 * chunks away has gone solid.
	 */
	public static void main(String[] args) {
		// A 32x32 sheet of four 16-texel tiles: solid, a 25%-covered cutout, a checkerboard, empty.
		int size = 32;
		int pitch = 16;
		int[] sheet = new int[size * size];
		for (int y = 0; y < pitch; y++) {
			for (int x = 0; x < pitch; x++) {
				sheet[x + y * size] = 0xFF806040;                                    // solid
				sheet[pitch + x + y * size] = x < 4 ? 0xFF20A030 : 0x00FF00FF;       // a 25% stripe
				sheet[x + (pitch + y) * size] = (x + y & 1) == 0 ? 0xFFFFFFFF : 0x00FF00FF;
				sheet[pitch + x + (pitch + y) * size] = 0x00FF00FF;                  // empty
			}
		}

		int[] solidRow = new int[pitch];
		System.arraycopy(sheet, 0, solidRow, 0, pitch);
		int[][] chain = build(sheet, size, size, pitch, 4);
		expect(chain.length == 5, "a 16-texel tile carries four levels below level 0");

		// Level 0 keeps every alpha it arrived with -- solidify may only repaint what is invisible.
		for (int i = 0; i < pitch; i++) {
			expect(chain[0][i] == solidRow[i], "solidify repainted a texel that was not transparent");
		}

		float[] want = { 1.0F, 0.25F, 0.5F, 0.0F };
		for (int level = 0; level < chain.length; level++) {
			int levelSize = size >> level;
			int levelPitch = pitch >> level;
			for (int tile = 0; tile < 4; tile++) {
				float got = tileCoverage(chain[level], levelSize, (tile & 1) * levelPitch,
					(tile >> 1) * levelPitch, levelPitch);
				// One texel of slack: a 2x2 tile cannot represent 25%, and a 1x1 tile cannot represent
				// anything but 0 or 1. Nothing above that is quantisation -- it is drift.
				float slack = 1.0F / (levelPitch * levelPitch);
				expect(Math.abs(got - want[tile]) <= slack + 1e-4F, "tile " + tile + " at level "
					+ level + " covers " + got + ", not " + want[tile]);
			}
		}

		// The checkerboard is the case a threshold alone cannot solve: every texel of level 1 averages
		// to the same alpha, so without the dither the tile is either wholly solid or wholly gone.
		expect(tileCoverage(chain[1], 16, 0, 8, 8) > 0.4F && tileCoverage(chain[1], 16, 0, 8, 8) < 0.6F,
			"a checkerboard has to stay half covered, not collapse either way");

		// A solid tile is the one every translucent texture in beta's atlas looks like -- water and ice
		// are uniformly semi-transparent, and a correction that touched them would make distant water
		// change how much it blends.
		for (int level = 0; level < chain.length; level++) {
			int levelPitch = pitch >> level;
			for (int y = 0; y < levelPitch; y++) {
				for (int x = 0; x < levelPitch; x++) {
					expect((chain[level][x + y * (size >> level)] >>> 24) == 255,
						"the solid tile lost alpha at level " + level);
				}
			}
		}

		// And the empty tile stays empty: no colour, and no coverage, may cross a tile boundary.
		for (int level = 0; level < chain.length; level++) {
			int levelPitch = pitch >> level;
			int levelSize = size >> level;
			for (int y = 0; y < levelPitch; y++) {
				for (int x = 0; x < levelPitch; x++) {
					expect((chain[level][levelPitch + x + (levelPitch + y) * levelSize] >>> 24) == 0,
						"the empty tile picked up alpha from a neighbour at level " + level);
				}
			}
		}

		// The remap's fixed points, which are what keep "solid" at 255 rather than a hair above the cut.
		for (int threshold = 0; threshold < 255; threshold++) {
			expect(remap(255, threshold) == 255, "255 must stay solid at threshold " + threshold);
			expect(remap(0, threshold) == 0, "0 must stay empty at threshold " + threshold);
			expect(remap(threshold, threshold) <= CUTOFF && remap(threshold + 1, threshold) > CUTOFF,
				"the cut must land between " + threshold + " and " + (threshold + 1));
		}

		System.out.println("Mipmapper self-check OK: coverage held to one texel at every level");
	}

	private static float tileCoverage(int[] pixels, int width, int x0, int y0, int pitch) {
		int above = 0;
		for (int y = y0; y < y0 + pitch; y++) {
			for (int x = x0; x < x0 + pitch; x++) {
				if (pixels[x + y * width] >>> 24 > CUTOFF) {
					above++;
				}
			}
		}
		return above / (float) (pitch * pitch);
	}

	private static void expect(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
