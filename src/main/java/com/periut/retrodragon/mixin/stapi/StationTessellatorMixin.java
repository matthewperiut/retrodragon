package com.periut.retrodragon.mixin.stapi;

import com.periut.retrodragon.render.QuadVertices;
import com.periut.retrodragon.render.RetroTessellator;

import net.minecraft.client.render.Tessellator;
import net.modificationstation.stationapi.impl.client.render.StationTessellatorImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Undoes StationAPI's hardcoded quad split when the backend expands quads from an index buffer.
 *
 * <p>{@code quad} builds a {@code BakedQuad}'s four vertices and then appends SIX of them --
 * v0,v1,v2,v0,v2,v3 -- with three {@code System.arraycopy} calls, unconditionally. That is the right
 * shape for beta, whose {@code vertex()} splits the same way and whose {@code draw()} therefore
 * submits a triangle list. It is the wrong shape here: {@link QuadVertices} turns beta's split off
 * under WebGPU because the backend expands 0,1,2,0,2,3 from a static index buffer instead, so six
 * vertices submitted as {@code GL_QUADS} come out as sheared triangles -- every baked model, held,
 * dropped, in the inventory and in a chunk.
 *
 * <p>Repaired here rather than by leaving beta's split on for the whole game, which was the previous
 * answer and cost every install with StationAPI the 1.44x that four-vertex quads buy. The two
 * duplicates are at slots 3 and 4 and the real fourth corner is at slot 5, so this is one copy back
 * over the first duplicate plus a rewind -- the work {@code quad} should not have done in the first
 * place, undone in six words.
 *
 * <p>At RETURN rather than by redirecting the copies: the copies are inline {@code arraycopy} calls
 * with computed offsets, and matching three of them by index is far more fragile than reading back
 * the block they just wrote.
 */
@Mixin(StationTessellatorImpl.class)
public class StationTessellatorMixin {
	@Shadow @Final private Tessellator self;

	@Inject(method = "quad", at = @At("RETURN"))
	private void retroperf$collapseSplit(CallbackInfo ci) {
		if (!QuadVertices.indexed()) {
			// GL backend: beta's split is live, and six vertices is what the batch is meant to hold.
			return;
		}
		((RetroTessellator) (Object) this.self).retroperf$collapseLastQuad();
	}
}
