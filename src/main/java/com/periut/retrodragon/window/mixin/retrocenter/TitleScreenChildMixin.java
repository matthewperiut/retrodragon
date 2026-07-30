package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.RetroCenter;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * A child instance has no business at the title screen: it was spawned to
 * play on exactly one server, so landing here means the player left it.
 * Shut down gracefully -- the bridge gives the window back to the hub.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenChildMixin extends Screen {

	@Inject(method = "init()V", at = @At("HEAD"), cancellable = true)
	private void retrocenter$closeChildAtTitle(CallbackInfo ci) {
		if (RetroCenter.isChildInstance()) {
			RetroCenter.log("child reached title screen -- shutting down to return to hub");
			com.periut.retrodragon.retrocenter.bridge.HubBridge.lockChildPresenting();
			ci.cancel();
			if (this.minecraft != null) {
				this.minecraft.scheduleStop();
			}
		}
	}
}
