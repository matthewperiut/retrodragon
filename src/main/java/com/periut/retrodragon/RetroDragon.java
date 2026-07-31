package com.periut.retrodragon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared logging for the mod. Deliberately NOT an entrypoint.
 *
 * <p>This used to implement OSL's {@code ModInitializer} for an {@code init} that called
 * {@link com.periut.retrodragon.render.RenderBackend#select()} -- which
 * {@link RetroDragonPreLaunch} had already called, because beta builds its window inside
 * {@code Minecraft.init()} and that runs BEFORE OSL's init entrypoint. The second call was a no-op
 * by construction ({@code select()} keeps its first answer), so the entrypoint bought one log line
 * at the price of a hard dependency on OSL for a mod that used nothing else from it.
 *
 * <p>RetroDragon now depends on fabric-loader and Minecraft alone. Both content APIs remain
 * optional and are still detected at runtime; RetroAPI brings OSL in on its own account.
 */
public class RetroDragon {

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

	private RetroDragon() {
	}
}
