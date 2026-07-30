package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.AntiAliasing;
import com.periut.retrodragon.render.PostProcess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the frame in the multisample framebuffer.
 *
 * The whole frame is wrapped rather than just the world. The GUI is axis-aligned quads at integer
 * scale, so its edges land on sample boundaries and MSAA changes nothing about it -- wrapping
 * everything costs a little fill and avoids having to find a world/GUI seam that beta does not
 * actually expose.
 *
 * Skipped while there is no world: loading screens call {@code Display.update()} mid-frame and
 * present themselves, so they must draw straight to the window.
 */
@Mixin(GameRenderer.class)
public class AntiAliasMixin {
	@Shadow private Minecraft client;

	@Inject(method = "onFrameUpdate(F)V", at = @At("HEAD"))
	private void retroperf$beginAa(float tickDelta, CallbackInfo ci) {
		if (Boolean.getBoolean("retroperf.validateShim")) {
			com.periut.retrodragon.shim.ShimValidator.runOnce();
		}
		if (this.client.world != null) {
			// FXAA and MSAA are alternatives, never both: FXAA is the default-capable path, MSAA is
			// retained only for experimentation because it seams on beta's terrain.
			if (!PostProcess.begin(this.client.displayWidth, this.client.displayHeight)) {
				AntiAliasing.begin(this.client.displayWidth, this.client.displayHeight);
			}
		}
	}

	/**
	 * Resolve BEFORE the HUD is drawn, so anti-aliasing covers the world only.
	 *
	 * FXAA softens luminance edges, which is right for terrain silhouettes and wrong for beta's
	 * pixel-art font and GUI sprites -- running it over the whole frame visibly blurs text and the
	 * crosshair. The HUD and any open screen draw after this point, straight to the window.
	 */
	@Inject(
		method = "onFrameUpdate(F)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;render(FZII)V"))
	private void retroperf$resolveBeforeHud(float tickDelta, CallbackInfo ci) {
		AntiAliasing.resolve();
		PostProcess.resolve();
	}

	@Inject(method = "onFrameUpdate(F)V", at = @At("RETURN"))
	private void retroperf$resolveAa(float tickDelta, CallbackInfo ci) {
		// Safety net: if the HUD was skipped (no world, F1) the frame still has to be presented.
		AntiAliasing.resolve();
		PostProcess.resolve();
		com.periut.retrodragon.render.DrawStats.endFrame();
		com.periut.retrodragon.shim.ShimTracker.checkFrame();
	}
}
