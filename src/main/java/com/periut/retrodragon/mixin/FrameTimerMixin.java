package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.FrameTimer;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticks {@link FrameTimer} once per rendered frame.
 *
 * <p>Deliberately on the game's frame loop rather than on either backend's present call: the two
 * backends present through completely different code, and a comparison is worthless unless both
 * numbers are measured at the same point. This is that point.
 *
 * <p>Costs one static int comparison when {@code -Dretroperf.fps} is unset.
 */
@Mixin(GameRenderer.class)
public class FrameTimerMixin {
	@Inject(method = "onFrameUpdate(F)V", at = @At("HEAD"))
	private void retroperf$frame(float tickDelta, CallbackInfo ci) {
		FrameTimer.tick();
	}
}
