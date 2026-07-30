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
 *   <li><b>Coverage rescale</b> (Castaño) -- averaging alpha shrinks the fraction of texels surviving
 *       the 0.5 alpha test, so leaves and grass thin out and vanish with distance. After each
 *       downsample, alpha is scaled until the surviving fraction matches level 0 again.</li>
 * </ul>
 *
 * <p>A 2x2 box cannot cross a tile boundary as long as the TILE stays at least two texels across at
 * every level, which is what bounds the chain -- see {@link BlockAtlas#mipLevels()}. The pitch is a
 * parameter rather than beta's 16 because RetroAPI grows the sheet around unchanged tiles, so the
 * grid there is 32 or 64 tiles per axis with the same 16-texel pitch.
 */
public final class Mipmapper {
	private static final float ALPHA_CUTOFF = 0.5F;

	private Mipmapper() {
	}

	/**
	 * @param argb       level 0, ARGB, row-major; MUTATED in place by the solidify pass
	 * @param tileTexels the grid pitch; the filter never averages across a multiple of it
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
		float baseCoverage = coverage(argb);

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
			rescaleCoverage(dst, baseCoverage);
			chain[level] = dst;
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

	private static float coverage(int[] pixels) {
		int above = 0;
		for (int pixel : pixels) {
			if ((pixel >>> 24) / 255.0F > ALPHA_CUTOFF) {
				above++;
			}
		}
		return above / (float) pixels.length;
	}

	private static void rescaleCoverage(int[] pixels, float target) {
		if (target <= 0.0F) {
			return;
		}
		float low = 0.0F;
		float high = 4.0F;
		float scale = 1.0F;
		for (int step = 0; step < 10; step++) {
			scale = (low + high) * 0.5F;
			int above = 0;
			for (int pixel : pixels) {
				if (Math.min(1.0F, (pixel >>> 24) / 255.0F * scale) > ALPHA_CUTOFF) {
					above++;
				}
			}
			if (above / (float) pixels.length < target) {
				low = scale;
			} else {
				high = scale;
			}
		}
		for (int i = 0; i < pixels.length; i++) {
			int alpha = Math.round(Math.min(255.0F, (pixels[i] >>> 24) * scale));
			pixels[i] = alpha << 24 | pixels[i] & 0x00FFFFFF;
		}
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
}
