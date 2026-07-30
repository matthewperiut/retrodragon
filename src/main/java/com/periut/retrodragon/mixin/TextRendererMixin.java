package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.TextBatcher;
import com.periut.retrodragon.render.WebGpuFrame;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.CharacterUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the text renderer's draw path under WebGPU.
 *
 * <p>Every text-drawing method in beta funnels into this one overload -- {@code draw(String,int,int,int)}
 * and {@code drawWithShadow} both call it, the latter twice -- so intercepting here covers all text
 * in the game, including anything a mod draws through the same renderer.
 *
 * <p>The replacement is not merely a translation. Beta renders a string by calling
 * {@code glCallLists} over one display list per character, where each list draws a quad AND advances
 * the modelview. Under any backend without display lists that is a draw call per character; here it
 * becomes a single batch per string, which then merges with its neighbours because the position is
 * baked into the vertices rather than pushed through the matrix stack. See
 * {@link TextBatcher} for why the advance makes the display-list route unworkable rather than merely
 * slow.
 */
@Mixin(TextRenderer.class)
public class TextRendererMixin {
	@Shadow private int[] characterWidths;
	@Shadow public int boundTexture;

	@Inject(method = "draw(Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$draw(String text, int x, int y, int color, boolean shadow,
			CallbackInfo ci) {
		if (!WebGpuFrame.active()) {
			return;
		}
		TextBatcher.draw(characterWidths, boundTexture, CharacterUtils.VALID_CHARACTERS,
			text, x, y, color, shadow);
		ci.cancel();
	}
}
