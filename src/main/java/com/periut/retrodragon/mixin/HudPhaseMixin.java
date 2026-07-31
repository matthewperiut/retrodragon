package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the HUD as GUI, at the HUD itself.
 *
 * <p>The obvious place for this would be "after world rendering returns", and that is where it was.
 * It does not work: beta does not draw the HUD inside the method that draws the world, so the phase
 * a shader extension saw for the entire HUD was whatever the last thing INSIDE the world had set --
 * a world phase.
 *
 * <p>The consequence is not subtle once a pack redirects the world into a linear target: the hotbar,
 * the hearts, every string of text and the inventory's player model all get rendered into it and
 * then re-gamma'd by the composite, so the whole interface comes out washed out while the world
 * beside it looks right.
 *
 * <p>Marking it at the draw call itself does not care where the call comes from, which is the only
 * property that makes it reliable.
 */
@Mixin(InGameHud.class)
public abstract class HudPhaseMixin {
	@Inject(method = "render(FZII)V", at = @At("HEAD"))
	private void retrodragon$hudBegin(float tickDelta, boolean hasScreen, int mouseX, int mouseY,
			CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.GUI);
	}
}
