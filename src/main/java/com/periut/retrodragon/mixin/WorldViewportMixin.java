package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.PostProcess;

import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Points beta's own {@code glViewport} at the world target instead of the window, while a scaled
 * offscreen is bound.
 *
 * <p>beta sizes the viewport from {@code displayWidth}/{@code displayHeight}, which is the WINDOW.
 * That was always right because the world was always drawn at window size. Under render scale it is
 * not: the attachment is smaller, so a window-sized viewport renders the frame at full size into a
 * smaller target and everything outside the target's bounds is simply clipped -- the visible result
 * is the world showing only its bottom-left corner, blown up.
 *
 * <p>Both call sites are redirected because beta has two: {@code onFrameUpdate} sets the viewport for
 * the frame, and {@code renderFrame} sets it again after the world's projection is established.
 * Missing either leaves the wrong one in force for part of the pass.
 *
 * <p><b>Only while a scaled target is bound.</b> {@code PostProcess.isActive()} is false whenever the
 * offscreen is not in use, which covers the HUD, every screen, the loading screens that present
 * mid-frame, and the entire WebGPU backend (where {@code PostProcess} never runs at all and the
 * equivalent job is done by the draw-list replay). In all of those the original arguments pass
 * through untouched, so this costs one boolean read on the paths it does not apply to.
 *
 * <p>The x/y offsets are passed through rather than scaled. beta only ever calls this with 0,0, and
 * scaling an offset that is always zero would be inventing a behaviour to go wrong later.
 */
@Mixin(GameRenderer.class)
public class WorldViewportMixin {

	@Redirect(
		method = { "onFrameUpdate(F)V", "renderFrame(FJ)V" },
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"),
		require = 1)
	private void retrodragon$scaleWorldViewport(int x, int y, int width, int height) {
		int targetWidth = PostProcess.targetWidth();
		int targetHeight = PostProcess.targetHeight();
		if (targetWidth > 0 && targetHeight > 0) {
			GL11.glViewport(x, y, targetWidth, targetHeight);
			return;
		}
		GL11.glViewport(x, y, width, height);
	}
}
