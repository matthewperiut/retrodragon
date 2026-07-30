package com.periut.retrodragon.render;

/**
 * Gate for Tessellator capture. While a sink is installed for the calling thread,
 * {@code TessellatorMixin} routes beta's Tessellator calls here instead of to GL -- that is how the
 * 3400-line vanilla BlockRenderManager meshes blocks into our buffers without being reimplemented.
 *
 * The render thread and mesh workers are kept apart by thread identity rather than by an
 * unconditional {@code ThreadLocal.get()}. The render thread runs tens of thousands of uncaptured
 * Tessellator calls per frame for GUI and entities; paying a ThreadLocal lookup on each of those
 * would cost more than async meshing saves. Identity is two reference compares.
 *
 * Before {@link #markRenderThread} runs there are no workers yet, so every thread correctly falls
 * through to the render-thread sink.
 */
public final class Capture {
	private static Thread renderThread;
	private static VertexSink renderSink;
	private static final ThreadLocal<VertexSink> WORKER_SINK = new ThreadLocal<>();

	private Capture() {
	}

	/** Called on the render thread before any worker is started. */
	public static void markRenderThread() {
		renderThread = Thread.currentThread();
	}

	public static boolean isRenderThread() {
		return renderThread == null || Thread.currentThread() == renderThread;
	}

	/** Null on the normal path, so uncaptured Tessellator use is unaffected. */
	public static VertexSink sink() {
		Thread current = Thread.currentThread();
		if (renderThread == null || current == renderThread) {
			return renderSink;
		}
		return WORKER_SINK.get();
	}

	public static void begin(VertexSink sink) {
		if (isRenderThread()) {
			renderSink = sink;
		} else {
			WORKER_SINK.set(sink);
		}
	}

	public static void end() {
		if (isRenderThread()) {
			renderSink = null;
		} else {
			WORKER_SINK.remove();
		}
	}
}
