package com.periut.retrodragon.retrocenter;

import com.periut.retrodragon.window.mixin.retrocenter.PacketRegistryInvoker;
import com.periut.retrodragon.retrocenter.net.RetroSyncPacket;

import net.fabricmc.api.ModInitializer;

/**
 * Plain fabric-loader entrypoint. Registers the pre-login carrier packet --
 * the probe transport that carries all of retrocenter's sync traffic.
 */
public final class RetroCenterMain implements ModInitializer {

	@Override
	public void onInitialize() {
		RetroCenter.captureLaunchArguments();

		// Pre-login sync carrier, both directions (id configurable via
		// -Dretrocenter.packetId; default 230).
		PacketRegistryInvoker.retrocenter$register(RetroSyncPacket.ID, true, true, RetroSyncPacket.class);
		RetroCenter.log("sync carrier packet registered at id " + RetroSyncPacket.ID);

		if (RetroCenter.isChildInstance()) {
			RetroCenter.log("running as child instance");
		}
	}
}
