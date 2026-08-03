package com.periut.retrodragon.render;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.periut.retrodragon.RetroDragon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Correct mipmap generation for beta's block/item atlases.
 *
 * Beta's own generator is disabled for good reason -- it produces an incomplete chain and it bleeds
 * colour out of fully-transparent texels. terrain.png stores leftover key colour (magenta) under
 * alpha 0, so a plain average drags magenta into every mip that touches a transparent edge, which
 * shows up as coloured grid lines on water and dark fringes on foliage.
 *
 * Two corrections, both necessary:
 *
 *  - **Solidify** -- before averaging, every transparent texel takes the colour of a nearby opaque
 *    neighbour. Alpha stays 0, so nothing looks different at level 0; it only changes what the
 *    averaging pulls in.
 *  - **Coverage restore** (Castaño) -- averaging alpha moves the fraction of texels that survive the
 *    alpha test, so a cutout changes shape with distance: at beta's 0.1 threshold a box filter grows
 *    it, until glass and leaves read as solid blocks. After each downsample, each tile's alpha is
 *    remapped so the surviving fraction matches level 0 again. See {@link Mipmapper} for why the
 *    threshold that is counted against is half rather than beta's 0.1, and {@link SectionDrawer} for
 *    the other half of that -- the cutout pass tests against the same number.
 *
 * A 2x2 box filter cannot cross a tile boundary here: the grid PITCH stays even at every level the
 * chain is allowed to reach, so a 2x2 block always lies inside one tile. No per-tile special-casing
 * is needed for the filter itself. How large the sheet is and how many tiles it holds is
 * {@link BlockAtlas}'s business -- RetroAPI grows both, StationAPI removes the grid altogether.
 */
public final class TerrainMipmaps {
	/**
	 * Mip depth CEILING. What is actually built is {@link BlockAtlas#mipLevels()}, which also bounds
	 * it by the grid pitch.
	 *
	 * <p>Each level halves the tile, and beta's UVs are computed for level 0, so at deep levels a UV
	 * near a tile edge rounds into the neighbouring tile -- blue water averaged with a reddish
	 * neighbour reads as magenta grid lines. The shader's per-tile clamp is what makes any depth
	 * usable at all; without one, shallow levels would be the only lever.
	 */
	public static final int LEVELS = Math.max(1, Integer.getInteger("retroperf.mipLevels", 4));

	private static ByteBuffer upload = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());

	private TerrainMipmaps() {
	}

	/** Called with the atlas already bound, right after beta uploaded level 0. */
	public static void apply(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		// Only a square sheet of whole tiles, whatever size the content APIs grew it to. A stitched
		// atlas (StationAPI) and any non-grid sheet keep the vanilla single-level path, because the
		// box filter below is only valid inside a tile -- see BlockAtlas.
		if (!BlockAtlas.canMipmap(width, height)) {
			return;
		}
		int levels = BlockAtlas.mipLevels();
		if (levels < 1) {
			return;
		}

		int[] pixels = new int[width * height];
		image.getRGB(0, 0, width, height, pixels, 0, width);
		// The chain, including a re-solidified level 0. Level 0 has to be re-uploaded too: only the
		// mip CHAIN was built from the solidified colours before, so level 0 still held beta's
		// black-under-transparent texels and every filtered read of it -- the RGSS taps, and the
		// linear blend between level 0 and level 1 -- pulled that black into cutout edges. Alpha is
		// untouched, so nearest sampling and the alpha test are unaffected.
		int[][] chain = Mipmapper.build(pixels, width, height, BlockAtlas.filterPitch(), levels);
		int size = width;
		for (int level = 0; level < chain.length; level++) {
			uploadLevel(level, chain[level], size, size);
			size /= 2;
		}

		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, chain.length - 1);
		// Nearest magnification keeps beta's blocky look; mipmapped minification is what removes
		// the shimmer at distance.
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
	}

	private static void uploadLevel(int level, int[] pixels, int width, int height) {
		upload = Mipmapper.toRgbaBytes(pixels, upload);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, width, height, 0,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
	}

	public static void logOnce(int width, int height) {
		RetroDragon.detail("mipmapped atlas {}x{} ({} levels)", width, height, LEVELS);
	}
}
