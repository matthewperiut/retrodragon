package com.periut.retrodragon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.ornithemc.osl.entrypoints.api.ModInitializer;

public class RetroDragon implements ModInitializer {

	public static final Logger LOGGER = LogManager.getLogger("RetroDragon");

	/** {@code -Dretroperf.verbose=true} brings back the routine per-domain info lines. */
	public static final boolean VERBOSE = Boolean.getBoolean("retroperf.verbose");

	/**
	 * Routine progress chatter -- which atlas was found, which subsystem came up, what a frame looks
	 * like. Silent unless {@code -Dretroperf.verbose=true}.
	 *
	 * <p>Startup, shutdown and anything that went wrong log through {@link #LOGGER} directly and are
	 * never suppressed: a log that says nothing about a failure is worse than a noisy one.
	 */
	public static void detail(String message, Object... args) {
		if (VERBOSE) {
			LOGGER.info(message, args);
		}
	}

	@Override
	public void init() {
		// Before anything creates a window: a WebGPU surface needs one with no GL context, and that
		// is decided at SDL_CreateWindow, not afterwards.
		com.periut.retrodragon.render.RenderBackend.select();
		LOGGER.info("RetroDragon loaded");
	}
}
