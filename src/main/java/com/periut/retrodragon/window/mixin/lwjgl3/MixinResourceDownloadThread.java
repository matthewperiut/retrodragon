package com.periut.retrodragon.window.mixin.lwjgl3;

import net.minecraft.client.resource.ResourceDownloadThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResourceDownloadThread.class)
public abstract class MixinResourceDownloadThread {

	@Shadow public abstract void reload();

	@Inject(method = "run", at = @At("HEAD"), cancellable = true)
	private void noResourceLoading(CallbackInfo ci){
		ci.cancel();
		reload();
	}
}