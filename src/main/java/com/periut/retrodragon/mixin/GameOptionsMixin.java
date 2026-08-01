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

	@Shadow public int fpsLimit;

	@Shadow public boolean advancedOpengl;

	/**
	 * RetroDragon's starting values for two vanilla video settings.
	 *
	 * <p>At HEAD, so this is a DEFAULT rather than an override: vanilla's parser runs immediately
	 * after and writes whatever options.txt actually says, so a player who turns either of these off
	 * keeps it off. What it changes is the two cases where the file has nothing to say -- a fresh
	 * install with no options.txt at all (beta's {@code load()} returns early and every field keeps
	 * whatever it was initialized to), and an older or hand-edited file that is simply missing the
	 * key.
	 *
	 * <p><b>Advanced OpenGL</b> is the one that actually moves. Vanilla defaults it off, where it
	 * meant occlusion queries; here it is what {@code TerrainAppearance.enabled()} reads to decide
	 * whether the world gets mipmapping and RGSS, which is the better-looking half of this renderer
	 * and not something a new player should have to find in a menu.
	 *
	 * <p><b>Performance</b> is already Balanced in b1.7.3, so this line changes nothing today. It is
	 * here to make that explicit rather than inherited: Balanced is the setting the WebGPU backend is
	 * built around -- vsync through Fifo, one present per refresh -- and Max FPS is the one that goes
	 * looking for a Mailbox swapchain. Pinning it in the same place as the other default means a
	 * change to beta's field initializer cannot quietly move it.
	 */
	@Inject(method = "load()V", at = @At("HEAD"))
	private void retrodragon$applyDefaults(CallbackInfo ci) {
		fpsLimit = com.periut.retrodragon.render.FramePacing.BALANCED;
		advancedOpengl = true;
	}

	@Inject(method = "load()V", at = @At("TAIL"))
	private void retrodragon$loadOptions(CallbackInfo ci) {
		RetroOptions.load(file);
	}

	@Inject(method = "save()V", at = @At("TAIL"))
	private void retrodragon$saveOptions(CallbackInfo ci) {
		RetroOptions.save(file);
	}
}
