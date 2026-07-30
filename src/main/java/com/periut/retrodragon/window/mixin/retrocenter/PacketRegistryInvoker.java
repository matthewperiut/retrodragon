package com.periut.retrodragon.window.mixin.retrocenter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.packet.Packet;

/**
 * Exposes b1.7.3's package-private packet-table registration so the sync
 * carrier packet can be registered at a custom id.
 */
@Mixin(Packet.class)
public interface PacketRegistryInvoker {

	@Invoker("register")
	static void retrocenter$register(int id, boolean s2c, boolean c2s, Class<? extends Packet> clazz) {
		throw new AssertionError();
	}
}
