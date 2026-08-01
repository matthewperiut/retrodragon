package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;

/**
 * Frame times, on whichever backend is running. {@code -Dretroperf.fps=N} reports every N frames.
 *
 * <h2>Why this exists separately from the bench harness</h2>
 *
 * {@code BenchMixin} produces better statistics but hijacks the session to do it -- it teleports the
 * player, pins the camera and the world clock, and disables the frame limiter. That is right for a
 * controlled comparison and wrong for "what does this actually run at while I play". It also has to
 * be armed correctly to produce anything at all, and a run that does not arm it produces silence
 * rather than an error.
 *
 * <p>This is the opposite trade: no session changes, no vantage control, just the numbers for
 * whatever is on screen. It runs identically under GL and WebGPU, which is the point -- a figure from
 * one backend is only worth anything next to the same figure from the other, measured the same way.
 *
 * <p>Reports the MEDIAN as well as the mean. A mean alone hides the shape of a frame-time
 * distribution with hitches in it, and terrain streaming produces exactly that.
 */
public final class FrameTimer {
	/** {@code -Dretroperf.fps=N}: report every N frames. 0 disables. */
	private static final int INTERVAL = Integer.getInteger("retroperf.fps", 0);

	private static final long[] SAMPLES = new long[4096];
	private static int count;
	private static long previous;
	private static long reported;

	private FrameTimer() {
	}

	public static boolean enabled() {
		return INTERVAL > 0;
	}

	/**
	 * {@code -Dretroperf.exitAfter=N} quits cleanly after N frames, on EITHER backend.
	 *
	 * <p>Backend-agnostic on purpose: an unattended comparison run has to end the same way on both,
	 * and it must never need killing. A {@code SIGKILL} to a process holding an acquired Metal
	 * drawable leaves the compositor waiting for a frame that never arrives, which on macOS takes the
	 * whole desktop down.
	 */
	private static final long EXIT_AFTER = Long.getLong("retroperf.exitAfter", -1L);

	private static long frames;

	/** Call once per frame. */
	public static void tick() {
		if (++frames == EXIT_AFTER) {
			// Through the game's own close path, which saves, releases the surface and destroys the
			// window in that order.
			RetroDragon.LOGGER.info("-Dretroperf.exitAfter={} reached; asking the game to quit",
				EXIT_AFTER);
			com.periut.retrodragon.window.sdl.Sdl3Events.requestClose();
		}
		if (INTERVAL <= 0) {
			return;
		}
		long now = System.nanoTime();
		if (previous != 0L) {
			long delta = now - previous;
			if (count < SAMPLES.length) {
				SAMPLES[count++] = delta;
			}
		}
		previous = now;

		if (count >= INTERVAL) {
			report();
			count = 0;
		}
	}

	private static void report() {
		long[] sorted = java.util.Arrays.copyOf(SAMPLES, count);
		java.util.Arrays.sort(sorted);
		double total = 0;
		for (long sample : sorted) {
			total += sample;
		}
		double meanMs = total / count / 1_000_000.0;
		double medianMs = sorted[count / 2] / 1_000_000.0;
		// The slowest 1% is what a hitch feels like, and it is the number a mean most obscures.
		double lowMs = sorted[(int) (count * 0.99)] / 1_000_000.0;

		reported += count;
		RetroDragon.LOGGER.info(
			"{} frames: {} fps avg ({} ms), median {} ms, 1% low {} fps ({} ms){}",
			reported,
			String.format("%.1f", 1000.0 / meanMs), String.format("%.2f", meanMs),
			String.format("%.2f", medianMs),
			String.format("%.1f", 1000.0 / lowMs), String.format("%.2f", lowMs),
			RenderBackend.isWebGpu()
				? " [WebGPU, " + WebGpuFrame.lastDraws() + " draws, " + FramePacing.describe() + "]"
				: " [OpenGL, " + FramePacing.describe() + "]");
	}
}
