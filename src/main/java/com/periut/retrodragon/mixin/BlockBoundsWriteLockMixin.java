package com.periut.retrodragon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.periut.retrodragon.render.MeshLock;

import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The write half of the block-bounds lock, taken at the one place every bounds write goes through.
 *
 * <h2>Why here rather than at the callers</h2>
 *
 * A mesh worker holds {@link MeshLock#BLOCK_BOUNDS} across a whole {@code BlockRenderManager.render}
 * call ({@code SectionMesher}), because beta renders a non-cube block by writing the shared
 * {@code Block.BLOCKS[id]} singleton's bounds and then reading them back face by face. That span is
 * only safe if NOTHING else writes those fields while it runs -- and plenty did.
 *
 * <p>The previous guards were a list of CALLERS: {@code BlockRenderManager.render} and
 * {@code World.getEntityCollisions}. That list was incomplete, and a list of callers can never be
 * complete, because a mod may call a bounds mutator from anywhere. The unguarded vanilla paths were
 * {@code World.raycast} (via {@code Block.raycast}, whose first instruction is
 * {@code updateBoundingBox}), {@code WorldRenderer.renderBlockOutline}, and {@code ArrowEntity.tick}
 * -- the first two run EVERY FRAME against the block under the crosshair, which is why the symptom
 * was "stairs render wrong, but only sometimes, and only where I am building".
 *
 * <p>{@code setBoundingBox} is a chokepoint instead of a list. Verified against the b1.7.3 jar: it
 * is declared exactly once, nothing overrides it, and it contains the only writes to the six fields
 * outside {@code BlockRenderManager} itself (whose two direct {@code putfield}s are already inside
 * the region {@code BlockBoundsLockMixin} guards). So every mutation in the game routes here --
 * including a modded block's {@code updateBoundingBox}, without the mod having to be known about.
 *
 * <p>The one way out is a mod writing {@code block.minX} directly; the fields are public. Nothing in
 * vanilla does that, and a mod that does would have been racing beta's own physics long before this
 * mod existed.
 *
 * <h2>Why locking the write alone is enough</h2>
 *
 * The worker's write-then-read span is protected because the WORKER holds the monitor for all of it;
 * this only has to stop everyone else from writing underneath it. Main-thread write-then-read spans
 * are a separate concern and stay covered by {@link BlockBoundsLockMixin} and
 * {@code WorldCollisionLockMixin} -- those are still needed, and this does not replace them.
 *
 * <p>Reentrant by construction: the worker calls this thousands of times while already holding the
 * monitor, which is the cheap already-owned path rather than a contended acquire.
 */
@Mixin(Block.class)
public class BlockBoundsWriteLockMixin {

	@WrapMethod(method = "setBoundingBox(FFFFFF)V")
	private void retroperf$lockBoundsWrite(float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ, Operation<Void> original) {
		if (!MeshLock.active) {
			original.call(minX, minY, minZ, maxX, maxY, maxZ);
			return;
		}
		synchronized (MeshLock.BLOCK_BOUNDS) {
			original.call(minX, minY, minZ, maxX, maxY, maxZ);
		}
	}
}
