package net.modificationstation.stationapi.impl.client.render;

import net.minecraft.client.render.Tessellator;

/**
 * Compile stub. See the README in the root of this source set.
 *
 * <p>Only the two members {@code com.periut.retrodragon.mixin.stapi.StationTessellatorMixin} needs:
 * the Tessellator this writes into, and the {@code quad} it writes with. {@code quad}'s parameters
 * are erased to {@code Object} because the mixin selects it by name and never reads them -- keeping
 * the real descriptor here would drag in BakedQuad for nothing.
 */
public class StationTessellatorImpl {
	private final Tessellator self;

	public StationTessellatorImpl(Tessellator self) {
		this.self = self;
		throw new AssertionError("compile stub");
	}

	public void quad(Object quad, float red, float green, float blue,
			int light0, int light1, int light2, int light3,
			float brightness0, float brightness1, float brightness2, boolean shade) {
		throw new AssertionError("compile stub");
	}
}
