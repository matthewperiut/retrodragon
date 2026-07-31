package com.periut.retrodragon.mixin;

import java.util.Comparator;
import java.util.List;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.render.MeshScheduler;
import com.periut.retrodragon.render.RetroSection;
import com.periut.retrodragon.render.SectionDrawer;

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
