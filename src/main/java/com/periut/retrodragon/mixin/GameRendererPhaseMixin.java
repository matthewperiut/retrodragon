package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;
import com.periut.retrodragon.render.WebGpuFrame;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame-level half of phase tracking: where the world begins and ends, and the two phases beta
 * draws from the game renderer rather than the world renderer.
 *
 * <p>Also the point a shader extension is told the frame's world state, in
 * {@link com.periut.retrodragon.api.ShaderExtension#onWorldFrame}. It has to be here and not at
 * replay time: this renderer records the whole frame and submits it at the buffer swap, and by then
 * beta's camera transform, fog and tick delta are gone.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPhaseMixin {
	/**
	 * Everything outside world rendering is GUI -- the title screen, the loading screen, a menu over
	 * a paused world. Set at the top of the frame so a screen that draws before any world does is
	 * classified before its first batch, not after.
	 */
	@Inject(method = "renderFrame(FJ)V", at = @At("HEAD"))
	private void retrodragon$frameBegin(float tickDelta, long nanoTime, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.GUI);
	}

	@Inject(method = "renderWorld(FI)V", at = @At("HEAD"))
	private void retrodragon$worldBegin(float tickDelta, int pass, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
		// Arms the per-frame notification; the extensions are told once beta's camera is loaded,
		// which has not happened yet at this point. See WebGpuFrame.beginWorldFrame.
		WebGpuFrame.beginWorldFrame();
	}

	@Inject(method = "renderWorld(FI)V", at = @At("RETURN"))
	private void retrodragon$worldEnd(float tickDelta, int pass, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.GUI);
	}

	/**
	 * The held item and the screen overlays beta draws in the same method.
	 *
	 * <p>Still a world phase: it is drawn with the world's depth buffer after a depth clear, and a
	 * shader pack lights the hand from the same sun as everything else.
	 */
	@Inject(method = "renderFirstPersonHand(FI)V", at = @At("HEAD"))
	private void retrodragon$handBegin(float tickDelta, int pass, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.HAND);
	}

	@Inject(method = "renderFirstPersonHand(FI)V", at = @At("RETURN"))
	private void retrodragon$handEnd(float tickDelta, int pass, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderRain()V", at = @At("HEAD"))
	private void retrodragon$rainBegin(CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WEATHER);
	}

	@Inject(method = "renderRain()V", at = @At("RETURN"))
	private void retrodragon$rainEnd(CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderSnow(F)V", at = @At("HEAD"))
	private void retrodragon$snowBegin(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WEATHER);
	}

	@Inject(method = "renderSnow(F)V", at = @At("RETURN"))
	private void retrodragon$snowEnd(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}
}
