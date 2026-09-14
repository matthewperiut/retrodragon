package com.periut.retrodragon.mixin.stapi;

import com.periut.retrodragon.render.Capture;
import com.periut.retrodragon.render.MeshTessellator;
import com.periut.retrodragon.render.VertexSink;

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

	@Shadow
	private int colorF2I(float r, float g, float b) {
		throw new AssertionError("shadow");
	}

	/**
	 * Catches each vertex colour as a float, on its way to being packed into a byte.
	 *
	 * <p>{@code TerrainLight} recovers a vertex's light by dividing one meshing walk's colour by
	 * another's, and eight bits is not enough to divide with -- the quotient of two bytes is worth
	 * about as much as the smaller of them. Beta's own renderer hands its colours to
	 * {@code Tessellator.color(float, float, float)}, which is where they get caught there; Arsenic
	 * packs its own and {@code System.arraycopy}s the result straight into the buffer, so this is
	 * the last point at which the float exists.
	 *
	 * <p>Four calls per quad, in vertex order, in both branches of {@code renderQuad} -- the tinted
	 * one and the plain one. The sum of the three channels is what is kept, for the same reason as
	 * everywhere else: the light is the same scalar in all three, and a sum cannot be the channel a
	 * block happens to have no colour in.
	 *
	 * <p>Only inside a chunk build. Items, the GUI and the block-damage overlay reach
	 * {@code renderQuad} too, on the render thread with no capture, and push nothing.
	 */
	@Redirect(
		method = "renderQuad",
		at = @At(value = "INVOKE",
			target = "Lnet/modificationstation/stationapi/impl/client/arsenic/renderer/render/"
				+ "BakedModelRendererImpl;colorF2I(FFF)I"),
		require = 1)
	private int retrodragon$captureQuadColor(BakedModelRendererImpl self, float r, float g, float b) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.pushQuadCorner(r + g + b);
		}
		return colorF2I(r, g, b);
	}

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
