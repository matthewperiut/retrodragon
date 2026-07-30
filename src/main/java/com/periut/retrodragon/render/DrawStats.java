package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;

/**
 * Counts immediate-mode Tessellator draws that are NOT chunk geometry -- GUI, HUD, entities,
 * particles, sky. Batching them only pays if there are many, so count before building anything.
 *
 * Zero cost unless {@code -Dretroperf.diag=true}.
 */
public final class DrawStats {
	private static final boolean DIAG = Boolean.getBoolean("retroperf.diag");
	private static long draws;
	private static long frames;

	private DrawStats() {
	}

	public static void countUncaptured() {
		if (DIAG) {
			draws++;
		}
	}

	/** Call once per frame. */
	public static void endFrame() {
		if (!DIAG) {
			return;
		}
		if (++frames % 600 == 0) {
			RetroDragon.LOGGER.info("immediate-mode draws: {} per frame (avg over {} frames)",
				String.format("%.1f", draws / 600.0), 600);
			draws = 0;
		}
	}
}
