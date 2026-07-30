package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.GpuBuffer;

import java.nio.ByteBuffer;

/**
 * The shared terrain buffer, so neighbouring sections can be drawn together.
 *
 * <p>Policy lives in {@link ArenaAllocator}, which is pure arithmetic and tested directly; this is
 * only the GPU buffer around it. That split exists because the first attempt at an arena failed
 * entirely on allocator behaviour -- it grew past Dawn's {@code maxBufferSize} and produced
 * allocations that could never be adjacent -- and neither fault is visible through a rendering test.
 *
 * <p><b>On by default</b>; {@code -Dretroperf.terrainArena=false} reverts to a buffer per section.
 * Measured upside is a 2.35x reduction in terrain draw calls under synthetic churn.
 *
 * <p>An earlier version of this class froze the game and rendered a world without its surface, so
 * the two mechanisms behind those failures are gone by construction rather than patched:
 *
 * <ul>
 *   <li><b>It never grows.</b> Sized once against the device limit. Growing a buffer that hundreds
 *       of sections hold offsets into is what silently invalidated every one of them.</li>
 *   <li><b>It is capped.</b> Past {@code maxBufferSize} Dawn returns an INVALID buffer rather than
 *       failing, and the symptom appears hundreds of frames later with nothing pointing at the
 *       cause. The allocator refuses instead.</li>
 *   <li><b>A section it cannot fit keeps its own buffer.</b> That costs one draw, not a broken
 *       frame, so a full arena degrades to the previous behaviour rather than to corruption.</li>
 * </ul>
 */
public final class TerrainArena {
	/**
	 * Bytes per terrain vertex, read once. The arena is sized in vertices and every offset it hands
	 * out is a vertex index, so this has to be the same number {@link VertexSink} packed with -- see
	 * {@link TerrainVertex}. It is fixed before the first section is meshed and never changes.
	 */
	private static final int STRIDE = TerrainVertex.stride(TerrainVertex.compact());
	/** Default 128 MB, which is half of WebGPU's guaranteed minimum maxBufferSize. */
	private static final long DEFAULT_BYTES = Long.getLong("retroperf.terrainArenaMB", 128L) << 20;

	private static TerrainArena instance;
	private static boolean unavailable;

	private final GpuBuffer buffer;
	private final ArenaAllocator allocator;
	private int fallbacks;

	private TerrainArena(GpuBuffer buffer, ArenaAllocator allocator) {
		this.buffer = buffer;
		this.allocator = allocator;
	}

	/** {@code -Dretroperf.terrainArena=false} reverts to a buffer per section. */
	public static boolean enabled() {
		return !"false".equals(System.getProperty("retroperf.terrainArena"));
	}

	public static synchronized TerrainArena get() {
		if (instance != null || unavailable) {
			return instance;
		}
		try {
			long bytes = Math.min(DEFAULT_BYTES, GpuBuffer.maxSize(GpuBackend.context()));
			GpuBuffer buffer = GpuBuffer.sized(GpuBackend.context(),
				Flags.BUFFER_USAGE_VERTEX, "retrodragon-terrain-arena", bytes);
			instance = new TerrainArena(buffer, new ArenaAllocator((int) (bytes / STRIDE)));
			com.periut.retrodragon.RetroDragon.LOGGER.info(
				"terrain arena: {} MB, {} vertices", bytes >> 20, bytes / STRIDE);
		} catch (RuntimeException e) {
			// Never fatal: without an arena every section keeps its own buffer, which is the
			// default path anyway.
			unavailable = true;
			com.periut.retrodragon.RetroDragon.LOGGER.warn(
				"terrain arena unavailable; using a buffer per section", e);
		}
		return instance;
	}

	public static synchronized void shutdown() {
		if (instance != null) {
			instance.buffer.close();
			instance = null;
		}
		unavailable = false;
	}

	public GpuBuffer buffer() {
		return buffer;
	}

	/**
	 * Moves an allocation to a new size and uploads into it.
	 *
	 * @param previous     the caller's current allocation, or -1
	 * @param previousSize how many vertices that allocation covers
	 * @return the new first vertex, or {@link ArenaAllocator#NO_SPACE} if the caller should keep its
	 *         own buffer instead
	 */
	public synchronized int upload(int previous, int previousSize, int vertices, ByteBuffer data) {
		if (previous >= 0 && previousSize == vertices) {
			// Same size: rewrite in place. This is the common case, since re-meshing a section
			// usually changes a few faces rather than its magnitude.
			buffer.writeAt((long) previous * STRIDE, data, vertices * STRIDE);
			return previous;
		}
		if (previous >= 0) {
			allocator.release(previous, previousSize);
		}
		int at = allocator.allocate(vertices);
		if (at == ArenaAllocator.NO_SPACE) {
			fallbacks++;
			return ArenaAllocator.NO_SPACE;
		}
		buffer.writeAt((long) at * STRIDE, data, vertices * STRIDE);
		return at;
	}

	public synchronized void release(int at, int vertices) {
		allocator.release(at, vertices);
	}

	/** How many sections the arena could not fit; a steadily rising count means it is too small. */
	public synchronized int fallbacks() {
		return fallbacks;
	}

	public synchronized int liveVertices() {
		return allocator.live();
	}
}
