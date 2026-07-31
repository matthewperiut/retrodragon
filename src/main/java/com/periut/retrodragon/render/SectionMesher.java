package com.periut.retrodragon.render;

import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.util.math.Vec3d;

/**
 * The block-iteration half of a chunk build, runnable on any thread.
 *
 * This is a faithful copy of vanilla's {@code ChunkBuilder.rebuild} loop -- same two-layer pass, same
 * early-out when no block belongs to the other layer, same "no blocks found means the layer stays
 * empty" rule -- because that loop decides what geometry exists and any deviation is an appearance
 * bug. Only the destination changed: a {@link VertexSink} instead of a display list.
 */
public final class SectionMesher {

	/**
	 * One reusable sink per thread. A fresh VertexSink carries a 128 KB int[], and with a few
	 * sections meshed per frame during movement that allocation alone was tens of MB/s of garbage.
	 */
	private static final ThreadLocal<VertexSink> SINK = ThreadLocal.withInitial(VertexSink::new);

	/**
	 * Per-thread stand-in for beta's global {@code Chunk.hasSkyLight}, fed by {@code
	 * ChunkSkyLightMixin}. See that class for why the static could not stay shared.
	 */
	private static final ThreadLocal<boolean[]> SKY_LIGHT = ThreadLocal.withInitial(() -> new boolean[1]);

	private SectionMesher() {
	}

	/** Called from {@code ChunkSkyLightMixin} for every sky-lit block this thread reads. */
	public static void markSkyLight() {
		SKY_LIGHT.get()[0] = true;
	}

	/** Reads this thread's flag and clears it, which is vanilla's clear-walk-read in one call. */
	private static boolean takeSkyLight() {
		boolean[] flag = SKY_LIGHT.get();
		boolean seen = flag[0];
		flag[0] = false;
		return seen;
	}

	public static MeshResult mesh(MeshJob job) {
		MeshResult result = new MeshResult(job.section, job.generation);
		VertexSink sink = SINK.get();

		// Per-section reset of this thread's Vec3d pool. Fluid rendering allocates from it and
		// nothing else resets a worker's pool, so it would grow without bound.
		Vec3d.resetCacheCount();

		int maxX = job.minX + job.sizeX;
		int maxY = job.minY + job.sizeY;
		int maxZ = job.minZ + job.sizeZ;

		takeSkyLight();
		BlockRenderManager renderManager = new BlockRenderManager(job.region);
		// Opacity grid for the occlusion BFS, filled during the layer-0 walk (which visits every
		// cell regardless of which layer the block belongs to).
		boolean[] opaque = new boolean[SectionVisibility.SIZE * SectionVisibility.SIZE * SectionVisibility.SIZE];

		for (int layer = 0; layer < 2; layer++) {
			boolean otherLayerPending = false;
			boolean rendered = false;
			boolean started = false;

			for (int by = job.minY; by < maxY; by++) {
				for (int bz = job.minZ; bz < maxZ; bz++) {
					for (int bx = job.minX; bx < maxX; bx++) {
						int id = job.region.getBlockId(bx, by, bz);
						if (layer == 0 && id > 0 && Block.BLOCKS_OPAQUE[id]) {
							opaque[((by - job.minY) * SectionVisibility.SIZE + (bz - job.minZ))
								* SectionVisibility.SIZE + (bx - job.minX)] = true;
						}
						if (id <= 0) {
							continue;
						}

						if (!started) {
							started = true;
							sink.begin(-job.minX, -job.minY, -job.minZ,
								ChunkGeometry.SEAM_SCALE, job.biasX, job.biasY, job.biasZ);
							Capture.begin(sink);
						}

						if (layer == 0 && Block.BLOCKS_WITH_ENTITY[id]) {
							result.addBlockEntity(bx, by, bz);
						}

						Block block = Block.BLOCKS[id];
						if (block.getRenderLayer() != layer) {
							otherLayerPending = true;
						} else {
							// Beta mutates the shared Block singleton's bounds while rendering it.
							synchronized (MeshLock.BLOCK_BOUNDS) {
								rendered |= renderManager.render(block, bx, by, bz);
								// A content API may emit its geometry by writing the Tessellator's
								// int[] directly rather than by calling vertex(), which the capture
								// cannot see. That writer is pointed at THIS THREAD's Tessellator for
								// the duration of a capture (see MeshTessellator), so draining it here
								// touches nothing another thread can be looking at.
								rendered |= ((RetroTessellator) (Object) MeshTessellator.get())
									.retroperf$drainInto(sink);
							}
						}
					}
				}
			}

			if (started) {
				Capture.end();
				result.layers[layer] = sink.copyOut();
			} else {
				rendered = false;
				result.layers[layer] = ChunkGeometry.NO_VERTICES;
			}

			if (rendered) {
				result.layerEmpty[layer] = false;
			}

			if (!otherLayerPending) {
				break;
			}
		}

		for (int layer = 0; layer < 2; layer++) {
			if (result.layers[layer] == null) {
				result.layers[layer] = ChunkGeometry.NO_VERTICES;
			}
		}

		result.hasSkyLight = takeSkyLight();
		result.visibility = SectionVisibility.compute(opaque);
		return result;
	}
}
