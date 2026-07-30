package com.periut.retrodragon.render;

import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.world.WorldRegion;

/**
 * One section's meshing work. The {@link WorldRegion} is snapshotted on the render thread because
 * building it calls {@code World.getChunk}, which generates chunks and runs lighting -- that must
 * never happen off-thread. After construction the region only reads cached Chunk references, which
 * is side-effect free.
 */
public final class MeshJob {
	public final ChunkBuilder section;
	public final WorldRegion region;
	public final int minX, minY, minZ;
	public final int sizeX, sizeY, sizeZ;
	public final float biasX, biasY, biasZ;
	public final int generation;

	public MeshJob(ChunkBuilder section, WorldRegion region, int minX, int minY, int minZ,
			int sizeX, int sizeY, int sizeZ, float biasX, float biasY, float biasZ, int generation) {
		this.section = section;
		this.region = region;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.biasX = biasX;
		this.biasY = biasY;
		this.biasZ = biasZ;
		this.generation = generation;
	}
}
