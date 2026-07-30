package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.periut.retrodragon.retrocenter.RetroCenter;

import net.minecraft.client.Minecraft;

/**
 * b1.7.3's Minecraft.stop() calls System.exit -- which, for an in-process
 * child instance, would take the hub down with it. Children skip the exit:
 * their game loop returns normally, KnotClient.main unwinds, and the
 * launcher's finally-block hands the window back to the hub.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStopExitMixin {

	@Redirect(method = "stop()V", at = @At(value = "INVOKE", target = "Ljava/lang/System;exit(I)V"))
	private void retrocenter$noExitInChild(int code) {
		if (RetroCenter.isChildInstance()) {
			RetroCenter.log("suppressed System.exit(" + code + ") in child instance");
			com.periut.retrodragon.retrocenter.bridge.HubBridge.childGameEnded("Left the server");
			return;
		}
		System.exit(code);
	}
}
