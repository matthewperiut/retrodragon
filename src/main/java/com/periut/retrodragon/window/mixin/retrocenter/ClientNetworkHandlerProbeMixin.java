package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.periut.retrodragon.retrocenter.net.ProbeFlow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.ClientNetworkHandler;
import net.minecraft.network.packet.handshake.HandshakePacket;

/**
 * Wires the pre-login probe into the very first connection attempt:
 * - remember the target of every outgoing connection,
 * - hold the login at the handshake while the probe runs on this same
 *   connection,
 * - translate a probe rejection (vanilla server kicking our carrier packet)
 *   into a clean re-connect without probing,
 * - drive the probe timeout from the connection pump.
 */
@Mixin(ClientNetworkHandler.class)
public abstract class ClientNetworkHandlerProbeMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void retrocenter$onConnect(Minecraft minecraft, String host, int port, CallbackInfo ci) {
		ProbeFlow.onConnectionStart((ClientNetworkHandler) (Object) this, host, port);
	}

	@Inject(method = "onHandshake", at = @At("HEAD"), cancellable = true)
	private void retrocenter$holdLoginForProbe(HandshakePacket packet, CallbackInfo ci) {
		if (ProbeFlow.holdLogin((ClientNetworkHandler) (Object) this, packet)) {
			ci.cancel();
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void retrocenter$probeTimeout(CallbackInfo ci) {
		ProbeFlow.clientTick((ClientNetworkHandler) (Object) this);
	}

	@Inject(method = "onDisconnected", at = @At("HEAD"), cancellable = true)
	private void retrocenter$probeRejected(String reason, Object[] args, CallbackInfo ci) {
		if (ProbeFlow.onDisconnected((ClientNetworkHandler) (Object) this, reason)) {
			ci.cancel();
		}
	}
}
