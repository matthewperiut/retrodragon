package com.periut.retrodragon.mixin;

import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.world.BlockView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the interior faces beta emits between two stacked cacti.
 *
 * {@code renderCactus} insets the four sides by 1/16 with a tessellator translate but leaves the top
 * and bottom faces at full block size, and its visibility test is {@code isSideVisible}, which only
 * asks whether the neighbour is an opaque cube. Cactus is not one, so at every joint in a column BOTH
 * blocks emit a face: the lower block's top and the upper block's bottom, exactly coplanar, sticking
 * a pixel out past the silhouette of the sides that surround them.
 *
 * Those quads are inside the model and can never be seen honestly, but they are not free:
 *
 *  - they are coincident, so they z-fight, and MSAA resolves the fight per sample rather than per
 *    pixel -- the coin flip becomes a visible average;
 *  - seen at a grazing angle they are a sub-pixel sliver with an exploding eye-space derivative, which
 *    saturates the terrain shader's LOD and blows its RGSS tap footprint past the tile, so all four
 *    taps clamp to the sprite's corners and the sliver reads as a flat, too-dark line.
 *
 * The second one is why the seam shows up specifically with rotated-grid supersampling on. The tap
 * footprint is separately bounded in terrain.fsh/terrain.wgsl, but the real fix is not to build the
 * geometry: no interior quad, nothing to z-fight, nothing to sample at a grazing angle, and two fewer
 * quads per joint in every cactus column.
 *
 * Scoped to the top and bottom faces against the SAME block id, so the exposed top of a column and the
 * face against the ground are untouched. {@code skipFaceCulling} (the inventory/item path) short-
 * circuits before {@code isSideVisible} is ever called, so held and dropped cacti are unaffected.
 */
@Mixin(BlockRenderManager.class)
public class BlockRendererMixin {

	@Redirect(
		method = "renderCactus(Lnet/minecraft/block/Block;IIIFFF)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/Block;isSideVisible(Lnet/minecraft/world/BlockView;IIII)Z"))
	private boolean retroperf$hideCactusJoint(Block block, BlockView view, int x, int y, int z, int side) {
		// side 0 is -Y and 1 is +Y; x/y/z are already the NEIGHBOUR's coordinates.
		if ((side == 0 || side == 1) && view.getBlockId(x, y, z) == block.id) {
			return false;
		}
		return block.isSideVisible(view, x, y, z, side);
	}
}
