package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;

import net.minecraft.client.Minecraft;

/**
 * Whether the world is drawn with texture anti-aliasing, and how much.
 *
 * <h2>Bound to the "Advanced OpenGL" video option</h2>
 *
 * b1.7.3 ships an <em>Advanced OpenGL</em> toggle whose original job -- hardware occlusion queries --
 * is gone under WebGPU: there is no {@code ARB_occlusion_query}, and RetroDragon's own section
 * visibility replaces what it did. Rather than leave a dead switch in the options screen, it now
 * selects between the two looks people actually want:
 *
 * <ul>
 *   <li><b>off</b> -- vanilla b1.7.3: one nearest tap, no mip chain, shimmer and all. Pixel-identical
 *       to how the game looked in 2011.</li>
 *   <li><b>on</b> -- mipmapped and rotated-grid supersampled terrain, the same treatment the GL
 *       backend applies: halves the shimmer that "aliasing" actually means in motion, and buys the
 *       sharpness back that mipmaps alone would cost.</li>
 * </ul>
 *
 * <p>It only ever affects the WORLD. The GUI, text and items keep a single nearest tap under both
 * settings, because softening 16x16 pixel art at 1:1 is never an improvement -- which is exactly the
 * complaint that motivated splitting the two.
 *
 * <p>Switching is free: these are uniform values, not pipeline state, so the option takes effect on
 * the next frame with no shader rebuild and no stutter.
 *
 * <p>Both treatments need a tile grid to be safe, so both are OFF on a stitched atlas whatever the
 * option says -- see {@link BlockAtlas}. Under StationAPI the switch therefore does nothing, which is
 * the honest outcome: there is no way to clamp a tap into a sprite whose bounds this code cannot see.
 */
public final class TerrainAppearance {
	private TerrainAppearance() {
	}

	/**
	 * The atlas's edge length in texels. 256 for vanilla, larger for an HD pack or once RetroAPI has
	 * grown it; the shader derives its per-mip texel inset from this, so a wrong value bleeds
	 * neighbouring tiles.
	 */
	public static float atlasTexels() {
		return BlockAtlas.texels();
	}

	/** The grid pitch in texels, or 0 when the atlas is stitched and has no grid. */
	public static float tileTexels() {
		return BlockAtlas.tileTexels();
	}

	/** True when the player has asked for the anti-aliased world. */
	public static boolean enabled() {
		Minecraft client = GameOptions.client();
		if (client == null || client.options == null) {
			// Before the options exist there is nothing to draw a world with anyway.
			return false;
		}
		return client.options.advancedOpengl;
	}

	/**
	 * How far the mip chain may be walked. 0 pins sampling to level 0, which is vanilla's look.
	 *
	 * <p>Also gated on {@link Config#MIPMAP}, because a chain that was never uploaded cannot be
	 * sampled -- reading level 3 of a single-level texture is undefined, and on some drivers it is
	 * flat white.
	 */
	public static float maxLod() {
		return enabled() && Config.MIPMAP ? BlockAtlas.mipLevels() : 0.0F;
	}

	/**
	 * 1 to enable rotated-grid supersampling in the terrain shader.
	 *
	 * <p>Gated on the grid as well: the four taps are only kept off the neighbouring texture by the
	 * per-tile clamp, so without a grid RGSS would smear one sprite into the next.
	 */
	public static float rgss() {
		return enabled() && Config.RGSS && BlockAtlas.uniformGrid() ? 1.0F : 0.0F;
	}
}
