package com.periut.retrodragon.mixin;

import java.util.Comparator;
import java.util.List;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.render.MeshScheduler;
import com.periut.retrodragon.render.RetroSection;
import com.periut.retrodragon.render.SectionDrawer;
import com.periut.retrodragon.render.TerrainLight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes chunk drawing to {@link SectionDrawer}; the F3 counters are kept accurate. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Shadow private Minecraft client;
	@Shadow private ChunkBuilder[] sortedChunks;
	@Shadow private boolean occlusion;
	@Shadow private List<ChunkBuilder> dirtyChunks;
	@Shadow private int chunkCount;
	@Shadow private int invisibleChunkCount;
	@Shadow private int occludedChunkCount;
	@Shadow private int compiledChunkCount;
	@Shadow private int emptyChunkCount;

	@Inject(method = "renderChunks(IIID)I", at = @At("HEAD"), cancellable = true)
	private void retroperf$renderChunks(int from, int to, int layer, double tickDelta, CallbackInfoReturnable<Integer> cir) {
		if (!Config.TERRAIN) {
			return;
		}
		if (layer == 0) {
			for (int i = from; i < to; i++) {
				ChunkBuilder section = this.sortedChunks[i];
				this.chunkCount++;
				if (section.renderLayerEmpty[layer]) {
					this.emptyChunkCount++;
				} else if (!section.inFrustum) {
					this.invisibleChunkCount++;
				} else if (this.occlusion && !section.unoccluded) {
					this.occludedChunkCount++;
				} else {
					this.compiledChunkCount++;
				}
			}
		}

		LivingEntity camera = this.client.camera;
		double camX = camera.lastTickX + (camera.x - camera.lastTickX) * tickDelta;
		double camY = camera.lastTickY + (camera.y - camera.lastTickY) * tickDelta;
		double camZ = camera.lastTickZ + (camera.z - camera.lastTickZ) * tickDelta;

		// Immediately before the draw, in the same callback -- see WorldRendererPhaseMixin for why it
		// cannot live in a second injection at this same point.
		com.periut.retrodragon.api.ShaderApi.setPhase(layer == 0
			? com.periut.retrodragon.api.DrawPhase.TERRAIN_OPAQUE
			: com.periut.retrodragon.api.DrawPhase.TERRAIN_TRANSLUCENT);
		com.periut.retrodragon.render.WebGpuFrame.notifyWorldFrame((float) tickDelta);
		cir.setReturnValue(SectionDrawer.draw(this.sortedChunks, from, to, layer, this.occlusion, camX, camY, camZ));
	}

	/**
	 * Replaces vanilla's build budget with an async pump.
	 *
	 * Vanilla meshed ~4 sections per call on the render thread and GameRenderer spun this in a
	 * time-budgeted loop. We instead apply whatever the workers finished, queue more work nearest
	 * first, and always report "done" so GameRenderer never spins -- pending sections simply appear
	 * a frame or two later, exactly as vanilla's own backlog did.
	 */
	@Inject(method = "compileChunks(Lnet/minecraft/entity/LivingEntity;Z)Z", at = @At("HEAD"), cancellable = true)
	private void retroperf$compileChunks(LivingEntity camera, boolean force, CallbackInfoReturnable<Boolean> cir) {
		if (!Config.TERRAIN || !MeshScheduler.ASYNC) {
			return;
		}
		cir.setReturnValue(true);
		MeshScheduler.drain();
		if (!MeshScheduler.start() || this.dirtyChunks.isEmpty() || !MeshScheduler.canSubmit()) {
			return;
		}

		this.dirtyChunks.sort(Comparator.comparingDouble(section -> section.squaredDistanceTo(camera)));
		// Compact in place: removing submitted entries one at a time from an ArrayList is O(n^2),
		// and during movement this list runs to the hundreds.
		int write = 0;
		for (int read = 0; read < this.dirtyChunks.size(); read++) {
			ChunkBuilder section = this.dirtyChunks.get(read);
			if (!((RetroSection) section).retroperf$isMeshing() && MeshScheduler.canSubmit()) {
				section.rebuild();
				section.dirty = false;
			} else {
				this.dirtyChunks.set(write++, section);
			}
		}
		if (write < this.dirtyChunks.size()) {
			this.dirtyChunks.subList(write, this.dirtyChunks.size()).clear();
		}
	}

	/**
	 * The rebuild storm this whole feature exists to remove.
	 *
	 * <p>Vanilla's {@code notifyAmbientDarknessChanged} marks EVERY sky-lit section dirty, because the
	 * time of day is baked into their vertex colours and the only way to change it is to build them
	 * again. {@code World.tick} fires it whenever {@code ambientDarkness} steps -- twelve values, up
	 * and down twice a day cycle, plus every rain and thunder transition -- so the visible world is
	 * re-meshed a couple of dozen times a day for a change that is one number.
	 *
	 * <p>With {@link TerrainLight} on, that number is a uniform and the sections are already right.
	 * Cancelled rather than left to run: the sections it dirties would be rebuilt into identical
	 * geometry, which is the same stutter for no difference at all.
	 *
	 * <p>Not cancelled on the GL backend or under StationAPI, where the light is still baked and the
	 * sweep is still the only thing that updates it.
	 */
	/**
	 * Rebuilds the world when the lightmap turns out not to be available after all.
	 *
	 * <p>Only reachable on the GL backend, and only when its terrain program fails to compile or
	 * link: fixed function has no vertex stage, so every section meshed so far is carrying its light
	 * in a pair nothing will read and would draw at its unlit tint. See {@link TerrainLight#fallBack}.
	 *
	 * <p>Here rather than at the point the failure is noticed, which is inside the section draw loop
	 * -- iterating the very meshes {@code reload()} frees.
	 */
	@Inject(method = "compileChunks(Lnet/minecraft/entity/LivingEntity;Z)Z", at = @At("HEAD"))
	private void retrodragon$rebuildWithoutLightmap(LivingEntity camera, boolean partial,
			CallbackInfoReturnable<Boolean> cir) {
		if (TerrainLight.takeReload()) {
			((WorldRenderer) (Object) this).reload();
		}
	}

	@Inject(method = "notifyAmbientDarknessChanged()V", at = @At("HEAD"), cancellable = true)
	private void retrodragon$ambientDarknessIsAUniform(CallbackInfo ci) {
		if (TerrainLight.enabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderLastChunks(ID)V", at = @At("HEAD"), cancellable = true)
	private void retroperf$renderLastChunks(int layer, double tickDelta, CallbackInfo ci) {
		if (!Config.TERRAIN) {
			return;
		}
		ci.cancel();
		com.periut.retrodragon.api.ShaderApi.setPhase(layer == 0
			? com.periut.retrodragon.api.DrawPhase.TERRAIN_OPAQUE
			: com.periut.retrodragon.api.DrawPhase.TERRAIN_TRANSLUCENT);
		SectionDrawer.replayLast();
	}
}
