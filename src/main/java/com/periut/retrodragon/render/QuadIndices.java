package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The shared index buffer that turns beta's quads into triangles.
 *
 * <p>{@code GL_QUADS} is what beta's Tessellator emits for terrain, entities, particles, items and
 * every GUI element -- and it does not exist in WebGPU, D3D12, Metal or modern GL core. The standard
 * fix is one static index buffer of the repeating pattern {@code 0,1,2, 0,2,3}, bound once and
 * reused by every quad draw: the vertices stay exactly as beta wrote them, and the conversion costs
 * no per-frame CPU work at all.
 *
 * <p>16-bit indices while a batch stays under 65536 vertices, 32-bit past that. Terrain sections
 * routinely exceed it; a GUI screen never does.
 */
public final class QuadIndices {
	/** Vertices per quad and indices per quad -- the 4:6 ratio the buffer is sized from. */
	public static final int VERTICES_PER_QUAD = 4;
	public static final int INDICES_PER_QUAD = 6;

	/** Above this many vertices in one batch, 16-bit indices cannot address the buffer. */
	public static final int MAX_16_BIT_VERTICES = 65536;

	private QuadIndices() {
	}

	/** {@code 0,1,2, 0,2,3} repeated, as 16-bit indices. */
	public static ByteBuffer forQuads(int quads) {
		ByteBuffer buffer = ByteBuffer
			.allocateDirect(quads * INDICES_PER_QUAD * 2)
			.order(ByteOrder.nativeOrder());
		for (int quad = 0; quad < quads; quad++) {
			int base = quad * VERTICES_PER_QUAD;
			buffer.putShort((short) base);
			buffer.putShort((short) (base + 1));
			buffer.putShort((short) (base + 2));
			buffer.putShort((short) base);
			buffer.putShort((short) (base + 2));
			buffer.putShort((short) (base + 3));
		}
		buffer.flip();
		return buffer;
	}

	/** The same pattern as 32-bit indices, for batches past {@link #MAX_16_BIT_VERTICES}. */
	public static ByteBuffer forQuads32(int quads) {
		ByteBuffer buffer = ByteBuffer
			.allocateDirect(quads * INDICES_PER_QUAD * 4)
			.order(ByteOrder.nativeOrder());
		for (int quad = 0; quad < quads; quad++) {
			int base = quad * VERTICES_PER_QUAD;
			buffer.putInt(base);
			buffer.putInt(base + 1);
			buffer.putInt(base + 2);
			buffer.putInt(base);
			buffer.putInt(base + 2);
			buffer.putInt(base + 3);
		}
		buffer.flip();
		return buffer;
	}

	/**
	 * Indices for a triangle FAN of {@code n} vertices: {@code 0,1,2, 0,2,3, 0,3,4, ...}.
	 *
	 * <p>WebGPU has no fan topology. Converting to an indexed list rather than adding a topology
	 * also means fans batch with everything else instead of forcing their own draw.
	 */
	public static ByteBuffer forFan(int vertices) {
		int triangles = Math.max(0, vertices - 2);
		ByteBuffer buffer = ByteBuffer.allocateDirect(triangles * 3 * 2).order(ByteOrder.nativeOrder());
		for (int i = 0; i < triangles; i++) {
			buffer.putShort((short) 0);
			buffer.putShort((short) (i + 1));
			buffer.putShort((short) (i + 2));
		}
		buffer.flip();
		return buffer;
	}

	/** {@link #forFan(int)} as 32-bit indices, which is what the shared fan buffer uses. */
	public static ByteBuffer forFan32(int vertices) {
		int triangles = Math.max(0, vertices - 2);
		ByteBuffer buffer = ByteBuffer.allocateDirect(triangles * 3 * 4).order(ByteOrder.nativeOrder());
		for (int i = 0; i < triangles; i++) {
			buffer.putInt(0);
			buffer.putInt(i + 1);
			buffer.putInt(i + 2);
		}
		buffer.flip();
		return buffer;
	}

	/**
	 * Indices for a triangle STRIP of {@code n} vertices, with the winding flip GL applies to every
	 * odd triangle -- without it every other triangle faces backwards and vanishes under culling.
	 */
	public static ByteBuffer forStrip(int vertices) {
		int triangles = Math.max(0, vertices - 2);
		ByteBuffer buffer = ByteBuffer.allocateDirect(triangles * 3 * 2).order(ByteOrder.nativeOrder());
		for (int i = 0; i < triangles; i++) {
			if ((i & 1) == 0) {
				buffer.putShort((short) i);
				buffer.putShort((short) (i + 1));
				buffer.putShort((short) (i + 2));
			} else {
				buffer.putShort((short) (i + 1));
				buffer.putShort((short) i);
				buffer.putShort((short) (i + 2));
			}
		}
		buffer.flip();
		return buffer;
	}
}
