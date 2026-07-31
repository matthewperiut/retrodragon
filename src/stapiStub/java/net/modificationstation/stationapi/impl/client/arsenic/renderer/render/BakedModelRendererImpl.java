package net.modificationstation.stationapi.impl.client.arsenic.renderer.render;

import net.minecraft.client.render.Tessellator;

/**
 * Compile stub. See the README in the root of this source set.
 *
 * <p>Only what {@code com.periut.retrodragon.mixin.stapi.BakedModelRendererMixin} needs: the
 * Tessellator field it redirects, and the one method that reads it on the terrain path.
 * {@code renderQuad}'s parameters are erased to {@code Object} because the mixin selects it by name
 * (there is exactly one) and never reads them.
 */
public class BakedModelRendererImpl {
	private final Tessellator tessellator = null;

	private void renderQuad(Object blockView, Object state, Object pos, Object quad, float[] box) {
		throw new AssertionError("compile stub");
	}
}
