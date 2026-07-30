package com.periut.retrodragon.shim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Records immediate-mode geometry as draw calls a GPU backend can replay.
 *
 * <p>{@link GlShim} models the fixed-function <em>state</em> -- matrices, blend, fog, alpha test --
 * and reduces it to a {@link PipelineKey} plus a uniform block. That is only half of a draw. This
 * is the other half: the vertices themselves, batched into runs that share a pipeline.
 *
 * <p><b>Why this has to exist before WebGPU can render anything.</b> A WebGPU surface requires a
 * window with no GL context, so the backends cannot coexist on one window -- the switch is
 * all-or-nothing. Every {@code glBegin}/{@code glVertex} beta or any mod issues therefore has to be
 * captured before the window can flip, not after. Terrain already has its own path
 * ({@code render/VertexSink} feeding {@code SectionMesher}); this covers everything else -- GUI,
 * entities, particles, items, the block-break overlay, text.
 *
 * <h2>Vertex format</h2>
 *
 * Interleaved and fixed, matching what beta actually emits: position (3f), texture (2f), colour
 * (4 bytes, RGBA), normal (3 bytes + 1 pad). 32 bytes, the same stride {@code VertexSink} uses, so
 * the two paths can eventually share upload machinery instead of each growing their own.
 *
 * <p>Not thread-safe: immediate-mode GL is inherently single-threaded, and every caller is the
 * render thread.
 */
public final class GeometryCapture {
	/** Bytes per vertex; matches {@code render/VertexSink.STRIDE_BYTES}. */
	public static final int STRIDE_BYTES = 32;

	private static final int INITIAL_VERTICES = 4096;

	private ByteBuffer vertices;
	private int vertexCount;

	/** Current per-vertex attributes, latched GL-style until changed. */
	private float u, v;
	private int rgba = 0xFFFFFFFF;
	private byte nx, ny, nz;

	private int topology = -1;
	private int firstVertexOfBatch;
	private long pipelineKey;
	private boolean capturing;

	public GeometryCapture() {
		vertices = ByteBuffer.allocateDirect(INITIAL_VERTICES * STRIDE_BYTES)
			.order(ByteOrder.nativeOrder());
	}

	/**
	 * Starts a run of primitives.
	 *
	 * @param topology GL primitive mode as passed to {@code glBegin}
	 * @param pipelineKey the state this run draws under, from {@link GlShim#pipelineKey()}
	 */
	public void begin(int topology, long pipelineKey) {
		this.topology = topology;
		this.pipelineKey = pipelineKey;
		this.firstVertexOfBatch = vertexCount;
		this.capturing = true;
	}

	public void texCoord(float s, float t) {
		this.u = s;
		this.v = t;
	}

	/** Colour components are 0-255, as beta passes them. */
	public void color(int r, int g, int b, int a) {
		this.rgba = (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
	}

	public void normal(float x, float y, float z) {
		// GL packs normals to signed bytes for the fixed-function pipeline; keep that so the
		// vertex is 32 bytes rather than growing to 44 for precision nothing here uses.
		this.nx = (byte) (x * 127.0F);
		this.ny = (byte) (y * 127.0F);
		this.nz = (byte) (z * 127.0F);
	}

	/** Emits a vertex using the currently latched attributes. */
	public void vertex(float x, float y, float z) {
		if (!capturing) {
			// A stray glVertex outside begin/end is a bug in the caller, not something to crash
			// the frame over; dropping it keeps the shim observational until it is trusted.
			return;
		}
		ensureCapacity();
		int base = vertexCount * STRIDE_BYTES;
		vertices.putFloat(base, x);
		vertices.putFloat(base + 4, y);
		vertices.putFloat(base + 8, z);
		vertices.putFloat(base + 12, u);
		vertices.putFloat(base + 16, v);
		vertices.putInt(base + 20, rgba);
		vertices.put(base + 24, nx);
		vertices.put(base + 25, ny);
		vertices.put(base + 26, nz);
		vertices.put(base + 27, (byte) 0);
		// 28..31 reserved: keeps the stride a power of two and leaves room for a texture or
		// draw index once batching across texture binds lands.
		vertices.putInt(base + 28, 0);
		vertexCount++;
	}

	/**
	 * Closes the run.
	 *
	 * @return the number of vertices it contributed, or 0 if it was empty
	 */
	public int end() {
		capturing = false;
		return vertexCount - firstVertexOfBatch;
	}

	private void ensureCapacity() {
		if ((vertexCount + 1) * STRIDE_BYTES <= vertices.capacity()) {
			return;
		}
		ByteBuffer grown = ByteBuffer.allocateDirect(vertices.capacity() * 2)
			.order(ByteOrder.nativeOrder());
		vertices.position(0).limit(vertexCount * STRIDE_BYTES);
		grown.put(vertices);
		vertices.clear();
		vertices = grown;
	}

	/** Vertices captured since the last {@link #reset()}. */
	public int vertexCount() {
		return vertexCount;
	}

	public int topology() {
		return topology;
	}

	public long pipelineKey() {
		return pipelineKey;
	}

	/**
	 * The packed vertex data, ready to upload. Position 0, limit at the end of live data -- so it
	 * can be handed straight to a buffer write without another copy.
	 */
	public ByteBuffer data() {
		vertices.position(0).limit(vertexCount * STRIDE_BYTES);
		return vertices;
	}

	/** Drops everything captured; call once per frame after upload. */
	public void reset() {
		vertices.clear();
		vertexCount = 0;
		firstVertexOfBatch = 0;
		capturing = false;
	}
}
