package com.periut.retrodragon.window.mixin.lwjgl3;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	@Accessor("INSTANCE")
	static Minecraft getInstance(){
		throw new IllegalStateException();
	}

	/**
	 * Seeds the static cache behind {@code Minecraft.getRunDirectory()}.
	 *
	 * <p>{@code getRunDirectory} fills this once, from vanilla's per-user location, and every later
	 * caller reads the cache. Setting it first is therefore the whole decision about where saves,
	 * options, texture packs and {@code resources/} live -- and {@code resources/} is where b1.7.3
	 * loads its sounds from, so getting it wrong is silent except that the game goes quiet.
	 *
	 * <p>A mixin accessor rather than reflection precisely because of the field name: this is
	 * remapped with the rest of the game, so it keeps working in a production (intermediary)
	 * install where a hardcoded {@code "runDirectoryCache"} would not resolve.
	 */
	@Accessor("runDirectoryCache")
	static void setRunDirectoryCache(java.io.File directory) {
		throw new IllegalStateException();
	}
}