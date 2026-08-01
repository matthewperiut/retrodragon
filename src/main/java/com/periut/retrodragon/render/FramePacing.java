package com.periut.retrodragon.render;

import net.minecraft.client.Minecraft;

import static com.periut.webgpu.webgpu_h.*;

/**
 * What the "Performance" video option means under WebGPU.
 *
 * <p>b1.7.3's option has three settings and, in vanilla, all three do roughly the same thing -- the
 * game asks for vsync once at startup and then sleeps a little more or less. Under WebGPU the
 * swapchain's present mode is the real control, so the setting can mean what its labels say:
 *
 * <table>
 *   <tr><th>setting</th><th>present mode</th><th>cap</th></tr>
 *   <tr><td>Max FPS</td><td>Immediate -- no vsync</td><td>none</td></tr>
 *   <tr><td>Balanced</td><td>Fifo -- vsync</td><td>the display's refresh rate</td></tr>
 *   <tr><td>Power saver</td><td>Fifo -- vsync</td><td>60 fps</td></tr>
 * </table>
 *
 * <p>Power saver needs both: on a 120 Hz display vsync alone still runs the GPU at 120 fps, which is
 * the opposite of saving power. The cap is applied by sleeping before the present rather than after,
 * so the frame that is finally shown is as fresh as possible.
 *
 * <p>Changing the setting reconfigures the swapchain, which is why it is read every frame rather
 * than latched: the option screen writes it directly and nothing notifies the renderer.
 */
public final class FramePacing {
	/** Beta's {@code PERFORMANCE_KEYS} order: max, balanced, powersaver. */
	private static final int MAX_FPS = 0;
	/**
	 * Public because it is also the value {@code GameOptionsMixin} seeds a fresh options.txt with, and
	 * the table above is where "what Balanced means" is written down. A compile-time constant, so
	 * naming it from the mixin does not pull this class (or the WebGPU bindings it imports) into
	 * options loading.
	 */
	public static final int BALANCED = 1;
	private static final int POWER_SAVER = 2;

	private static final long POWER_SAVER_FPS = 60L;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static long nextFrameAt;

	/**
	 * Set by {@code Display.setVSyncEnabled}, LWJGL 2's swap-interval API. Under WebGPU there is no
	 * GL swap interval to set -- {@code RenderBackend.select()} calls {@code Sdl3Window.setNoGl(true)}
	 * before the window even exists, which makes {@code Sdl3Window.setSwapInterval} a permanent no-op
	 * for the rest of the run -- so without this, a mod calling that LWJGL API on the default backend
	 * changed nothing here and nothing on screen. {@code null} until a mod calls it, meaning the
	 * "Performance" option's three-tier mapping below still decides everything; once set it wins over
	 * that mapping until called again.
	 */
	private static volatile Boolean vsyncOverride;

	private FramePacing() {
	}

	/** See {@link #vsyncOverride}. Called from {@code Display.setVSyncEnabled}, nowhere else. */
	public static void setVsyncOverride(boolean enabled) {
		vsyncOverride = enabled;
	}

	/**
	 * Forces a setting, for measuring outside the game.
	 *
	 * <p>{@code -Dretroperf.pacing=max|balanced|powersaver}. Without it the standalone harnesses fall
	 * back to Balanced -- {@link GameOptions#client()} returns null when there is no Minecraft -- which
	 * means vsync, which means every frame-loop measurement they take is really a measurement of the
	 * display's refresh rate. That made the uncapped path, the one that matters for throughput, the
	 * one path no benchmark could reach.
	 *
	 * <p>-1 when unset, so the game's own option still wins in the game.
	 */
	private static final int FORCED = switch (System.getProperty("retroperf.pacing", "")) {
		case "max" -> MAX_FPS;
		case "balanced" -> BALANCED;
		case "powersaver" -> POWER_SAVER;
		default -> -1;
	};

	private static int setting() {
		if (FORCED >= 0) {
			return FORCED;
		}
		Minecraft client = GameOptions.client();
		if (client == null || client.options == null) {
			return BALANCED;
		}
		return client.options.fpsLimit;
	}

