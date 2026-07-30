package com.periut.retrodragon.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the client singleton, whose field is private.
 *
 * <p>Needed only to read the video options at draw time -- specifically the "Advanced OpenGL" toggle
 * that now selects whether the world is anti-aliased. Reading it live rather than caching it at
 * startup is what makes the option take effect the moment it is changed.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("INSTANCE")
	static Minecraft retroperf$instance() {
		throw new AssertionError("replaced by mixin");
	}
}
