package com.periut.retrodragon.mixin;

import java.io.File;

import com.periut.retrodragon.RetroOptions;

import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries {@link RetroOptions}'s keys through beta's options.txt round trip.
 *
 * <p>Vanilla reads the file with an if-chain over the key, so it skips ours harmlessly, but
 * {@code save()} rewrites the file from its own fields -- anything it does not know about is gone.
 * Without this, editing {@code retrodragon.backend} would survive exactly until the first time the
 * player touched a video setting.
 *
 * <p>Both hooks are at TAIL, after vanilla's own reader and writer have finished and closed. That is
 * the whole compatibility story: vanilla's parse is not intercepted, its output is not rewritten
 * mid-flight, and a mod doing the same thing at the same point appends alongside rather than
 * fighting for the stream.
 *
 * <p>The values are also read at preLaunch straight off disk -- see
 * {@link RetroOptions#loadFromGameDir()} -- because the backend has to be chosen before the game
 * exists. This hook is what keeps the two in step afterwards, and matters for the case preLaunch
 * cannot see: RetroCenter points a child instance at the HUB's options.txt, so the file this reads
 * is not always the one in the game directory.
 */
@Mixin(GameOptions.class)
public class GameOptionsMixin {

	@Shadow private File file;

	@Inject(method = "load()V", at = @At("TAIL"))
	private void retrodragon$loadOptions(CallbackInfo ci) {
		RetroOptions.load(file);
	}

	@Inject(method = "save()V", at = @At("TAIL"))
	private void retrodragon$saveOptions(CallbackInfo ci) {
		RetroOptions.save(file);
	}
}