	/**
	 * The swapchain present mode this setting wants.
	 *
	 * <p><b>Max FPS is MAILBOX, never IMMEDIATE.</b> Immediate presents every finished frame straight
	 * to the surface, which against a VISIBLE {@code CAMetalLayer} means asking the window server to
	 * composite 400 frames a second when it consumes 120. Two things follow, and both were observed:
	 *
	 * <ul>
	 *   <li>The drawable pool exhausts, so acquiring the next backbuffer blocks under back-pressure --
	 *       and "Max FPS" measured SLOWER than vsync, around 90 against 120.</li>
	 *   <li>The window server is flooded, and on macOS that hangs the whole desktop. It did not happen
	 *       when the window was off screen, because nothing was compositing it.</li>
	 * </ul>
	 *
	 * <p>Mailbox is what uncapped rendering actually wants: the renderer runs as fast as it likes and
	 * the compositor takes the most recent finished frame at its own rate, so frames are dropped
	 * rather than queued. The frame RATE stays uncapped -- {@code FrameTimer} counts frames rendered --
	 * while presentation stays bounded by the display.
	 *
	 * <p>Falls back to Fifo where Mailbox is unsupported; {@code Surface.configure} checks the
	 * surface's capabilities rather than assuming.
	 */
	public static int presentMode() {
		if (vsyncOverride != null) {
			return vsyncOverride ? WGPUPresentMode_Fifo() : WGPUPresentMode_Mailbox();
		}
		return setting() == MAX_FPS ? WGPUPresentMode_Mailbox() : WGPUPresentMode_Fifo();
	}

	/**
	 * Whether the renderer should run free of the display's refresh rate.
	 *
	 * <p>True only for Max FPS. Presenting cannot outrun the compositor on macOS -- acquiring a
	 * drawable blocks until one frees, and asking for more is what {@code Immediate} did and what
	 * hung the machine. So "no frame limiter" cannot mean presenting more often; it has to mean
	 * RENDERING more often. See {@code WebGpuRenderer}, which draws every frame to a texture of its
	 * own and blits only the latest into a drawable.
	 */
	public static boolean uncapped() {
		if (vsyncOverride != null) {
			return !vsyncOverride;
		}
		return setting() == MAX_FPS;
	}

	/**
	 * Sleeps if this frame is early. Called immediately before present.
	 *
	 * <p>Sleeps in millisecond steps and busy-waits the last fraction: {@code Thread.sleep} is only
	 * accurate to a millisecond or so, and at 60 fps a frame is 16.7 ms, so rounding alone would
	 * cost several percent of the budget and make the cap land at 58 rather than 60.
	 */
	public static void await() {
		// An explicit override bypasses the three-tier mapping entirely -- including its 60 fps
		// power-saver cap, which "vsync on/off" has no equivalent of.
		if (vsyncOverride != null || setting() != POWER_SAVER) {
			nextFrameAt = 0L;
			return;
		}
		long period = NANOS_PER_SECOND / POWER_SAVER_FPS;
		long now = System.nanoTime();
		if (nextFrameAt == 0L || now - nextFrameAt > period * 4) {
			// First frame, or the loop stalled long enough that catching up would burst. Restart
			// the cadence from here rather than racing to make up lost frames.
			nextFrameAt = now + period;
			return;
		}
		long remaining = nextFrameAt - now;
		if (remaining > 0L) {
			try {
				long millis = remaining / 1_000_000L;
				if (millis > 1L) {
					Thread.sleep(millis - 1L);
				}
				while (System.nanoTime() < nextFrameAt) {
					Thread.onSpinWait();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		nextFrameAt += period;
	}

	public static String describe() {
		if (vsyncOverride != null) {
			return vsyncOverride ? "vsync forced on (Display.setVSyncEnabled, fifo)"
				: "vsync forced off (Display.setVSyncEnabled, mailbox)";
		}
		return switch (setting()) {
			case MAX_FPS -> "Max FPS (mailbox, uncapped render)";
			case POWER_SAVER -> "Power saver (vsync, 60 fps cap)";
			default -> "Balanced (vsync)";
		};
	}
}
