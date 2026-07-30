package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.Capture;

import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import net.minecraft.world.biome.source.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives mesh workers their own BiomeSource.
 *
 * {@code BiomeSource} keeps its lookup results in shared scratch arrays that callers read *after*
 * the call returns. The main thread uses it every frame for sky colour, so a worker asking for a
 * grass or foliage tint races it and gets a garbage temperature -- wrong block colours, intermittently.
 * A per-thread instance is deterministic from the world seed, so it produces identical results.
 *
 * FixedBiomeSource (nether, skylands) writes constants and is safe to share, so it is left alone.
 */
@Mixin(WorldRegion.class)
public class WorldRegionBiomeMixin {
	@Shadow private World world;

	@Unique private static final ThreadLocal<BiomeSource> retroperf$source = new ThreadLocal<>();

	@Inject(method = "getBiomeSource()Lnet/minecraft/world/biome/source/BiomeSource;",
		at = @At("HEAD"), cancellable = true)
	private void retroperf$workerBiomeSource(CallbackInfoReturnable<BiomeSource> cir) {
		if (Capture.isRenderThread()) {
			return;
		}
		if (this.world.getBiomeSource().getClass() != BiomeSource.class) {
			return;
		}
		BiomeSource mine = retroperf$source.get();
		if (mine == null) {
			mine = new BiomeSource(this.world);
			retroperf$source.set(mine);
		}
		cir.setReturnValue(mine);
	}
}
