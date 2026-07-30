package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;

import net.minecraft.client.gui.screen.ConnectScreen;

/**
 * The moment a child instance reaches its "Logging in..." screen, lift the
 * present gate: the hub's frozen frame is replaced by the child's first
 * player-facing screen -- the Mojang splash and texture loading happened
 * invisibly in the back buffer.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenChildMixin {

	@Inject(method = "init()V", at = @At("RETURN"))
	private void retrocenter$liftPresentGate(CallbackInfo ci) {
		if (RetroCenter.isChildInstance()) {
			RetroCenter.log("child reached logging-in screen -- presenting to the window");
			HubBridge.enableChildPresenting();
		}
	}
}
