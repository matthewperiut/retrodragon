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
 *
 * <p>The loop runs TWICE when {@link TerrainLight} is on, over the same snapshot, with beta's
 * luminance lookup answering differently each time: once for the colour a block has with no light in
 * it, once for the light itself. See that class for why one pass cannot produce both.
 */
public final class SectionMesher {

	/**
	 * One reusable sink per thread. A fresh VertexSink carries a 128 KB int[], and with a few
	 * sections meshed per frame during movement that allocation alone was tens of MB/s of garbage.
	 */
	private static final ThreadLocal<VertexSink> SINK = ThreadLocal.withInitial(VertexSink::new);

	/**
	 * Per-thread stand-in for beta's global {@code Chunk.hasSkyLight}, fed by {@code
	 * WorldRegionLightMixin}. See that class for why the static could not stay shared, and why the
	 * flag is collected at the call rather than inside {@code Chunk.getLight}.
	 */
	private static final ThreadLocal<boolean[]> SKY_LIGHT = ThreadLocal.withInitial(() -> new boolean[1]);

	private static final float[] NO_SUMS = new float[0];

	private SectionMesher() {
	}

	/** Called from {@code WorldRegionLightMixin} for every sky-lit block this thread reads. */
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
		if (!TerrainLight.enabled()) {
			walk(job, result, TerrainLight.Pass.NONE);
			return result;
		}

