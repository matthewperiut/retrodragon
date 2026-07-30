package com.periut.retrodragon.shim;

import com.periut.retrodragon.render.Primitives;
import com.periut.retrodragon.render.QuadIndices;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Everything the game drew this frame, recorded in submission order.
 *
 * <p>Under GL, a draw takes effect the moment beta calls it. Under WebGPU a draw is a command
 * recorded into an encoder, and the state it needs -- matrices, colour, fog, the bound texture -- has
 * to be captured AT THE CALL, not read back later, because by the time the frame is submitted beta
 * has moved on. So each batch carries its own copy of the uniform block, and the vertices are
 * appended to one buffer that is uploaded once.
 *
 * <h2>Why the vertices need no conversion</h2>
 *
 * Beta's Tessellator already packs vertices as 8 ints -- position 3f at 0, texture 2f at 12, colour
 * 4 bytes at 20, normal 3 bytes at 24 -- which is a 32-byte stride identical to what
 * {@code fixedfunc.wgsl} declares. Capturing a batch is therefore a straight copy of the bytes beta
 * was about to hand to {@code glVertexPointer}, with no repacking at all.
 *
 * <p>Not thread-safe. Every caller is the render thread, and immediate-mode drawing is inherently
 * serial.
 */
public final class DrawList {
	/** Bytes per vertex, matching beta's Tessellator and {@code fixedfunc.wgsl}. */
	public static final int STRIDE = 32;

	/** One uniform block per batch, at WebGPU's guaranteed dynamic-offset alignment. */
	public static final int UNIFORM_SLOT = 256;

	private static final int INITIAL_VERTICES = 16384;
	private static final int INITIAL_BATCHES = 512;

	private ByteBuffer vertices;
	private int vertexCount;

	private ByteBuffer uniforms;
	private int batchCount;

	private long[] pipelineKey = new long[INITIAL_BATCHES];
	private int[] firstVertex = new int[INITIAL_BATCHES];
	private int[] count = new int[INITIAL_BATCHES];
	private int[] texture = new int[INITIAL_BATCHES];
	private int[] glMode = new int[INITIAL_BATCHES];

	/**
	 * Index into {@link #buffers} for a batch whose vertices live in a buffer of their own, or -1
	 * for the ordinary case where they were copied into this frame's shared buffer.
	 *
	 * <p>Terrain needs this. A section's geometry changes only when the blocks in it change, so
	 * copying it into the frame buffer every frame would move tens of megabytes a second to
	 * reproduce data the GPU already has. Instead each section keeps its own buffer and the batch
	 * just points at it -- but it still has to occupy its true position in the frame's draw order,
	 * because the world is drawn before the GUI and depth-tested against it.
	 */
	private int[] external = new int[INITIAL_BATCHES];
	private final java.util.ArrayList<Object> buffers = new java.util.ArrayList<>();

	// --- pass segments ---------------------------------------------------------------------------
	//
	// Beta clears mid-frame -- the depth buffer between the world and the GUI, most notably -- and
	// under GL that takes effect immediately. WebGPU has no mid-pass clear at all: a clear is a
	// LOAD OP, chosen when the pass begins. So a clear ends the current pass and opens a new one,
	// and the frame becomes a sequence of segments rather than a single pass.
	private static final int INITIAL_SEGMENTS = 8;
	private static final int CLEAR_COLOR = 1;
	private static final int CLEAR_DEPTH = 2;

	private int[] segmentFirstBatch = new int[INITIAL_SEGMENTS];
	private int[] segmentFlags = new int[INITIAL_SEGMENTS];
	private float[] segmentClear = new float[INITIAL_SEGMENTS * 4];
	private int segmentCount;

	public DrawList() {
		vertices = direct(INITIAL_VERTICES * STRIDE);
		uniforms = direct(INITIAL_BATCHES * UNIFORM_SLOT);
		reset();
	}

	/**
	 * Ends the current segment and starts one whose pass begins with these load ops.
	 *
	 * <p>Coalesces with the segment already open when it is still empty: beta issues several
	 * consecutive clears at the top of a frame (colour, then depth), and each one starting its own
	 * empty render pass would cost a full attachment load/store round trip for nothing.
	 */
	public void clear(boolean color, boolean depth, float r, float g, float b, float a) {
		int flags = (color ? CLEAR_COLOR : 0) | (depth ? CLEAR_DEPTH : 0);
		int current = segmentCount - 1;
		if (segmentFirstBatch[current] == batchCount) {
			segmentFlags[current] |= flags;
			if (color) {
				setClear(current, r, g, b, a);
			}
			return;
		}
		ensureSegments();
		segmentFirstBatch[segmentCount] = batchCount;
		segmentFlags[segmentCount] = flags;
		setClear(segmentCount, r, g, b, a);
		segmentCount++;
	}

