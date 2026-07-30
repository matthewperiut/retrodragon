package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.FogState;

import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Records beta's fog mode as it is set, so {@link FogState} can hand it to the terrain shader.
 *
 * A redirect rather than a query: the mod's rule is that it sets GL state and never reads it, and
 * observing the call is both cheaper and correct at the moment the value changes.
 */
@Mixin(GameRenderer.class)
public class FogModeMixin {

	@Redirect(
		method = "applyFog(IF)V",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glFogi(II)V"))
	private void retroperf$noteFogMode(int target, int value) {
		if (target == GL11.GL_FOG_MODE) {
			FogState.noteMode(value);
		}
		GL11.glFogi(target, value);
	}
}
