package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * One section layer's geometry, living in a GPU buffer instead of a display list.
 *
 * <p>Backend-agnostic by holding both: a GL VBO name and, under WebGPU, a {@code GpuBuffer}. Only
 * one is ever populated, since the backend is fixed at startup. The packed vertex data is identical
 * either way -- 8 ints per vertex, the same 32-byte stride beta's Tessellator uses and the same one
 * {@code fixedfunc.wgsl} declares -- so the mesher feeds both without a conversion.
 */
public final class SectionMesh {
	/** Scratch upload staging, reused across every section on the render thread. */
	private static ByteBuffer staging = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());

	private int vbo;
	private int vertexCount;
	/** WebGPU fallback when the shared arena is off or full; null otherwise, and always under GL. */
	private com.periut.retrodragon.gpu.GpuBuffer gpuBuffer;
	/** This layer's slice of the shared arena, or -1 when it has none. */
	private int arenaVertex = -1;
	private int arenaSize;

	public int vertexCount() {
		return this.vertexCount;
	}

	/** First vertex within the shared arena, or -1 when this layer owns its buffer. */
	public int arenaVertex() {
		return this.arenaVertex;
	}

	/** Render thread only. {@code data} is packed vertices in whatever layout the mesher used. */
	public void upload(int[] data) {
		// The layout is a process-wide decision made before any geometry exists, so the mesher that
		// filled this array and the pipeline that will read it agree by construction rather than by
		// anything carried alongside the data.
		boolean compact = TerrainVertex.compact();
		int stride = TerrainVertex.stride(compact);
		int verts = data.length / TerrainVertex.strideInts(compact);
		this.vertexCount = verts;
		if (verts == 0) {
			return;
		}
		int bytes = verts * stride;
		if (staging.capacity() < bytes) {
			int cap = staging.capacity();
			while (cap < bytes) {
				cap <<= 1;
			}
			staging = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
		}
		staging.clear();
		staging.asIntBuffer().put(data, 0, data.length);
		staging.limit(bytes);

		if (WebGpuFrame.active()) {
			// A section is re-meshed only when its blocks change, so this runs on a block edit or a
			// chunk load, never per frame.
			//
			// The shared arena is tried first when enabled: allocations there are exact-fit and
			// coalescing, so neighbouring sections end up adjacent and collapse into one draw. When
			// it is off or full, the section keeps a buffer of its own -- one extra draw, and the
			// path that is known to work.
			TerrainArena arena = TerrainArena.enabled() ? TerrainArena.get() : null;
			if (arena != null) {
				int at = arena.upload(this.arenaVertex, this.arenaSize, verts, staging);
				if (at != ArenaAllocator.NO_SPACE) {
					this.arenaVertex = at;
					this.arenaSize = verts;
					if (this.gpuBuffer != null) {
						this.gpuBuffer.close();
						this.gpuBuffer = null;
					}
					return;
				}
				// Refused: the allocator already released the old slice, so this layer has none.
				this.arenaVertex = -1;
				this.arenaSize = 0;
			}
			if (this.gpuBuffer == null) {
				this.gpuBuffer = new com.periut.retrodragon.gpu.GpuBuffer(GpuBackend.context(),
					com.periut.retrodragon.gpu.Flags.BUFFER_USAGE_VERTEX, "retrodragon-section");
			}
			this.gpuBuffer.write(staging, bytes);
			return;
		}

		if (this.vbo == 0) {
			this.vbo = GL15.glGenBuffers();
		}
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, staging, GL15.GL_STATIC_DRAW);
		// Must not leave this bound: beta's immediate-mode Tessellator hands GL client-memory
		// pointers, which are reinterpreted as offsets into whatever array buffer is bound.
		// Leaving it bound segfaults the driver on the next legacy draw.
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
	}

	/** Marks the layer empty without dropping the VBO, which is likely reused on the next build. */
	public void clear() {
		this.vertexCount = 0;
	}

	/** Assumes the caller has already enabled the client arrays; see {@link SectionDrawer}. */
	public void draw() {
		if (this.vertexCount == 0) {
			return;
		}
		if (WebGpuFrame.active()) {
			// Quads when the mesher left them as four vertices, a triangle list when it expanded
			// them. The buffer's shape and the mode it is submitted as are the same decision, made
			// once in QuadVertices -- getting them out of step draws every quad as one sheared
			// triangle, which is what it looked like the first time this project got it wrong.
			if (this.arenaVertex >= 0) {
				WebGpuFrame.captureTerrain(TerrainArena.get().buffer(), this.arenaVertex,
					this.vertexCount);
			} else if (this.gpuBuffer != null) {
				WebGpuFrame.captureTerrain(this.gpuBuffer, 0, this.vertexCount);
			}
			return;
		}
		if (this.vbo == 0) {
			return;
		}
		// Always the legacy layout here: fixed-function GL has no normalised integer texture
		// coordinate, so TerrainVertex.compact() is false whenever this backend is the live one.
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
		GL11.glVertexPointer(3, GL11.GL_FLOAT, VertexSink.STRIDE_BYTES, 0L);
		GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VertexSink.STRIDE_BYTES, 12L);
		GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VertexSink.STRIDE_BYTES, 20L);
		GL11.glNormalPointer(GL11.GL_BYTE, VertexSink.STRIDE_BYTES, 24L);
		if (TerrainVertex.spriteClamp() && TerrainShader.isActive()) {
			// Beta's pad word, which nothing else reads -- so the stitched-atlas pitch rides along in
			// the legacy layout for free. NOT normalised: the shader wants texels (16, 32), not 0..1.
			// A generic attribute rather than a second texture coordinate, because fixed-function
			// glTexCoordPointer cannot deliver an unnormalised integer and this one can.
			GL20.glEnableVertexAttribArray(TerrainShader.SPRITE_ATTRIB);
			GL20.glVertexAttribPointer(TerrainShader.SPRITE_ATTRIB, 1, GL11.GL_UNSIGNED_BYTE, false,
				VertexSink.STRIDE_BYTES, TerrainVertex.spriteOffset(false));
		}
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.vertexCount);
	}

	/** Render thread only. */
	public void free() {
		if (this.vbo != 0) {
			GL15.glDeleteBuffers(this.vbo);
			this.vbo = 0;
		}
		if (this.arenaVertex >= 0) {
			TerrainArena arena = TerrainArena.get();
			if (arena != null) {
				arena.release(this.arenaVertex, this.arenaSize);
			}
			this.arenaVertex = -1;
			this.arenaSize = 0;
		}
		if (this.gpuBuffer != null) {
			this.gpuBuffer.close();
			this.gpuBuffer = null;
		}
		this.vertexCount = 0;
	}
}