	private void setClear(int segment, float r, float g, float b, float a) {
		segmentClear[segment * 4] = r;
		segmentClear[segment * 4 + 1] = g;
		segmentClear[segment * 4 + 2] = b;
		segmentClear[segment * 4 + 3] = a;
	}

	private void ensureSegments() {
		if (segmentCount < segmentFirstBatch.length) {
			return;
		}
		int capacity = segmentFirstBatch.length * 2;
		segmentFirstBatch = java.util.Arrays.copyOf(segmentFirstBatch, capacity);
		segmentFlags = java.util.Arrays.copyOf(segmentFlags, capacity);
		segmentClear = java.util.Arrays.copyOf(segmentClear, capacity * 4);
	}

	public int segmentCount() {
		return segmentCount;
	}

	public int segmentFirstBatch(int segment) {
		return segmentFirstBatch[segment];
	}

	/** Exclusive end batch of a segment; the last one runs to the end of the frame. */
	public int segmentEndBatch(int segment) {
		return segment + 1 < segmentCount ? segmentFirstBatch[segment + 1] : batchCount;
	}

	public boolean segmentClearsColor(int segment) {
		return (segmentFlags[segment] & CLEAR_COLOR) != 0;
	}

	public boolean segmentClearsDepth(int segment) {
		return (segmentFlags[segment] & CLEAR_DEPTH) != 0;
	}

	public float segmentClear(int segment, int channel) {
		return segmentClear[segment * 4 + channel];
	}

	private static ByteBuffer direct(int bytes) {
		return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
	}

	/**
	 * Records one batch.
	 *
	 * @param source     beta's own vertex bytes, read from its current position; not consumed
	 * @param vertices   how many vertices {@code source} holds
	 * @param glMode     the {@code glDrawArrays} primitive mode ({@code GL_QUADS} for most of beta)
	 * @param uniformBlock the state this batch draws under, exactly {@link GlState#UNIFORM_BYTES}
	 */
	public void add(ByteBuffer source, int vertices, int glMode, long pipelineKey, int texture,
			ByteBuffer uniformBlock) {
		if (vertices <= 0) {
			return;
		}
		ensureVertices(vertices);

		// Merge into the previous batch when nothing that affects the draw has changed. Beta emits
		// long runs of these -- every string of text, every GUI element on a screen, every particle
		// -- and they differ only in vertex data. Merging turns a screenful of text from hundreds of
		// draws into one, and it is exact rather than approximate: the batches share a pipeline, a
		// texture, a primitive mode and a byte-identical uniform block, so one draw over the
		// concatenated vertices is the same GPU work.
		if (canMerge(glMode, pipelineKey, texture, uniformBlock)) {
			copy(source, vertices * STRIDE, this.vertices, vertexCount * STRIDE);
			count[batchCount - 1] += vertices;
			vertexCount += vertices;
			merged++;
			return;
		}

		ensureBatches();

		// Bulk copies, not per-byte loops: both buffers are direct, so this is an intrinsified
		// memcpy. A busy frame moves a few megabytes through here, and the naive loop showed up as
		// the single most expensive thing in the capture path.
		copy(source, vertices * STRIDE, this.vertices, vertexCount * STRIDE);
		copy(uniformBlock, GlState.UNIFORM_BYTES, uniforms, batchCount * UNIFORM_SLOT);

		this.pipelineKey[batchCount] = pipelineKey;
		this.firstVertex[batchCount] = vertexCount;
		this.count[batchCount] = vertices;
		this.texture[batchCount] = texture;
		this.glMode[batchCount] = glMode;
		this.external[batchCount] = -1;

		vertexCount += vertices;
		batchCount++;
	}

