package com.periut.retrodragon.render;

import net.minecraft.client.render.Tessellator;

/**
 * A Tessellator per meshing thread, so a content API's direct buffer writes never land in the one
 * the render thread is drawing with.
 *
 * <p>{@code Tessellator.INSTANCE} is a single object for the whole game. StationAPI's baked-model
 * path writes vertices into its {@code int[]} rather than calling {@code vertex()}, which makes that
 * object shared mutable state the moment meshing moves off the render thread: a worker draining it
 * rewinds {@code bufferPosition} under a batch the render thread is still filling, and beta's quad
 * split then reads {@code bufferPosition - 24} at {@code bufferPosition == 8} and dies on an index
 * of -16. Locking cannot fix it -- the same writer is reached from item and GUI drawing, which never
 * goes through {@code BlockRenderManager.render} and so is not under the block-bounds monitor.
 *
 * <p>So the writer is handed a different Tessellator instead, by
 * {@code com.periut.retrodragon.mixin.stapi.BakedModelRendererMixin}, for exactly as long as a
 * capture is active. Everything else about it is ordinary: {@code TessellatorMixin} applies to the
 * class, so {@code start}/{@code draw} are swallowed and {@code vertex()} still routes to the sink.
 *
 * <p>The constructor only allocates -- an {@code int[]} and a direct {@code ByteBuffer} -- so it is
 * safe off-thread and off the GL context.
 */
public final class MeshTessellator {
	/**
	 * Ints per instance. Small on purpose: {@link SectionMesher} drains after every block, so this
	 * only ever holds one block's quads, and StationAPI grows it itself if that is ever wrong.
	 */
	private static final int CAPACITY = 64 * 1024;

	private static final ThreadLocal<Tessellator> PER_THREAD =
		ThreadLocal.withInitial(() -> new Tessellator(CAPACITY));

	private MeshTessellator() {
	}

	/** The calling thread's meshing Tessellator. */
	public static Tessellator get() {
		return PER_THREAD.get();
	}
}
