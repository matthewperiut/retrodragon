package com.periut.retrodragon.mixin;

import java.util.ArrayList;
import java.util.List;

import com.periut.retrodragon.render.MeshLock;

import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code Vec3d}'s object pool per-thread.
 *
 * The vanilla pool is a static ArrayList plus a static counter. Fluid rendering allocates from it
 * (via {@code LiquidBlock.getFlow}), so meshing off-thread races the main thread's own use of the
 * pool and throws IndexOutOfBounds almost immediately.
 *
 * Inert until async meshing starts: one volatile read, then the vanilla path.
 */
@Mixin(Vec3d.class)
public class Vec3dCacheMixin {
	@Unique private static final ThreadLocal<List<Vec3d>> retroperf$cache = ThreadLocal.withInitial(ArrayList::new);
	@Unique private static final ThreadLocal<int[]> retroperf$count = ThreadLocal.withInitial(() -> new int[1]);

	@Inject(method = "createCached(DDD)Lnet/minecraft/util/math/Vec3d;", at = @At("HEAD"), cancellable = true)
	private static void retroperf$createCached(double x, double y, double z, CallbackInfoReturnable<Vec3d> cir) {
		if (!MeshLock.active) {
			return;
		}
		List<Vec3d> cache = retroperf$cache.get();
		int[] count = retroperf$count.get();
		if (count[0] >= cache.size()) {
			cache.add(Vec3d.create(0.0, 0.0, 0.0));
		}
		Vec3d vec = cache.get(count[0]++);
		vec.x = x;
		vec.y = y;
		vec.z = z;
		cir.setReturnValue(vec);
	}

	@Inject(method = "resetCacheCount()V", at = @At("HEAD"), cancellable = true)
	private static void retroperf$resetCacheCount(CallbackInfo ci) {
		if (MeshLock.active) {
			retroperf$count.get()[0] = 0;
			ci.cancel();
		}
	}

	@Inject(method = "clearCache()V", at = @At("HEAD"), cancellable = true)
	private static void retroperf$clearCache(CallbackInfo ci) {
		if (MeshLock.active) {
			retroperf$cache.get().clear();
			retroperf$count.get()[0] = 0;
			ci.cancel();
		}
	}
}