		// The colour every vertex came out with, per layer, under each of the three lightings. The
		// later walks get a throwaway result: they produce the same geometry over the same snapshot,
		// so the block entities, visibility and sky-light flag the first walk recorded still stand.
		float[][] tint = walk(job, result, TerrainLight.Pass.TINT);
		float[][] daylight = walk(job, new MeshResult(job.section, job.generation),
			TerrainLight.Pass.DAYLIGHT);
		// A section with no block light in it has the same floor everywhere -- the brightness table's
		// own minimum -- and a whole walk is a lot to spend rediscovering that. Outdoors, which is
		// most of what a player is looking at, this is the common case.
		float[][] floor = hasBlockLight(job)
			? walk(job, new MeshResult(job.section, job.generation), TerrainLight.Pass.BLOCK_FLOOR)
			: null;
		applyLight(result, tint, daylight, floor);
		return result;
	}

	/**
	 * Whether anything in the section, or the ring of blocks around it a renderer samples, is lit by
	 * something other than the sky.
	 *
	 * <p>Asked of the light data rather than of the blocks: an emitter three chunks away still leaves
	 * block light in these cells, and a scan for torches inside the region would miss it. One nibble
	 * read per cell through beta's own lookup, and it stops at the first one it finds.
	 */
	private static boolean hasBlockLight(MeshJob job) {
		TerrainLight.pass(TerrainLight.Pass.BLOCK_FLOOR);
		try {
			for (int y = job.minY - 1; y <= job.minY + job.sizeY; y++) {
				for (int z = job.minZ - 1; z <= job.minZ + job.sizeZ; z++) {
					for (int x = job.minX - 1; x <= job.minX + job.sizeX; x++) {
						if (job.region.getNaturalBrightness(x, y, z, 0) > TerrainLight.MIN) {
							return true;
						}
					}
				}
			}
			return false;
		} finally {
			TerrainLight.pass(TerrainLight.Pass.NONE);
		}
	}

	/**
	 * One walk of the section, in the given lighting pass.
	 *
	 * @return the per-layer float colour sums {@link TerrainLight} needs, or null outside a pass
	 */
	private static float[][] walk(MeshJob job, MeshResult result, TerrainLight.Pass pass) {
		VertexSink sink = SINK.get();
		float[][] sums = pass == TerrainLight.Pass.NONE ? null : new float[2][];
		// A light walk is here for its colours alone. Everything else about the section -- what
		// geometry it holds, which block entities are in it, whether it sees the sky, what it can see
		// through -- was settled by the first walk over the same snapshot and is not recomputed.
		boolean geometry = pass == TerrainLight.Pass.NONE || pass == TerrainLight.Pass.TINT;

		// Per-walk reset of this thread's Vec3d pool. Fluid rendering allocates from it and nothing
		// else resets a worker's pool, so it would grow without bound.
		Vec3d.resetCacheCount();

		int maxX = job.minX + job.sizeX;
		int maxY = job.minY + job.sizeY;
		int maxZ = job.minZ + job.sizeZ;

		takeSkyLight();
		BlockRenderManager renderManager = new BlockRenderManager(job.region);
		// Opacity grid for the occlusion BFS, filled during the layer-0 walk (which visits every
		// cell regardless of which layer the block belongs to). Not allocated for the light walk,
		// which does not recompute it.
		boolean[] opaque = geometry
			? new boolean[SectionVisibility.SIZE * SectionVisibility.SIZE * SectionVisibility.SIZE]
			: null;

		// Cleared in a finally, not at the end: a block renderer that throws would otherwise leave
		// this thread answering every later lighting query -- entities, the sky, another section --
		// with packed light rather than a luminance.
		TerrainLight.pass(pass);
		try {
			for (int layer = 0; layer < 2; layer++) {
				boolean otherLayerPending = false;
				boolean rendered = false;
				boolean started = false;

				for (int by = job.minY; by < maxY; by++) {
					for (int bz = job.minZ; bz < maxZ; bz++) {
						for (int bx = job.minX; bx < maxX; bx++) {
							int id = job.region.getBlockId(bx, by, bz);
							if (geometry && layer == 0 && id > 0 && Block.BLOCKS_OPAQUE[id]) {
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

							if (geometry && layer == 0 && Block.BLOCKS_WITH_ENTITY[id]) {
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
					if (geometry) {
						result.layers[layer] = sink.copyOut();
					}
					if (sums != null) {
						sums[layer] = sink.copyOutColorSums();
					}
				} else {
					rendered = false;
					result.layers[layer] = ChunkGeometry.NO_VERTICES;
					if (sums != null) {
						sums[layer] = NO_SUMS;
					}
				}

				if (rendered) {
					result.layerEmpty[layer] = false;
				}

				if (!otherLayerPending) {
					break;
				}
			}
		} finally {
			TerrainLight.pass(TerrainLight.Pass.NONE);
		}

		for (int layer = 0; layer < 2; layer++) {
			if (result.layers[layer] == null) {
				result.layers[layer] = ChunkGeometry.NO_VERTICES;
			}
			if (sums != null && sums[layer] == null) {
				sums[layer] = NO_SUMS;
			}
		}

		if (geometry) {
			result.hasSkyLight = takeSkyLight();
			result.visibility = SectionVisibility.compute(opaque);
		}
		return sums;
	}

	/**
	 * Fills in each vertex's light pair from the walks.
	 *
	 * <p>The walks produce the same geometry in the same order -- same snapshot, same loop, same
	 * renderer -- so a vertex is identified by its index and nothing has to be matched up. A count
	 * that disagrees means that assumption has broken somewhere (a renderer whose output depends on
	 * the luminance it was handed), and the layer is left fully lit rather than guessed at: too
	 * bright at night is a wrong colour, mismatched indices are the wrong colour on the wrong face.
	 *
	 * <p>Only the light half of the word is written. The other byte is the stitched-atlas sprite
	 * size, which the first walk already put there.
	 *
	 * @param floor null when the section has no block light, where the floor is the table's minimum
	 */
	private static void applyLight(MeshResult result, float[][] tint, float[][] daylight,
			float[][] floor) {
		boolean compact = TerrainVertex.compact();
		int strideInts = TerrainVertex.strideInts(compact);
		int slot = TerrainVertex.wordSlot(compact);
		for (int layer = 0; layer < 2; layer++) {
			int[] vertices = result.layers[layer];
			int count = vertices.length / strideInts;
			float[] a = tint[layer];
			float[] b = daylight[layer];
			float[] c = floor == null ? null : floor[layer];
			boolean paired = a.length == count && b.length == count
				&& (c == null || c.length == count);
			for (int i = 0; i < count; i++) {
				int word = paired
					? TerrainLight.word(a[i], b[i], c == null ? a[i] * TerrainLight.MIN : c[i])
					: TerrainLight.UNLIT_WORD;
				int at = i * strideInts + slot;
				vertices[at] = vertices[at] & ~TerrainLight.WORD_MASK | word;
			}
		}
	}
}
