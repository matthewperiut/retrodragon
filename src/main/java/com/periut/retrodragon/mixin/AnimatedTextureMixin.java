package com.periut.retrodragon.mixin;

import java.nio.ByteBuffer;

import com.periut.retrodragon.render.AnimatedMipmaps;
import com.periut.retrodragon.render.RenderBackend;

import net.minecraft.client.texture.TextureManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The GL half of the animated-texture mip fix.
 *
 * <p>Under WebGPU every animated upload already passes through {@code GlBridge.glTexSubImage2D},
 * which is the one seam beta's binders, RetroAPI's mcmeta animations and any mod all share -- so
 * that backend needs nothing here. Under GL there is no shim: {@code GL11} is only rewritten when
 * WebGPU owns the window, so those calls go straight to the driver and level 0 is all that ever
 * lands.
 *
 * <p>Hence a redirect on beta's own call sites. It covers beta's lava, water, fire and portal
 * binders, which is the visible case. It does NOT cover RetroAPI's animators on the GL backend:
 * those call {@code glTexSubImage2D} from RetroAPI's own tail hook on {@code tick}, which is a
 * separate method and so outside a redirect scoped to {@code tick}'s instructions.
 *
 * <p>Beta's four call sites in {@code tick} are the sprite upload, the replicate variant, the
 * anaglyph copy and its own dead per-level loop. All four are redirected: the filter in
 * {@link AnimatedMipmaps} decides what is actually a grid-aligned block-atlas tile, and everything
 * else falls through as a plain upload.
 */
@Mixin(TextureManager.class)
public class AnimatedTextureMixin {

	@Redirect(
		method = "tick()V",
		at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/opengl/GL11;glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V",
			remap = false))
	private void retrodragon$animatedTile(int target, int level, int x, int y, int width, int height,
			int format, int type, ByteBuffer pixels) {
		GL11.glTexSubImage2D(target, level, x, y, width, height, format, type, pixels);
		if (level != 0 || RenderBackend.isWebGpu()) {
			// WebGPU: the call above already went through GlBridge, which regenerates there. Doing it
			// again here would rebuild the same chain twice a tick.
			return;
		}
		// Beta uploads GL_RGBA/GL_UNSIGNED_BYTE here, which is the byte order AnimatedMipmaps reads.
		if (format == GL11.GL_RGBA && type == GL11.GL_UNSIGNED_BYTE) {
			AnimatedMipmaps.regenerate(retrodragon$boundTexture(), x, y, width, height, pixels);
		}
	}

	/**
	 * Which texture the tile landed in. Asked of GL rather than tracked, because {@code tick} binds
	 * the atlas itself (and rebinds for the anaglyph copy), so the only reliable answer is the live
	 * binding at the moment of the upload.
	 */
	private static int retrodragon$boundTexture() {
		return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
	}
}
