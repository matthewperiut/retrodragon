package com.periut.retrodragon.window.mixin.stapi;

import com.periut.retrodragon.window.Starac;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Separate mixin with higher priority (runs after StationAPI's default 1000).
 * Cleans up ReloadScreenManager state after StationAPI's injection completes.
 */
@Mixin(value = Minecraft.class, priority = 1500)
public class MixinMinecraftStapiCleanup {

    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;renderLoadingScreen()V"
            )
    )
    private void starac_cleanupAfterStationAPI(CallbackInfo ci) {
        if (Starac.STAPI_NEEDS_ON_FINISH_CLEANUP) {
            Starac.STAPI_NEEDS_ON_FINISH_CLEANUP = false;
            ReloadScreenManagerAccessor.onFinish();
            System.out.println("[RetroDragon] Called onFinish() to clean up ReloadScreenManager");
        }
    }
}
