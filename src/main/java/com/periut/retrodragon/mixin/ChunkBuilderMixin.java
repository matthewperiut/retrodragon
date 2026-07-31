package com.periut.retrodragon.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.render.ChunkGeometry;
import com.periut.retrodragon.render.MeshJob;
import com.periut.retrodragon.render.MeshResult;
import com.periut.retrodragon.render.MeshScheduler;
import com.periut.retrodragon.render.RetroSection;
import com.periut.retrodragon.render.SectionMesh;
import com.periut.retrodragon.render.SectionMesher;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's display-list chunk build with a VBO build, meshed off-thread when possible.
 *
 * The split is: snapshot on the render thread (building a WorldRegion calls {@code World.getChunk},
 * which generates chunks and must not run off-thread), block iteration on a worker, upload and
 * bookkeeping back on the render thread.
 */
@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderMixin implements RetroSection {
	@Shadow public World world;
	@Shadow public int x;
	@Shadow public int y;
	@Shadow public int z;
	@Shadow public int sizeX;
	@Shadow public int sizeY;
	@Shadow public int sizeZ;
	@Shadow public int renderX;
	@Shadow public int renderY;
	@Shadow public int renderZ;
	@Shadow public boolean dirty;
	@Shadow public boolean[] renderLayerEmpty;
	@Shadow public boolean hasSkyLight;
	@Shadow public List<BlockEntity> blockEntities;
	@Shadow private List<BlockEntity> currentBlockEntities;
	@Shadow private boolean built;

	@Unique private final SectionMesh[] retroperf$meshes = { new SectionMesh(), new SectionMesh() };
	/** Bumped whenever the section moves, so an in-flight mesh for the old position is discarded. */
	@Unique private int retroperf$generation;
	@Unique private boolean retroperf$meshing;
	@Unique private long retroperf$visibility = com.periut.retrodragon.render.SectionVisibility.ALL_CONNECTED;

	@Override
	public SectionMesh retroperf$mesh(int layer) {
		return this.retroperf$meshes[layer];
	}

	@Override
	public boolean retroperf$isMeshing() {
		return this.retroperf$meshing;
	}

	@Override
	public long retroperf$visibility() {
		return this.retroperf$visibility;
	}

	@Override
	public void retroperf$freeMeshes() {
		// Null while the vanilla constructor is still running: it calls setPosition -> reset()
		// before mixin field initializers have run.
		if (this.retroperf$meshes == null) {
			return;
		}
		for (SectionMesh mesh : this.retroperf$meshes) {
			mesh.free();
		}
	}

	@Inject(method = "reset()V", at = @At("HEAD"))
	private void retroperf$reset(CallbackInfo ci) {
		this.retroperf$generation++;
		this.retroperf$visibility = com.periut.retrodragon.render.SectionVisibility.ALL_CONNECTED;
		this.retroperf$freeMeshes();
	}

	@Override
	public MeshJob retroperf$snapshot() {
		WorldRegion region = new WorldRegion(this.world,
			this.x - 1, this.y - 1, this.z - 1,
			this.x + this.sizeX + 1, this.y + this.sizeY + 1, this.z + this.sizeZ + 1);
		// Vanilla's centre for the seam scale is (sizeZ/2, sizeY/2, sizeZ/2). The x term really does
		// use sizeZ -- reproduced so geometry matches vanilla exactly (the sizes are equal anyway).
		float shift = ChunkGeometry.SEAM_SCALE - 1.0F;
		return new MeshJob((ChunkBuilder) (Object) this, region,
			this.x, this.y, this.z, this.sizeX, this.sizeY, this.sizeZ,
			this.renderX + this.sizeZ / 2.0F * shift,
			this.renderY + this.sizeY / 2.0F * shift,
			this.renderZ + this.sizeZ / 2.0F * shift,
			this.retroperf$generation);
	}

	@Override
	public void retroperf$applyMesh(MeshResult result) {
		this.retroperf$meshing = false;
		if (result.failed || result.generation != this.retroperf$generation) {
			// Either the worker threw, or the section moved while this was in flight and the geometry
			// is for the old position. Nothing usable arrived, so the section has to be built again.
			//
			// Through the world, NOT `this.dirty = true`. Vanilla only ever rebuilds sections that are
			// in WorldRenderer.dirtyChunks, and the only thing that puts one there --
			// WorldRenderer.markDirty -- skips a section whose `dirty` is already set. Setting the flag
			// by hand therefore takes the section OUT of the rebuild path permanently: it looks
			// scheduled to everything that can schedule it, and is never built again. The chunk then
			// keeps whatever lighting it last had, through every block and sky light update, forever.
			// A null world means close() has run: WorldRenderer.reload() -- which is what a video
			// option like Advanced OpenGL triggers -- calls close() on every section, and close() is
			// `reset(); this.world = null`. The reset bumps the generation, so an in-flight mesh lands
			// here with nothing left to schedule against. reload() builds fresh sections and marks
			// them dirty itself, so the right answer is to drop this result on the floor.
			if (!this.dirty && this.world != null) {
				this.world.setBlocksDirty(this.x, this.y, this.z,
					this.x + this.sizeX - 1, this.y + this.sizeY - 1, this.z + this.sizeZ - 1);
			}
			return;
		}

		ChunkBuilder.chunkUpdates++;
		for (int layer = 0; layer < 2; layer++) {
			this.retroperf$meshes[layer].upload(result.layers[layer]);
			this.renderLayerEmpty[layer] = result.layerEmpty[layer];
		}

		// Block entities are resolved here, not on the worker: this reads the chunk's block-entity
		// map, which the main thread mutates.
		HashSet<BlockEntity> previous = new HashSet<>(this.blockEntities);
		this.blockEntities.clear();
		for (int i = 0; i < result.blockEntityCount; i++) {
			int at = i * 3;
			BlockEntity blockEntity = this.world.getBlockEntity(
				result.blockEntityPositions[at], result.blockEntityPositions[at + 1], result.blockEntityPositions[at + 2]);
			if (blockEntity != null && BlockEntityRenderDispatcher.INSTANCE.hasRenderer(blockEntity)) {
				this.blockEntities.add(blockEntity);
			}
		}
		List<BlockEntity> added = new ArrayList<>(this.blockEntities);
		added.removeAll(previous);
		this.currentBlockEntities.addAll(added);
		previous.removeAll(this.blockEntities);
		this.currentBlockEntities.removeAll(previous);

		this.retroperf$visibility = result.visibility;
		this.hasSkyLight = result.hasSkyLight;
		this.built = true;
	}

	@Inject(method = "rebuild()V", at = @At("HEAD"), cancellable = true)
	private void retroperf$rebuild(CallbackInfo ci) {
		if (!Config.TERRAIN) {
			return;
		}
		ci.cancel();
		if (!this.dirty || this.retroperf$meshing) {
			return;
		}

		long snapshotStart = System.nanoTime();
		MeshJob job = this.retroperf$snapshot();
		MeshScheduler.noteSnapshot(System.nanoTime() - snapshotStart);
		if (MeshScheduler.start() && MeshScheduler.canSubmit()) {
			this.retroperf$meshing = true;
			MeshScheduler.submit(job);
		} else {
			this.retroperf$applyMesh(SectionMesher.mesh(job));
		}
	}
}
