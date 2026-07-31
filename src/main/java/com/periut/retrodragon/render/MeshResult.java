package com.periut.retrodragon.render;

import net.minecraft.client.render.chunk.ChunkBuilder;

/**
 * A finished mesh, waiting to be applied on the render thread.
 *
 * Block entities are carried as packed positions rather than resolved objects: resolving them calls
 * into the chunk's block-entity map, which the main thread mutates, so it is done during apply.
 */
public final class MeshResult {
	public final ChunkBuilder section;
	public final int generation;
	public final int[][] layers = new int[2][];
	public final boolean[] layerEmpty = { true, true };
	public int[] blockEntityPositions = new int[0];
	public int blockEntityCount;
	public boolean hasSkyLight;
	/** Set when the worker threw: the geometry is not this section's, so it must be rebuilt. */
	public boolean failed;
	/** Face-to-face connectivity for the occlusion BFS. */
	public long visibility = SectionVisibility.ALL_CONNECTED;

	public MeshResult(ChunkBuilder section, int generation) {
		this.section = section;
		this.generation = generation;
	}

	public void addBlockEntity(int x, int y, int z) {
		int need = (this.blockEntityCount + 1) * 3;
		if (need > this.blockEntityPositions.length) {
			int[] grown = new int[Math.max(24, this.blockEntityPositions.length * 2)];
			System.arraycopy(this.blockEntityPositions, 0, grown, 0, this.blockEntityCount * 3);
			this.blockEntityPositions = grown;
		}
		int at = this.blockEntityCount * 3;
		this.blockEntityPositions[at] = x;
		this.blockEntityPositions[at + 1] = y;
		this.blockEntityPositions[at + 2] = z;
		this.blockEntityCount++;
	}
}
