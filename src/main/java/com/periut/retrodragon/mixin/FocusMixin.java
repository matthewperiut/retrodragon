package com.periut.retrodragon.mixin;

import com.periut.retrodragon.Config;

import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the game reacting to focus loss, for unattended benchmarking and screenshots.
 *
 * Beta does two things when the window is not focused, both of which ruin an automated run:
 * {@code GameRenderer.onFrameUpdate} opens the pause menu after 500 ms -- so any screenshot taken while another
 * window has focus contains the pause menu instead of the scene.
 *
 * The other half, {@code Minecraft.run}'s 10 ms unfocused sleep (which would cap a background
 * benchmark at ~100 fps), is already neutralised by starac's {@code noUnFullscreenOnUnfocus}
 * redirect returning true unconditionally. Preserve that behaviour when retrowindow replaces starac.
 *
 * Only the focus *queries at those two sites* are redirected -- real input focus is untouched.
 */
@Mixin(GameRenderer.class)
public class FocusMixin {

	@Redirect(
		method = "onFrameUpdate(F)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;isActive()Z"))
	private boolean retroperf$neverAutoPause() {
		return Config.NO_AUTO_PAUSE || Display.isActive();
	}
}
