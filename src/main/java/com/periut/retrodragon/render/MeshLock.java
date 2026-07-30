package com.periut.retrodragon.render;

/**
 * Guards beta's shared mutable {@code Block} bounds.
 *
 * {@code BlockRenderManager.render} calls {@code updateBoundingBox}/{@code setBoundingBox} on the
 * {@code Block.BLOCKS[id]} singleton, and the main thread reads those same fields for physics and
 * item rendering. Meshing off-thread without this lock corrupts collision boxes and item shapes.
 *
 * ponytail: one global monitor. It serializes workers against each other as well as against the
 * main thread, so more than ~2 workers will not scale. The upgrade, if that ever shows up in the
 * bench, is per-block-id stripe monitors plus a global read/write lock (bounds writes always target
 * the block being rendered; {@code getEntityCollisions} touches many ids and needs the write lock).
 */
public final class MeshLock {
	public static final Object BLOCK_BOUNDS = new Object();

	/** False while meshing is synchronous, so main-thread consumers skip locking entirely. */
	public static volatile boolean active;

	private MeshLock() {
	}
}
