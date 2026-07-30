package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;

/**
 * A child that got disconnected/kicked shuts down and carries the reason
 * back to the hub, which shows it on its own DisconnectedScreen.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenChildMixin extends Screen {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void retrocenter$captureReason(String title, String message, Object[] args, CallbackInfo ci) {
		if (RetroCenter.isChildInstance()) {
			HubBridge.lockChildPresenting();
			// Carry the RAW screen parameters (translation keys + args) so
			// the hub replays the exact vanilla DisconnectedScreen.
			String[] stringArgs = null;
			if (args != null) {
				stringArgs = new String[args.length];
				for (int i = 0; i < args.length; i++) {
					stringArgs[i] = String.valueOf(args[i]);
				}
			}
			HubBridge.noteChildDisconnect(title, message, stringArgs);
			RetroCenter.log("child disconnected: " + title + " / " + message);
		}
	}

	@Inject(method = "init()V", at = @At("HEAD"), cancellable = true)
	private void retrocenter$closeChildOnDisconnect(CallbackInfo ci) {
		if (RetroCenter.isChildInstance()) {
			ci.cancel();
			if (this.minecraft != null) {
				this.minecraft.scheduleStop();
			}
		}
	}
}