	/**
	 * Records a batch whose vertices are already on the GPU, in a buffer the caller owns.
	 *
	 * <p>Never merges with a neighbour: two batches in different buffers cannot become one draw.
	 *
	 * @param buffer      an opaque handle the renderer knows how to bind
	 * @param firstVertex offset within that buffer, in vertices
	 */
	public void addExternal(Object buffer, int firstVertex, int vertices, int glMode,
			long pipelineKey, int texture, ByteBuffer uniformBlock) {
		if (vertices <= 0 || buffer == null) {
			return;
		}
		// Two allocations that are adjacent in the same buffer, under identical state, are one
		// draw. This is what the terrain arena exists for: at the default view distance it turns
		// hundreds of per-section draws into a handful of runs.
		if (canMergeExternal(buffer, firstVertex, glMode, pipelineKey, texture, uniformBlock)) {
			count[batchCount - 1] += vertices;
			merged++;
			return;
		}
		ensureBatches();
		copy(uniformBlock, GlState.UNIFORM_BYTES, uniforms, batchCount * UNIFORM_SLOT);

		int handle = buffers.indexOf(buffer);
		if (handle < 0) {
			handle = buffers.size();
			buffers.add(buffer);
		}

		this.pipelineKey[batchCount] = pipelineKey;
		this.firstVertex[batchCount] = firstVertex;
		this.count[batchCount] = vertices;
		this.texture[batchCount] = texture;
		this.glMode[batchCount] = glMode;
		this.external[batchCount] = handle;
		batchCount++;
	}

	/** The buffer a batch draws from, or null when it uses this frame's shared vertex buffer. */
	public Object buffer(int batch) {
		int handle = external[batch];
		return handle < 0 ? null : buffers.get(handle);
	}

	/**
	 * Whether a batch can be appended to the previous one instead of starting its own draw.
	 *
	 * <p>Only within a segment: a pass boundary is a clear, and geometry cannot cross it. Indexed
	 * primitives merge because the index pattern is relative to the batch's base vertex and repeats
	 * -- quad N of a merged batch indexes exactly the vertices it did before. Strips and fans do NOT
	 * merge: concatenating two strips would create phantom triangles across the join.
	 */
	private boolean canMerge(int glMode, long pipelineKey, int texture, ByteBuffer uniformBlock) {
		if (batchCount == 0 || segmentFirstBatch[segmentCount - 1] == batchCount) {
			return false;
		}
		int previous = batchCount - 1;
		if (this.external[previous] >= 0) {
			// The previous batch draws from its own buffer; this one's vertices are in the shared
			// buffer, so they cannot be one draw.
			return false;
		}
		if (this.glMode[previous] != glMode || this.pipelineKey[previous] != pipelineKey
				|| this.texture[previous] != texture) {
			return false;
		}
		if (glMode != 4 && glMode != 7 && glMode != 1 && glMode != 0) {
			// GL_TRIANGLES, GL_QUADS, GL_LINES and GL_POINTS are the modes where each primitive is
			// self-contained. Everything else depends on its neighbours.
			return false;
		}
		// Same quad-alignment rule as canMergeExternal, for the same reason.
		if (glMode == 7 && this.count[previous] % QuadIndices.VERTICES_PER_QUAD != 0) {
			return false;
		}
		return sameUniforms(previous, uniformBlock);
	}

	/**
	 * Whether an arena-backed batch continues the previous one exactly.
	 *
	 * <p>Requires true adjacency -- {@code firstVertex + count} of the previous batch must equal this
	 * one's first vertex. Anything looser would draw the gap between them, which is either another
	 * section that was deliberately culled or unallocated space.
	 */
	private boolean canMergeExternal(Object buffer, int firstVertex, int glMode, long pipelineKey,
			int texture, ByteBuffer uniformBlock) {
		if (batchCount == 0 || segmentFirstBatch[segmentCount - 1] == batchCount) {
			return false;
		}
		int previous = batchCount - 1;
		if (external[previous] < 0 || buffers.get(external[previous]) != buffer) {
			return false;
		}
		if (this.glMode[previous] != glMode || this.pipelineKey[previous] != pipelineKey
				|| this.texture[previous] != texture) {
			return false;
		}
		if (this.firstVertex[previous] + this.count[previous] != firstVertex) {
			return false;
		}
		// An indexed quad batch draws the pattern 4q+{0,1,2,0,2,3} offset by a base vertex, so quad
		// boundaries in a MERGED run sit every four vertices from the run's start. A previous batch
		// whose vertex count is not a multiple of four shifts every quad after it by the remainder,
		// which does not drop geometry -- it draws triangles spanning two unrelated faces. Beta's
		// terrain never produces such a batch; refusing to merge one costs a draw call and removes
		// the possibility entirely.
		if (Primitives.indexing(glMode) == Primitives.Indexing.QUADS
				&& this.count[previous] % QuadIndices.VERTICES_PER_QUAD != 0) {
			return false;
		}
		return sameUniforms(previous, uniformBlock);
	}

