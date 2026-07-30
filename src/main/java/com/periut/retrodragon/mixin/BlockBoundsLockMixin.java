package com.periut.retrodragon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.periut.retrodragon.render.MeshLock;

import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Main-thread half of the block-bounds lock.
 *
 * Rendering a block mutates the shared {@code Block.BLOCKS[id]} singleton's bounding box, and the
 * main thread renders blocks too (items, inventories, falling blocks). Without this, a worker's
 * bounds writes land in the middle of a main-thread render and shapes flicker.
 *
 * One volatile read while meshing is synchronous, so the vanilla path is unaffected.
 */
@Mixin(BlockRenderManager.class)
public class BlockBoundsLockMixin {

	@WrapMethod(method = "render(Lnet/minecraft/block/Block;III)Z")
	private boolean retroperf$lockBounds(Block block, int x, int y, int z, Operation<Boolean> original) {
		if (!MeshLock.active) {
			return original.call(block, x, y, z);
		}
		synchronized (MeshLock.BLOCK_BOUNDS) {
			return original.call(block, x, y, z);
		}
	}
}
