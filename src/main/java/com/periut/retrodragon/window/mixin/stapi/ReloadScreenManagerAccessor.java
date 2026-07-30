package com.periut.retrodragon.window.mixin.stapi;

import net.modificationstation.stationapi.api.client.resource.ReloadScreenManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(value = ReloadScreenManager.class, remap = false)
public interface ReloadScreenManagerAccessor {

    @Accessor("LOCATIONS")
    static List<String> getLocations() {
        throw new IllegalStateException();
    }

    @Invoker("onFinish")
    static void onFinish() {
        throw new IllegalStateException();
    }
}
