package net.modificationstation.stationapi.mixin.resourceloader.client;

import net.minecraft.client.util.Timer;

/** Compile stub. See the README in the root of this source set. */
public interface MinecraftAccessor {
	Timer getTimer();

	void invokeLogGlError(String message);
}
