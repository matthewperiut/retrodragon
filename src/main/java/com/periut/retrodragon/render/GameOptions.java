package com.periut.retrodragon.render;

import net.minecraft.client.Minecraft;

/**
 * Reaches the client's video options, or admits it cannot.
 *
 * <p>The accessor mixin behind this only exists once Mixin has applied it, which is true in the game
 * and false everywhere else -- the standalone renderer checks ({@code webgpuWindow},
 * {@code webgpuDraw}) run without a Minecraft instance at all, and calling the accessor there throws
 * the "replaced by mixin" stub. Options are read on the frame path, so that turns a diagnostic tool
 * into a crash.
 *
 * <p>Resolved once and cached: whether mixins are applied cannot change during a run, and this sits
 * on a path that runs every frame.
 */
public final class GameOptions {
	private static boolean resolved;
	private static boolean available;

	private GameOptions() {
	}

	/** The client, or null when there is not one -- a standalone test, or before startup finishes. */
	public static Minecraft client() {
		if (!resolved) {
			resolved = true;
			try {
				com.periut.retrodragon.mixin.MinecraftAccessor.retroperf$instance();
				available = true;
			} catch (Throwable t) {
				// No mixins, so no game: the caller falls back to its defaults.
				available = false;
			}
		}
		if (!available) {
			return null;
		}
		try {
			return com.periut.retrodragon.mixin.MinecraftAccessor.retroperf$instance();
		} catch (Throwable t) {
			available = false;
			return null;
		}
	}
}
