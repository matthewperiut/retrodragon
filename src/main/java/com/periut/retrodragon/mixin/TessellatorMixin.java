package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.Capture;
import com.periut.retrodragon.render.DrawStats;
import com.periut.retrodragon.render.QuadVertices;
import com.periut.retrodragon.render.VertexSink;
import com.periut.retrodragon.render.WebGpuFrame;
import com.periut.retrodragon.shim.ShimTracker;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes the Tessellator into a {@link VertexSink} while a capture is active.
 *
 * Only the primitive entry points are intercepted; every other overload in Tessellator funnels
 * into one of these (e.g. {@code vertex(x,y,z,u,v)} calls {@code texture} then {@code vertex},
 * {@code color(float,float,float)} ends at {@code color(int,int,int,int)}), so this covers all
 * ~495 call sites in BlockRenderManager.
 *
 * {@code start}/{@code draw} are swallowed during a capture: layer boundaries are decided by the
 * mesher, not by vanilla, and nested start/draw pairs must not split a section's geometry.
 */
@Mixin(Tessellator.class)
public class TessellatorMixin {

	// The Tessellator's own buffers and batch state. Shadowed rather than reconstructed because the
	// packed layout IS the thing being captured: 8 ints per vertex -- position 3f at 0, texture 2f at
	// 12, colour 4 bytes at 20, normal 3 bytes at 24 -- which is byte-for-byte what fixedfunc.wgsl
	// declares. Capturing a batch is a memcpy, not a conversion.
	/**
	 * beta's {@code convertQuadsToTriangles}. When set, {@code vertex()} expands every quad into
	 * SIX vertices (0,1,2, 0,2,3) as it records, and {@code draw()} submits {@code GL_TRIANGLES}
	 * while leaving {@code mode} reading 7. Trusting {@code mode} therefore applies the quad index
	 * pattern to data that is already a triangle list -- every quad then renders as one sheared
	 * triangle, which is precisely what it looked like.
	 */
	@Shadow private static boolean TRIANGLE_MODE;

	@Shadow private ByteBuffer byteBuffer;
	@Shadow private IntBuffer intBuffer;
	@Shadow private int[] buffer;
	@Shadow private int vertexCount;
	@Shadow private int bufferPosition;
	@Shadow private int mode;
	@Shadow private boolean hasColor;
	@Shadow private boolean hasTexture;
	@Shadow private boolean hasNormals;
	@Shadow private boolean drawing;
	@Shadow private int addedVertexCount;

	/**
	 * Gives the two vertices beta duplicates per quad the normal they were duplicated from.
	 *
	 * <p>{@code vertex()} expands a quad into 0,1,2, 0,2,3 by copying slots back over the new
	 * vertices -- position, texture and colour, but NOT slot 6, the packed normal. Those two vertices
	 * therefore keep whatever the buffer held at that index from an earlier batch.
	 *
	 * <p>Under GL a normal is current state, so every corner of a quad shares the one
	 * {@code glNormal3f} that preceded it. Here two of the six carry something else, and the
	 * per-vertex lighting term breaks across the diagonal the two triangles share -- a gradient with
	 * a hard crease down every face, worst on lit all-quad geometry like dropped items.
	 *
	 * <p>Repaired here rather than in {@code vertex()} because this is the last point before the
	 * batch is handed over, so it costs one pass over geometry that is about to be copied anyway,
	 * and only when the batch actually carries normals.
	 *
	 * <p>{@code VertexSink} has the same split for the display-list path and fixes it at the source.
	 */
	@org.spongepowered.asm.mixin.Unique
	private void retroperf$repairSplitNormals() {
		// Gated on the EFFECTIVE split, not on TRIANGLE_MODE. With the split redirected off the
		// buffer holds four vertices per quad, and walking it in groups of six would copy normals
		// between unrelated faces -- a repair that becomes the corruption it was written to fix.
		if (!QuadVertices.splitting(TRIANGLE_MODE) || !hasNormals
				|| mode != com.periut.retrodragon.render.Primitives.GL_QUADS) {
			return;
		}
		final int stride = 8;
		final int normal = 6;
		// Groups of six: v0,v1,v2 then the copies of v0 and v2, then v3.
		for (int base = 0; base + 6 * stride <= bufferPosition; base += 6 * stride) {
			buffer[base + 3 * stride + normal] = buffer[base + normal];
			buffer[base + 4 * stride + normal] = buffer[base + 2 * stride + normal];
		}
	}

