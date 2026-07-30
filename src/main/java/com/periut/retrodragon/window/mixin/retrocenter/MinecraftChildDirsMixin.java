package com.periut.retrodragon.window.mixin.retrocenter;

import java.io.File;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.periut.retrodragon.retrocenter.bridge.ChildConfig;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;

import net.minecraft.client.Minecraft;

/**
 * Child instances share the player's settings with the hub: b1.7.3 keeps
 * video/sound settings, keybinds and the selected texture pack in
 * options.txt, and pack files in texturepacks/. Both are read (and written)
 * from the HUB's game dir -- one source of truth -- while everything else
 * (saves, stats, screenshots, mods) stays in the per-server profile dir.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftChildDirsMixin {

	@ModifyArg(
			method = "init",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/option/GameOptions;<init>(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V"),
			index = 1)
	private File retrocenter$optionsFromHub(File dir) {
		return retrocenter$hubDirOr(dir);
	}

	@ModifyArg(
			method = "init",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/resource/pack/TexturePacks;<init>(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V"),
			index = 1)
	private File retrocenter$texturePacksFromHub(File dir) {
		return retrocenter$hubDirOr(dir);
	}

	/**
	 * Sound EFFECTS come from &lt;runDir&gt;/resources/ (populated by the
	 * resource download thread); a fresh child profile has an empty one --
	 * music streams, but the effect SoundPool stays silent. Resources are
	 * user-global assets: share the hub's, like options and texture packs.
	 */
	@ModifyArg(
			method = "init",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/resource/ResourceDownloadThread;<init>(Ljava/io/File;Lnet/minecraft/client/Minecraft;)V"),
			index = 0)
	private File retrocenter$resourcesFromHub(File dir) {
		return retrocenter$hubDirOr(dir);
	}

	private static File retrocenter$hubDirOr(File dir) {
		ChildConfig config = HubBridge.childConfig();
		if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance() && config != null && config.hubGameDir != null) {
			return new File(config.hubGameDir);
		}
		return dir;
	}
}
