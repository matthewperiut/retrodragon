package com.periut.retrodragon.window.mixin.lwjgl3;

import com.periut.retrodragon.window.lwjgl3compat.implementation.glfw.VirtualGLFWMouseImplementation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftDrawVirtualCursor {

	@Inject(method = "run", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V", remap = false))
	private void drawVirtualCursor(CallbackInfo ci) {
		VirtualGLFWMouseImplementation.render();
	}
}
