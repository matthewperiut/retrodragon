package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;

import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks every screen as GUI: the title screen, the inventory, the pause menu, a mod's own screen.
 *
 * <p>Same reasoning as {@link HudPhaseMixin}. The inventory is the case that shows it most clearly,
 * because it draws a live player model -- so a screen misclassified as world puts an entity render
 * through a shader pack's linear target and the player in the inventory comes out washed out while
 * the same model looks correct in third person.
 */
@Mixin(Screen.class)
public abstract class ScreenPhaseMixin {
	@Inject(method = "render(IIF)V", at = @At("HEAD"))
	private void retrodragon$screenBegin(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.GUI);
	}
}
