package com.periut.retrodragon.mixin.stapi;

import com.periut.retrodragon.render.Capture;
import com.periut.retrodragon.render.MeshTessellator;

import net.minecraft.client.render.Tessellator;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.BakedModelRendererImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Points Arsenic's terrain quads at the meshing thread's own Tessellator while a capture is active.
 *
 * <p>{@code renderQuad} is the whole terrain path: it hands each {@code BakedQuad} to
 * {@code Tessellator.quad}, which packs four vertices and {@code System.arraycopy}s them into that
 * Tessellator's {@code int[]}. It reads {@code this.tessellator} -- {@code Tessellator.INSTANCE},
 * captured in a final field when the renderer was built -- so without this every mesh worker writes
 * into the object the render thread is drawing with. See {@link MeshTessellator} for what that
 * costs.
 *
 * <p>Only while {@link Capture#sink()} is non-null, which is only inside a chunk build. Item, GUI
 * and block-damage rendering read the field through other methods and are untouched, so they keep
 * using {@code INSTANCE} on the render thread exactly as before.
 *
 * <p>Applied only when StationAPI is installed -- {@code GlPlugin.shouldApplyMixin} gates this
 * package. Selected by method NAME because there is exactly one {@code renderQuad}, which keeps the
 * compile stub free of StationAPI's block and geometry types.
 */
@Mixin(BakedModelRendererImpl.class)
public class BakedModelRendererMixin {
	@Shadow @Final private Tessellator tessellator;

	@Redirect(
		method = "renderQuad",
		at = @At(value = "FIELD",
			opcode = Opcodes.GETFIELD,
			target = "Lnet/modificationstation/stationapi/impl/client/arsenic/renderer/render/"
				+ "BakedModelRendererImpl;tessellator:Lnet/minecraft/client/render/Tessellator;"),
		require = 1)
	private Tessellator retroperf$meshTessellator(BakedModelRendererImpl self) {
		return Capture.sink() != null ? MeshTessellator.get() : this.tessellator;
	}
}
