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
	/**
	 * A rebuild was requested while a mesh was already in flight, so the result of that mesh will be
	 * stale the moment it lands and the section has to be built again.
	 *
	 * <p>This is what stops a chunk being left at the wrong time of day.
	 * {@code WorldRenderer.compileChunks} clears {@code dirty} UNCONDITIONALLY right after calling
	 * {@code rebuild()}:
	 *
	 * <pre>chunk.rebuild(); chunk.dirty = false;</pre>
	 *
	 * <p>It does not ask whether the rebuild happened. Vanilla's could not fail to, but ours declines
	 * whenever the section already has a mesh in flight, and the clear lands anyway. The request is
	 * swallowed: the section comes out clean, off {@code dirtyChunks}, holding geometry that was
	 * snapshotted before whatever prompted the request.
	 *
	 * <p>Which matters most for sky light, because {@code notifyAmbientDarknessChanged} is a ONE-SHOT
	 * sweep -- it walks every section once per ambient step and skips any that is already dirty. Lose
	 * that one call and nothing comes back for it. Dusk moves the ambient level through a series of
	 * steps, so a section that happens to be meshing during one of them keeps the light level from
	 * the step before, and once night settles there are no more sweeps to correct it. It stays a
	 * visibly different brightness from its neighbours until an unrelated block change nearby
	 * dirties it again.
	 *
	 * <p>Async meshing is what makes it reachable: vanilla built sections inline, so a rebuild request
	 * could never arrive while one was outstanding.
	 */
	@Unique private boolean retroperf$restale;
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
		// A rebuild was asked for while this mesh was in flight, so the geometry that just arrived is
		// already out of date. Ask again, once, now that the section is free to accept one.
		boolean restale = this.retroperf$restale;
		this.retroperf$restale = false;
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
			this.retroperf$scheduleRebuild();
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

		if (restale) {
			this.retroperf$scheduleRebuild();
		}
	}

	/**
	 * Puts this section back on {@code WorldRenderer.dirtyChunks} through the world.
	 *
	 * <p>Through the world, NOT {@code this.dirty = true}. Vanilla only ever rebuilds sections that
	 * are in {@code WorldRenderer.dirtyChunks}, and the only thing that puts one there --
	 * {@code WorldRenderer.markDirty} -- skips a section whose {@code dirty} is already set. Setting
	 * the flag by hand therefore takes the section OUT of the rebuild path permanently: it looks
	 * scheduled to everything that can schedule it, and is never built again. The chunk then keeps
	 * whatever lighting it last had, through every block and sky light update, forever.
	 *
	 * <p>A null world means {@code close()} has run: {@code WorldRenderer.reload()} -- which is what a
	 * video option like Advanced OpenGL triggers -- calls {@code close()} on every section, and
	 * {@code close()} is {@code reset(); this.world = null}. The reset bumps the generation, so an
	 * in-flight mesh lands here with nothing left to schedule against. {@code reload()} builds fresh
	 * sections and marks them dirty itself, so the right answer is to drop the request on the floor.
	 */
	@Unique
	private void retroperf$scheduleRebuild() {
		if (!this.dirty && this.world != null) {
			this.world.setBlocksDirty(this.x, this.y, this.z,
				this.x + this.sizeX - 1, this.y + this.sizeY - 1, this.z + this.sizeZ - 1);
		}
	}

	@Inject(method = "rebuild()V", at = @At("HEAD"), cancellable = true)
	private void retroperf$rebuild(CallbackInfo ci) {
		if (!Config.TERRAIN) {
			return;
		}
		ci.cancel();
		if (!this.dirty) {
			return;
		}
		if (this.retroperf$meshing) {
			// Declining the request, so record it: compileChunks is about to clear `dirty` whether we
			// built anything or not, and without this the request is simply lost. See retroperf$restale.
			this.retroperf$restale = true;
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
