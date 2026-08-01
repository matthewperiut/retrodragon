package com.periut.retrodragon.render;

import net.minecraft.client.Minecraft;

import static com.periut.webgpu.webgpu_h.*;

/**
 * What the "Performance" video option means under WebGPU, and how a mod's own pacing requests --
 * {@code Display.setVSyncEnabled} and {@code Display.sync(int)}, LWJGL 2's classic entry points, and
 * {@link com.periut.retrodragon.api.RetroSettings#setFrameLimit}, the new reflective one -- layer on
 * top of it.
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
 *
 * <h2>Two things a mod can ask for on top of the setting</h2>
 *
 * {@link #vsyncOverride} ("sync presentation to the display, or don't") and {@link #frameLimit}
 * ("hold this exact rate, whatever it is") answer different questions, and a caller that sets both
 * gets both -- see {@link #presentMode()}, {@link #uncapped()} and {@link #await()} for exactly how
 * they combine with each other and with the three-tier setting. In short:
 *
 * <ul>
 *   <li><b>Present mode</b> (does the swapchain wait for the display): {@code vsyncOverride}, if set,
 *       decides it outright. Otherwise a numeric {@code frameLimit} decides it -- Mailbox, same as Max
 *       FPS, and for the same reason: a target above the display's refresh rate could never be reached
 *       through Fifo, which blocks at that rate no matter what {@link #await()} does afterwards.
 *       Otherwise the three-tier setting decides it, as before.</li>
 *   <li><b>The sleep target</b> ({@link #await()}): a numeric {@code frameLimit}, if set, ALWAYS
 *       applies, on both backends and regardless of {@code vsyncOverride} or the tier -- it is the
 *       most specific request a caller can make, and it composes with vsync exactly the way Power
 *       Saver's fixed 60 fps already does (vsync bounds the display waits, the sleep bounds the rate
 *       further if it asks for less). With no numeric limit, an explicit {@code vsyncOverride}
 *       suppresses the tier's own Power Saver sleep (there is nothing left for the tier to add once a
 *       mod has said "vsync on" or "vsync off" directly). With neither set, the tier's own Power Saver
 *       sleep applies -- but ONLY on WebGPU; on GL, vanilla's own render loop already sleeps toward
 *       its own 40 fps figure for that tier, so this class stays out of the way there rather than
 *       layering a second, uncoordinated sleep on top. See {@link #await()}.</li>
 * </ul>
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

	/**
	 * A numeric frame-rate cap a mod has asked for directly, in frames per second; {@code 0} means
	 * none. Set by {@link com.periut.retrodragon.api.RetroSettings#setFrameLimit} and by
	 * {@code Display.sync(int)} -- both funnel into this one field, so a mod using either entry point
	 * gets identical behaviour and only one thing ever needs to be asked what the current target is.
	 *
	 * <p>{@code 0} is "no numeric cap", not "cap to 0 fps": the same convention {@code BenchMixin}
	 * already uses for vanilla's {@code fpsLimit} field, and the one
	 * {@link com.periut.retrodragon.api.RetroSettings#NO_FRAME_LIMIT} documents.
	 *
	 * <p>Deliberately independent of {@link #vsyncOverride} -- see the class doc for how the two
	 * combine.
	 */
	private static volatile int frameLimit;

	private FramePacing() {
	}

	/** See {@link #vsyncOverride}. Called from {@code Display.setVSyncEnabled}, nowhere else. */
	public static void setVsyncOverride(boolean enabled) {
		vsyncOverride = enabled;
	}

	/** See {@link #frameLimit}. */
	public static int getFrameLimit() {
		return frameLimit;
	}

	/**
	 * See {@link #frameLimit}. Called from {@code RetroSettings.setFrameLimit} and
	 * {@code Display.sync(int)}, nowhere else. {@code fps <= 0} clears the cap rather than being
	 * stored as-is, so every reader of {@link #frameLimit} can treat "positive" and "has a cap" as the
	 * same question.
	 */
	public static void setFrameLimit(int fps) {
		frameLimit = Math.max(0, fps);
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
	 * <p><b>A numeric {@link #frameLimit} is Mailbox too, for the same underlying reason.</b> Fifo
	 * blocks a present at the display's refresh rate; a mod asking for 240 fps on a 60 Hz panel would
	 * never see it if presentation itself were the bottleneck, however precisely {@link #await()}
	 * slept. Mailbox lets the renderer run free and {@link #await()} throttle it to the exact number
	 * instead -- correct whether that number is above, at, or below the display's refresh rate.
	 *
	 * <p>Falls back to Fifo where Mailbox is unsupported; {@code Surface.configure} checks the
	 * surface's capabilities rather than assuming.
	 */
	public static int presentMode() {
		if (vsyncOverride != null) {
			return vsyncOverride ? WGPUPresentMode_Fifo() : WGPUPresentMode_Mailbox();
		}
		if (frameLimit > 0) {
			return WGPUPresentMode_Mailbox();
		}
		return setting() == MAX_FPS ? WGPUPresentMode_Mailbox() : WGPUPresentMode_Fifo();
	}

	/**
	 * Whether the renderer should run free of the display's refresh rate.
	 *
	 * <p>Always agrees with {@link #presentMode()} -- true exactly when that returns Mailbox -- since
	 * the two describe the same decision from different sides: this one tells
	 * {@code WebGpuRenderer.beginFrame} whether to draw into {@code offscreen} instead of acquiring a
	 * drawable, and {@code presentMode()} tells the swapchain which present mode to request.
	 *
	 * <p>Presenting cannot outrun the compositor on macOS -- acquiring a drawable blocks until one
	 * frees, and asking for more is what {@code Immediate} did and what hung the machine. So "no frame
	 * limiter" (Max FPS) and "a specific frame limiter" ({@link #frameLimit}) both mean the same thing
	 * here: RENDERING freely and letting {@code presentOffscreen}'s own rate cap, or {@link #await()}'s
	 * sleep, decide what actually reaches the screen. See {@code WebGpuRenderer}, which draws every
	 * frame to a texture of its own and blits only the latest into a drawable.
	 */
	public static boolean uncapped() {
		if (vsyncOverride != null) {
			return !vsyncOverride;
		}
		if (frameLimit > 0) {
			return true;
		}
		return setting() == MAX_FPS;
	}

	/**
	 * The fps {@link #await()} paces to right now; {@code 0} means no sleep at all. Pulled out as its
	 * own (pure, GPU-free) function so the precedence rules can be asserted directly -- see
	 * {@code framePacingTest}.
	 *
	 * <p>A numeric {@link #frameLimit} always wins when set, on EITHER backend: it is the most
	 * specific request a caller can make, and it applies whether or not {@link #vsyncOverride} is also
	 * set -- see the class doc.
	 *
	 * <p>Failing that: an explicit {@code vsyncOverride} suppresses the tier's own Power Saver sleep
	 * (there is nothing left to add once a mod has said "vsync on" or "vsync off" directly). With
	 * neither set, Power Saver's fixed 60 fps applies -- but only when {@link RenderBackend#isWebGpu()}.
	 * On GL, vanilla's OWN {@code GameRenderer.renderFrame} already sleeps toward its own 40 fps target
	 * for this tier, with its own separate clock ({@code lastFrameTime}), completely untouched by this
	 * class. Applying a SECOND, independent 60 fps sleep on top of that on GL would not add up to
	 * "60 fps and 40 fps agree on whichever is stricter" the way vsync-plus-a-sleep does elsewhere in
	 * this class -- two clocks with no shared state, both trying to hold a fixed cadence in the same
	 * loop iteration, run out of phase and each one's wait stacks on the other's (the same hazard
	 * {@code Display.sync(int)}'s doc explains at length). The WebGPU-only gate here is what keeps
	 * this class's own sleep from colliding with vanilla's, on the one tier where vanilla still runs
	 * its own.
	 */
	private static long targetFps() {
		if (frameLimit > 0) {
			return frameLimit;
		}
		if (vsyncOverride == null && setting() == POWER_SAVER && RenderBackend.isWebGpu()) {
			return POWER_SAVER_FPS;
		}
		return 0L;
	}

	/**
	 * Sleeps if this frame is early. Called immediately before present.
	 *
	 * <p>Sleeps in millisecond steps and busy-waits the last fraction: {@code Thread.sleep} is only
	 * accurate to a millisecond or so, and at 60 fps a frame is 16.7 ms, so rounding alone would
	 * cost several percent of the budget and make the cap land at 58 rather than 60.
	 *
	 * <p>That accuracy caveat matters more now than it used to. Power Saver's fixed 60 fps was the
	 * only target this method ever had to hit, but {@link #frameLimit} can be anything a mod asks for
	 * -- at 240 fps the whole period is 4.2 ms, so the same millisecond of {@code Thread.sleep} slop
	 * is a much bigger fraction of the budget. The busy-wait tail is what keeps the cap honest at a
	 * high target as well as a low one; it costs a spin of {@code Thread.onSpinWait()} calls rather
	 * than accuracy.
	 *
	 * <p><b>Called at most once per frame.</b> WebGPU's one call site is {@code WebGpuRenderer.endFrame};
	 * GL's are the handful of swap points in {@code Display} (mutually exclusive per frame -- exactly
	 * one fires for any given swap). Never called from a mod's own {@code Display.sync(int)}, which
	 * only sets {@link #frameLimit} and leaves the sleeping to whichever of those runs later in the
	 * same iteration. Two independent callers pacing the same target in the same frame would not
	 * cancel out; started at different moments they run out of phase, and each one's wait stacks on
	 * the other's, roughly doubling the effective period. See {@code Display.sync(int)}'s doc for the
	 * long version.
	 */
	public static void await() {
		long target = targetFps();
		if (target <= 0L) {
			nextFrameAt = 0L;
			return;
		}
		long period = NANOS_PER_SECOND / target;
		long now = System.nanoTime();
		if (nextFrameAt == 0L || now - nextFrameAt > period * 4) {
			// First frame, or the loop stalled long enough that catching up would burst (or the target
			// itself just changed and the old schedule is stale). Restart the cadence from here rather
			// than racing to make up lost frames.
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
		String limitSuffix = frameLimit > 0 ? ", numeric cap " + frameLimit + " fps" : "";
		if (vsyncOverride != null) {
			return (vsyncOverride ? "vsync forced on (Display.setVSyncEnabled, fifo)"
				: "vsync forced off (Display.setVSyncEnabled, mailbox)") + limitSuffix;
		}
		if (frameLimit > 0) {
			return "numeric cap " + frameLimit + " fps (mailbox, throttled render)";
		}
		return switch (setting()) {
			case MAX_FPS -> "Max FPS (mailbox, uncapped render)";
			case POWER_SAVER -> "Power saver (vsync, 60 fps cap)";
			default -> "Balanced (vsync)";
		};
	}

	/**
	 * {@code ./gradlew framePacingTest} -- the pacing arithmetic, GPU- and game-free: the precedence
	 * between {@link #frameLimit}, {@link #vsyncOverride} and the three-tier setting
	 * ({@link #targetFps()}, {@link #presentMode()}, {@link #uncapped()}), and a bounded real check of
	 * {@link #await()}'s own sleep. What it cannot check outside the game is that a held rate is
	 * actually what lands on screen; see the class javadoc for what to look for there.
	 *
	 * <p>Order matters below: {@link #vsyncOverride} is a one-way latch (see its own doc) once set for
	 * the rest of this process, so every {@code vsyncOverride == null} scenario runs first.
	 */
	public static void main(String[] args) {
		expect(getFrameLimit() == 0, "getFrameLimit() starts at RetroSettings.NO_FRAME_LIMIT");

		setFrameLimit(120);
		expect(getFrameLimit() == 120, "setFrameLimit(120) took");
		setFrameLimit(-5);
		expect(getFrameLimit() == 0, "a negative fps clamps to the no-limit sentinel, not stored raw");
		setFrameLimit(0);
		expect(getFrameLimit() == 0, "setFrameLimit(0) clears the cap");

		// No override, no numeric limit: the three-tier setting alone decides. No Minecraft client
		// exists on this classpath, so setting() falls back to Balanced (see its own doc) -- vsync,
		// no numeric sleep target.
		expect(targetFps() == 0L, "Balanced has no numeric sleep target");
		expectPresentModeAgreesWithUncapped();
		expect(!uncapped(), "Balanced does not uncap the renderer");

		// A numeric limit alone must win the present-mode question over the tier -- otherwise a target
		// above the (Balanced-implied) vsync rate could never be reached, whatever await() slept for.
		setFrameLimit(90);
		expect(targetFps() == 90L, "a numeric limit is the sleep target");
		expect(uncapped(), "a numeric limit uncaps presentation so await() can throttle precisely");
		expectPresentModeAgreesWithUncapped();
		setFrameLimit(0);

		// vsyncOverride enters the picture now -- irreversibly for the rest of this process.
		setVsyncOverride(true);
		expect(!uncapped(), "vsync forced on does not uncap the renderer");
		expect(targetFps() == 0L, "vsync alone, with no numeric limit, has nothing to sleep for");
		expectPresentModeAgreesWithUncapped();

		// Both set: vsync still wins present mode (an explicit "vsync on" must still mean Fifo), but
		// the numeric limit still wins the sleep target -- the two are orthogonal, so a caller asking
		// for both gets both, the same way Power Saver already combines vsync with a fixed sleep.
		setFrameLimit(30);
		expect(!uncapped(), "vsync still forces Fifo-style presentation even with a numeric limit set");
		expect(targetFps() == 30L, "the numeric limit still throttles on top of vsync");
		setFrameLimit(0);

		setVsyncOverride(false);
		expect(uncapped(), "vsync forced off uncaps the renderer");
		expect(targetFps() == 0L, "vsync off alone has nothing to sleep for");
		setFrameLimit(45);
		expect(uncapped(), "vsync off plus a numeric limit is still uncapped presentation");
		expect(targetFps() == 45L, "the numeric limit applies with vsync explicitly off too");
		setFrameLimit(0);

		// await() itself: a real sleep, at a high enough target to keep this fast, bounded loosely to
		// keep it non-flaky. The first call primes the cadence and must NOT sleep -- there is nothing
		// yet to catch up to (see the "first frame" branch); the second is where the cap actually
		// holds.
		setFrameLimit(200); // 5 ms period
		long t0 = System.nanoTime();
		await();
		long primed = System.nanoTime() - t0;
		expect(primed < 5_000_000L, "priming await() must return immediately, not sleep a whole period");
		long t1 = System.nanoTime();
		await();
		long held = System.nanoTime() - t1;
		expect(held > 1_000_000L, "await() actually slept toward the 200 fps target");
		expect(held < 200_000_000L, "await() did not stall far past its target");
		setFrameLimit(0);

		System.out.println("FramePacing self-check OK");
	}

	private static void expectPresentModeAgreesWithUncapped() {
		boolean mailbox = presentMode() == WGPUPresentMode_Mailbox();
		expect(mailbox == uncapped(), "presentMode() and uncapped() must agree: mailbox iff uncapped");
	}

	private static void expect(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what + " -- frameLimit=" + frameLimit
				+ " vsyncOverride=" + vsyncOverride);
		}
	}
}
