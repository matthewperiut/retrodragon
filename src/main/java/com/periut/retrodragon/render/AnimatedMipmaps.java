package com.periut.retrodragon.render;

import java.nio.ByteBuffer;

import com.periut.retrodragon.api.RetroSettings;

/**
 * Keeps an animated tile's mip levels in step with the frame that was just uploaded.
 *
 * <h2>The bug this closes</h2>
 *
 * {@link TerrainMipmaps} builds the corrected chain ONCE, when the atlas loads, from the static
 * terrain.png. Every animated tile -- beta's lava, water, fire and portal binders, RetroAPI's
 * mcmeta animations, anything a mod drives -- then rewrites <em>level 0 only</em>, every tick.
 * Levels 1..N for those tiles therefore keep their load-time pixels for the whole session.
 *
 * <p>Nothing looks wrong up close, because level 0 is correct. Past a few blocks the sampler moves
 * down the chain and reads a frozen frame: lava appears to stop animating and settles on the wrong
 * colour. RGSS widens the band it is visible over, because its four taps are one level SHARPER than
 * the isotropic choice and get averaged, so the stale level dominates sooner.
 *
 * <h2>Why not beta's own per-tick loop</h2>
 *
 * {@code TextureManager.tick} already contains one, and it is unreachable: {@code MIPMAP} is false
 * in the class's static initialiser and nothing in the game ever assigns it. Enabling it would be
 * worse than leaving it dead. It filters with {@code smoothBlend}, a plain average -- exactly the
 * naive filter {@link Mipmapper} exists to replace, with no solidify pass (so terrain.png's key
 * colour bleeds out from under transparent texels) and no coverage rescale. And its tile arithmetic
 * is {@code sprite % 16}, {@code sprite / 16}, {@code 16 >> level}, capped at four levels: literal
 * 16s standing for three different things against a 256-texel sheet, which writes to the wrong
 * offsets the moment a content API grows the atlas.
 *
 * <p>So beta's loop stays dead and this regenerates the levels with the SAME treatment the static
 * chain got, at the atlas's real pitch and depth.
 *
 * <h2>Cost</h2>
 *
 * A 16-texel tile's chain is 8x8 + 4x4 + 2x2 + 1x1 = 85 texels. Beta ticks at most a handful of
 * animated tiles at 20 Hz, so this is a few thousand texels a second -- against the whole-sheet
 * rebuild the old comment was rejecting, which would have been 87k texels per tile per tick.
 */
public final class AnimatedMipmaps {

	/** Reused across ticks; the regeneration is single-threaded through the texture upload path. */
	private static int[] argbScratch = new int[0];
	private static ByteBuffer uploadScratch;

	private AnimatedMipmaps() {
	}

	/**
	 * Regenerates levels 1..N for the region a level-0 upload just replaced.
	 *
	 * <p>Silently does nothing unless the region is a whole, grid-aligned tile of the block atlas.
	 * That test is what makes this safe to call from the shared upload seam: GUI sheets, the font
	 * sheet and partial uploads all fall out here rather than being mipmapped by accident, which is
	 * the mistake {@link TextureStore} documents having made once already.
	 *
	 * @param rgba level 0 for the region, R,G,B,A bytes from the buffer's current position. Read
	 *     only -- the caller's buffer is left at the position it arrived with.
	 */
	public static void regenerate(int name, int x, int y, int width, int height, ByteBuffer rgba) {
		if (!RetroSettings.isMipmap() || rgba == null || !isWholeTile(name, x, y, width, height)) {
			return;
		}
		int levels = BlockAtlas.mipLevels();
		if (levels < 1) {
			return;
		}

		int pixels = width * height;
		// EXACTLY the tile, not "at least". Mipmapper.coverage() measures the surviving alpha
		// fraction over the whole array, so a buffer left oversized by an earlier, larger tile would
		// fold stale trailing texels into the rescale. Tile size does not change in practice, so this
		// allocates once per session rather than per tick.
		if (argbScratch.length != pixels) {
			argbScratch = new int[pixels];
		}
		int base = rgba.position();
		if (rgba.remaining() < pixels * 4) {
			return;
		}
		for (int i = 0; i < pixels; i++) {
			int r = rgba.get(base + i * 4) & 0xFF;
			int g = rgba.get(base + i * 4 + 1) & 0xFF;
			int b = rgba.get(base + i * 4 + 2) & 0xFF;
			int a = rgba.get(base + i * 4 + 3) & 0xFF;
			argbScratch[i] = a << 24 | r << 16 | g << 8 | b;
		}

		// The tile is handed to Mipmapper as a one-tile sheet: pitch == width. The 2x2 box never
		// crosses a tile boundary either way, so the filter is identical to the whole-atlas build.
		// solidify and the coverage rescale now see one tile rather than the sheet, which for the
		// tiles that animate is moot -- lava, water, fire and portal are fully opaque, so solidify is
		// a no-op -- and for a transparent mcmeta animation is the better answer anyway: no colour
		// can be dragged in from a neighbouring sprite, which is what the shader's per-tile clamp
		// already assumes.
		// argbScratch is handed to build() directly even though solidify MUTATES it: it is refilled
		// from the caller's buffer on every call, and chain[0] -- the mutated level 0 -- is never
		// uploaded. A defensive copy here would be one allocation per animated tile per tick for
		// nothing.
		int[][] chain = Mipmapper.build(argbScratch, width, height, width, levels);

		int size = width;
		int levelX = x;
		int levelY = y;
		for (int level = 1; level < chain.length; level++) {
			size /= 2;
			levelX /= 2;
			levelY /= 2;
			if (size < 1) {
				break;
			}
			uploadScratch = Mipmapper.toRgbaBytes(chain[level], uploadScratch);
			// Through GL rather than through either backend's texture object: under WebGPU this
			// reaches TextureStore.update via GlBridge, under GL it reaches the driver, and the
			// caller does not have to know which one it is running on.
			org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, level,
				levelX, levelY, size, size,
				org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, uploadScratch);
		}
	}

	/**
	 * Whether this upload is one whole tile of the block atlas, on the grid.
	 *
	 * <p>Square, tile-sized and tile-aligned. Anything else -- a partial row, an unaligned offset, a
	 * sheet with no grid at all (StationAPI's stitched atlas, where {@code mipLevels()} is 0 anyway)
	 * -- is left alone, because the per-tile filter is only valid inside a tile.
	 */
	private static boolean isWholeTile(int name, int x, int y, int width, int height) {
		if (!BlockAtlas.isBlockAtlas(name)) {
			return false;
		}
		if (BlockAtlas.uniformGrid()) {
			int tile = BlockAtlas.tileTexels();
			return tile > 1 && width == tile && height == tile && x % tile == 0 && y % tile == 0;
		}
		// Stitched: the boundary is the SPRITE the upload lands in rather than a grid cell. Same
		// question either way -- does this sub-image cover exactly one whole texture -- and without it
		// an animated sprite would keep re-uploading level 0 and leave its mips frozen at whatever
		// frame the atlas was built with.
		BlockAtlas.SpriteGrid grid = BlockAtlas.spriteGrid();
		if (grid == null) {
			return false;
		}
		int size = grid.sizeAtTexel(x, y);
		return size > 1 && width == size && height == size && x % size == 0 && y % size == 0;
	}
}
