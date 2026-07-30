package com.periut.retrodragon.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.periut.retrodragon.render.MeshLock;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Physics half of the block-bounds lock: collision gathering calls {@code updateBoundingBox} on
 * every block in the swept volume, reading the same shared singletons a mesh worker is writing.
 */
@Mixin(World.class)
public class WorldCollisionLockMixin {

	@WrapMethod(method = "getEntityCollisions(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;)Ljava/util/List;")
	private List<Box> retroperf$lockCollisions(Entity entity, Box box, Operation<List<Box>> original) {
		if (!MeshLock.active) {
			return original.call(entity, box);
		}
		synchronized (MeshLock.BLOCK_BOUNDS) {
			return original.call(entity, box);
		}
	}
}
