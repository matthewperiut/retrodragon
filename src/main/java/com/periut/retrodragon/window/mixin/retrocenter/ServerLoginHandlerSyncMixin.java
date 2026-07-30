package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.net.ServerProbe;

import net.minecraft.server.network.ServerLoginNetworkHandler;

/**
 * Freezes the vanilla "Took too long to log in" timeout while a client is
 * syncing mods over the pre-login probe -- downloads can take longer than
 * the ~30s login budget.
 */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginHandlerSyncMixin {

	@Shadow
	private int loginTicks;

	@Inject(method = "tick", at = @At("HEAD"))
	private void retrocenter$freezeLoginTimeout(CallbackInfo ci) {
		if (ServerProbe.isActive((ServerLoginNetworkHandler) (Object) this)) {
			this.loginTicks = 0;
		}
	}
}