	/**
	 * Turns off beta's CPU quad split for the whole game.
	 *
	 * <p>{@code vertex()} reads {@code TRIANGLE_MODE} once, and when it is set it re-emits v0 and v2
	 * on every fourth vertex so a quad occupies six slots. Redirecting that one read to
	 * {@link QuadVertices#splitting} leaves four, which is what the geometry actually contains -- the
	 * WebGPU backend expands them again at draw time out of a static index buffer it binds once, so
	 * the split is duplicated work that also costs 50% more vertices to store, upload and transform.
	 *
	 * <p>A {@code @Redirect} rather than clearing the field, because the field is set in the
	 * Tessellator's static initialiser and the class can load before the backend has been chosen.
	 * Redirecting the read is decided per call and cannot race the class load.
	 *
	 * <p>This is the single largest change to how much geometry the game moves, and it applies
	 * everywhere: terrain, entities, particles, items, text and every GUI element go through this
	 * method.
	 */
	@org.spongepowered.asm.mixin.injection.Redirect(
		method = "vertex(DDD)V",
		at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/render/Tessellator;TRIANGLE_MODE:Z",
			opcode = org.objectweb.asm.Opcodes.GETSTATIC),
		require = 1)
	private boolean retroperf$splitQuads() {
		return QuadVertices.splitting(TRIANGLE_MODE);
	}

	@Inject(method = "vertex(DDD)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$vertex(double x, double y, double z, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.vertex(x, y, z);
			ci.cancel();
		} else if (ShimTracker.ENABLED) {
			ShimTracker.geometry().vertex((float) x, (float) y, (float) z);
		}
	}

	@Inject(method = "texture(DD)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$texture(double u, double v, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.texture(u, v);
			ci.cancel();
		} else if (ShimTracker.ENABLED) {
			ShimTracker.geometry().texCoord((float) u, (float) v);
		}
	}

	@Inject(method = "color(IIII)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$color(int r, int g, int b, int a, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.color(r, g, b, a);
			ci.cancel();
		} else if (ShimTracker.ENABLED) {
			ShimTracker.geometry().color(r, g, b, a);
		}
	}

	@Inject(method = "normal(FFF)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$normal(float x, float y, float z, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.normal(x, y, z);
			ci.cancel();
		} else if (ShimTracker.ENABLED) {
			ShimTracker.geometry().normal(x, y, z);
		}
	}

	@Inject(method = "setOffset(DDD)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$setOffset(double x, double y, double z, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.setOffset(x, y, z);
			ci.cancel();
		}
	}

	@Inject(method = "translate(FFF)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$translate(float x, float y, float z, CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.translate(x, y, z);
			ci.cancel();
		}
	}

	@Inject(method = "disableColor()V", at = @At("HEAD"), cancellable = true)
	private void retroperf$disableColor(CallbackInfo ci) {
		VertexSink sink = Capture.sink();
		if (sink != null) {
			sink.disableColor();
			ci.cancel();
		}
	}

	@Inject(method = "start(I)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$start(int mode, CallbackInfo ci) {
		if (Capture.sink() != null) {
			ci.cancel();
		}
	}

	/**
	 * The geometry seam.
	 *
	 * <p>Under WebGPU this is the ONLY place vertices enter the renderer: beta submits every quad it
	 * draws -- terrain, entities, particles, items, text, every GUI element -- through this one
	 * method, using vertex arrays rather than immediate mode. So capturing here covers the whole
	 * game rather than a growing list of special cases.
	 *
	 * <p>Injected at HEAD, which is before {@code draw()} has copied its {@code int[]} into the
	 * NIO buffer it hands to {@code glVertexPointer}. That copy is done here instead -- matching the
	 * bytecode position after the fill would be fragile, and the copy is the same three lines.
	 */
	@Inject(method = "draw()V", at = @At("HEAD"), cancellable = true)
	private void retroperf$draw(CallbackInfo ci) {
		if (Capture.sink() != null) {
			ci.cancel();
			return;
		}
		if (WebGpuFrame.active()) {
			if (vertexCount > 0) {
				retroperf$repairSplitNormals();
				intBuffer.clear();
				intBuffer.put(buffer, 0, bufferPosition);
				byteBuffer.position(0);
				byteBuffer.limit(bufferPosition * 4);
				// The same substitution draw() makes: quads pre-expanded on the CPU are submitted as
				// a triangle list, not as quads.
				int submitted = QuadVertices.splitting(TRIANGLE_MODE)
					&& mode == com.periut.retrodragon.render.Primitives.GL_QUADS
					? com.periut.retrodragon.render.Primitives.GL_TRIANGLES
					: mode;
				WebGpuFrame.capture(byteBuffer, vertexCount, submitted, hasColor, hasNormals,
					hasTexture);
			}
			// draw() would have done this on the way out. Skipping it leaves the Tessellator
			// believing it is still mid-batch, and the very next start() throws.
			drawing = false;
			addedVertexCount += vertexCount;
			vertexCount = 0;
			byteBuffer.clear();
			bufferPosition = 0;
			ci.cancel();
			return;
		}
		DrawStats.countUncaptured();
	}
}