	/**
	 * Whether a batch's uniform block is byte-identical to the one already stored for {@code batch}.
	 *
	 * <p>{@link ByteBuffer#mismatch} rather than a loop over {@code get}: it is a JDK intrinsic that
	 * compares machine words, where the loop was 256 bounds-checked single-byte reads. This runs
	 * once per batch and terrain alone offers it hundreds of times a frame, every frame, for the
	 * express purpose of failing fast -- so it is worth having it fail fast cheaply.
	 */
	private boolean sameUniforms(int batch, ByteBuffer uniformBlock) {
		int slot = batch * UNIFORM_SLOT;
		int position = uniforms.position();
		int limit = uniforms.limit();
		int blockPosition = uniformBlock.position();
		int blockLimit = uniformBlock.limit();
		try {
			uniforms.clear().position(slot).limit(slot + GlState.UNIFORM_BYTES);
			uniformBlock.limit(blockPosition + GlState.UNIFORM_BYTES);
			return uniforms.mismatch(uniformBlock) < 0;
		} finally {
			uniforms.limit(limit).position(position);
			uniformBlock.limit(blockLimit).position(blockPosition);
		}
	}

	/** How many batches were folded into a neighbour this frame; a direct measure of the win. */
	public int mergedCount() {
		return merged;
	}

	private int merged;

	/** Copies {@code bytes} from {@code source}'s position into {@code destination} at an offset,
	 * leaving both buffers' position and limit exactly as they were -- beta reuses its buffer. */
	private static void copy(ByteBuffer source, int bytes, ByteBuffer destination, int at) {
		int sourcePosition = source.position();
		int sourceLimit = source.limit();
		int destinationPosition = destination.position();
		int destinationLimit = destination.limit();
		try {
			source.limit(sourcePosition + bytes);
			destination.clear().position(at);
			destination.put(source);
		} finally {
			source.position(sourcePosition).limit(sourceLimit);
			destination.limit(destinationLimit).position(destinationPosition);
		}
	}

	private void ensureVertices(int extra) {
		int needed = (vertexCount + extra) * STRIDE;
		if (needed <= vertices.capacity()) {
			return;
		}
		int capacity = vertices.capacity();
		while (capacity < needed) {
			capacity *= 2;
		}
		ByteBuffer grown = direct(capacity);
		vertices.position(0).limit(vertexCount * STRIDE);
		grown.put(vertices);
		grown.clear();
		vertices = grown;
	}

	private void ensureBatches() {
		if (batchCount < pipelineKey.length) {
			return;
		}
		int capacity = pipelineKey.length * 2;
		pipelineKey = java.util.Arrays.copyOf(pipelineKey, capacity);
		firstVertex = java.util.Arrays.copyOf(firstVertex, capacity);
		count = java.util.Arrays.copyOf(count, capacity);
		texture = java.util.Arrays.copyOf(texture, capacity);
		glMode = java.util.Arrays.copyOf(glMode, capacity);
		external = java.util.Arrays.copyOf(external, capacity);

		ByteBuffer grown = direct(capacity * UNIFORM_SLOT);
		uniforms.position(0).limit(batchCount * UNIFORM_SLOT);
		grown.put(uniforms);
		grown.clear();
		uniforms = grown;
	}

	public int batchCount() {
		return batchCount;
	}

	public int vertexCount() {
		return vertexCount;
	}

	public long pipelineKey(int batch) {
		return pipelineKey[batch];
	}

	public int firstVertex(int batch) {
		return firstVertex[batch];
	}

	public int count(int batch) {
		return count[batch];
	}

	public int texture(int batch) {
		return texture[batch];
	}

	public int glMode(int batch) {
		return glMode[batch];
	}

	/** Byte offset of this batch's uniform block, ready to pass as a dynamic offset. */
	public int uniformOffset(int batch) {
		return batch * UNIFORM_SLOT;
	}

	/** Vertex bytes, position 0, limited to live data -- hand straight to a buffer write. */
	public ByteBuffer vertexData() {
		return vertices.position(0).limit(vertexCount * STRIDE);
	}

	public ByteBuffer uniformData() {
		return uniforms.position(0).limit(batchCount * UNIFORM_SLOT);
	}

	/** Drops the frame. Capacity is kept, so a steady-state frame allocates nothing. */
	public void reset() {
		vertices.clear();
		uniforms.clear();
		vertexCount = 0;
		batchCount = 0;
		merged = 0;
		buffers.clear();
		// One segment always exists, so a batch can be recorded before any clear arrives -- a mod
		// that draws before beta's first glClear would otherwise have nowhere to go.
		segmentCount = 1;
		segmentFirstBatch[0] = 0;
		segmentFlags[0] = 0;
		setClear(0, 0.0F, 0.0F, 0.0F, 1.0F);
	}
}
