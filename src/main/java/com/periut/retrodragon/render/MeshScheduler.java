package com.periut.retrodragon.render;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.periut.retrodragon.RetroDragon;

/**
 * Moves chunk meshing off the render thread.
 *
 * The render thread snapshots a {@link MeshJob} (which must build the WorldRegion, since that
 * generates chunks), workers do the block iteration, and the render thread applies results by
 * uploading VBOs. Everything that touches GL or the world's mutable structures stays on the render
 * thread; only the pure block walk moves.
 */
public final class MeshScheduler {
	public static final boolean ASYNC = !"false".equals(System.getProperty("retroperf.asyncMesh"));
	private static final int THREADS = Math.max(1, Integer.getInteger("retroperf.meshThreads", 2));
	/** Cap on queued work so a teleport cannot enqueue thousands of stale sections. */
	private static final int MAX_IN_FLIGHT = THREADS * 8;
	/** Max VBO uploads applied per frame; the rest wait. */
	private static final int UPLOAD_BUDGET = Math.max(1, Integer.getInteger("retroperf.uploadBudget", 8));

	private static final BlockingQueue<MeshJob> QUEUE = new LinkedBlockingQueue<>();
	private static final ConcurrentLinkedQueue<MeshResult> DONE = new ConcurrentLinkedQueue<>();

	private static final boolean DIAG = Boolean.getBoolean("retroperf.diag");

	/** Render thread only. */
	private static int inFlight;
	private static boolean started;
	private static long uploadNanos, snapshotNanos;
	private static int uploadCount, snapshotCount, diagFrames;

	private MeshScheduler() {
	}

	/** Called from the render thread before any worker exists. */
	public static boolean start() {
		if (!ASYNC) {
			return false;
		}
		if (!started) {
			started = true;
			Capture.markRenderThread();
			MeshLock.active = true;
			for (int i = 0; i < THREADS; i++) {
				Thread worker = new Thread(MeshScheduler::workerLoop, "RetroDragon mesh " + i);
				worker.setDaemon(true);
				worker.setPriority(Thread.NORM_PRIORITY - 1);
				worker.start();
			}
			RetroDragon.detail("async meshing on, {} worker(s)", THREADS);
		}
		return true;
	}

	public static boolean canSubmit() {
		return inFlight < MAX_IN_FLIGHT;
	}

	public static void submit(MeshJob job) {
		inFlight++;
		QUEUE.add(job);
	}

	/**
	 * Render thread: apply finished meshes, up to a per-frame budget.
	 *
	 * Uploading is GL work and has to happen here, so draining everything at once just moves the
	 * stall rather than removing it -- workers finishing a burst would produce a burst of
	 * glBufferData in one frame. The leftovers stay queued for the next frame.
	 */
	public static void drain() {
		long started = DIAG ? System.nanoTime() : 0L;
		int budget = UPLOAD_BUDGET;
		int applied = 0;
		MeshResult result;
		while (budget-- > 0 && (result = DONE.poll()) != null) {
			inFlight--;
			((RetroSection) result.section).retroperf$applyMesh(result);
			applied++;
		}
		if (DIAG) {
			uploadNanos += System.nanoTime() - started;
			uploadCount += applied;
			report();
		}
	}

	/** Render thread: cost of building a WorldRegion snapshot, which is unavoidably on-thread. */
	public static void noteSnapshot(long nanos) {
		if (DIAG) {
			snapshotNanos += nanos;
			snapshotCount++;
		}
	}

	private static void report() {
		if (++diagFrames % 600 != 0) {
			return;
		}
		RetroDragon.LOGGER.info(String.format(
			"mesh diag: uploads=%d (%.1f ms total, %.0f us each)  snapshots=%d (%.1f ms total, %.0f us each)  queued=%d",
			uploadCount, uploadNanos / 1e6, uploadCount == 0 ? 0.0 : uploadNanos / 1e3 / uploadCount,
			snapshotCount, snapshotNanos / 1e6, snapshotCount == 0 ? 0.0 : snapshotNanos / 1e3 / snapshotCount,
			inFlight));
		uploadNanos = snapshotNanos = 0L;
		uploadCount = snapshotCount = 0;
	}

	private static void workerLoop() {
		while (true) {
			MeshJob job;
			try {
				job = QUEUE.take();
			} catch (InterruptedException e) {
				return;
			}
			try {
				DONE.add(SectionMesher.mesh(job));
			} catch (Throwable t) {
				// Never let one bad section kill the worker: report an empty result so the render
				// thread decrements inFlight and the section can be retried.
				RetroDragon.LOGGER.error("mesh failed for section", t);
				MeshResult empty = new MeshResult(job.section, job.generation);
				empty.layers[0] = ChunkGeometry.NO_VERTICES;
				empty.layers[1] = ChunkGeometry.NO_VERTICES;
				empty.failed = true;
				DONE.add(empty);
			}
		}
	}
}
