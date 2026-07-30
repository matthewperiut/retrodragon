package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;

import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * The earliest disconnect signal: the moment the player clicks "Disconnect"
 * (button id 1) in a child instance, lock presenting -- the pause-menu frame
 * freezes and none of the teardown transition screens flicker through; the
 * next visible frame belongs to the hub.
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuChildMixin {

	@Inject(method = "buttonClicked", at = @At("HEAD"))
	private void retrocenter$freezeOnDisconnect(ButtonWidget button, CallbackInfo ci) {
		if (button.id == 1 && RetroCenter.isChildInstance()) {
			RetroCenter.log("child disconnect clicked -- freezing presentation");
			HubBridge.lockChildPresenting();
		}
	}
}
