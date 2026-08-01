package com.periut.retrodragon.api;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.render.Capture;
import com.periut.retrodragon.render.GameOptions;
import com.periut.retrodragon.render.RenderScale;

import net.minecraft.client.Minecraft;

/**
 * Runtime on/off switches for RGSS (rotated-grid supersampling) and terrain mipmaps -- the two
 * anti-aliasing options {@link Config#RGSS} and {@link Config#MIPMAP} used to be launch-time only.
 *
 * <h2>Reflection-friendly by design</h2>
 *
 * Stable class name, public static methods, {@code boolean} in and out, no RetroDragon type in any
 * signature. A mod that does not want a compile-time dependency on this one can still call it:
 *
 * <pre>
 * Class.forName("com.periut.retrodragon.api.RetroSettings")
 *     .getMethod("setMipmap", boolean.class)
 *     .invoke(null, false);
 * </pre>
 *
 * <h2>Config's fields are still the launch-time defaults</h2>
 *
 * {@code -Dretroperf.rgss=false} / {@code -Dretroperf.mipmap=false} are parsed once into
 * {@link Config#RGSS} / {@link Config#MIPMAP} and nothing here changes that -- those two fields stay
 * exactly what they always were. This class owns the CURRENT value, seeded from that default, and
 * every render-path read that used to go straight to {@code Config.RGSS} / {@code Config.MIPMAP} now
 * goes through {@link #isRgss()} / {@link #isMipmap()} instead, so a runtime change actually reaches
 * the shader and the texture upload path.
 *
 * <h2>Frame limit: pass-through, not owned here</h2>
 *
 * {@link #getFrameLimit()} / {@link #setFrameLimit(int)} are a thin pass-through to
 * {@code com.periut.retrodragon.render.FramePacing}, unlike RGSS and mipmaps above -- the actual
 * pacing decision (does the renderer wait for the display, how long does it sleep before presenting)
 * has to be made where the renderer already reads {@code vsyncOverride}, the boolean equivalent
 * {@code Display.setVSyncEnabled} feeds. Keeping both in {@code FramePacing} means a mod can ask for
 * "vsync" through one entry point and "a number" through the other and get one documented answer for
 * what happens when it does both -- see that class's javadoc. {@code Display.sync(int)}, LWJGL 2's
 * classic frame limiter, sets this exact same value, so a mod written for vanilla b1.7.3 that already
 * calls that gets this for free, with no code change.
 *
 * <h2>RGSS: instant, either direction</h2>
 *
 * RGSS is read as a plain per-draw shader uniform ({@code TerrainShader}, {@code TerrainAppearance}).
 * There is no GPU resource behind it, so {@link #setRgss} takes effect on the next frame drawn, on
 * either backend, with no reupload and no thread requirement beyond the write itself being atomic
 * (it is -- a single volatile store).
 *
 * <h2>Mipmap: off is instant, on costs a reupload</h2>
 *
 * Turning mipmaps off is exactly as cheap as RGSS: {@code TerrainAppearance.maxLod()} and the
 * equivalent GL read both clamp the sampled LOD to 0 the moment {@link #isMipmap()} says false, so an
 * already-uploaded chain simply stops being read. Turning them ON is not free -- a chain that was
 * never built cannot be sampled (undefined on GL; the WebGPU texture was allocated with a single
 * level to begin with, see {@code TextureStore.mipLevelsFor}) -- so {@link #setMipmap} has to force
 * the block atlas to be re-read from the active texture pack and re-uploaded with the full chain this
 * time. That reuses the exact path a texture-pack switch or an F3+T resource reload already takes,
 * {@code TextureManager.reload()}, rather than a second uploader living here: it re-reads every
 * registered texture (terrain.png included) and re-runs it through {@code load(BufferedImage,int)},
 * which is the one seam {@code MipmapCompletenessMixin} already hooks to build the corrected chain on
 * GL and {@code TextureStore.define} already hooks to build it on WebGPU. Both backends are covered
 * by that one call; nothing backend-specific lives here.
 *
 * <p><b>Thread expectations.</b> The reupload touches GL state on the GL backend and WebGPU
 * queue/device calls on the other -- neither is safe off the render thread. Called ON the render
 * thread (the common case: b1.7.3 has no separate UI thread, so a GUI screen's button handler already
 * runs there) the reupload happens synchronously, before {@link #setMipmap} returns. Called off the
 * render thread, the new value still takes effect immediately for {@link #isMipmap()} -- every read
 * site sees it on the very next check -- but the reupload itself is deferred: it runs the next time
 * {@link #pumpPendingMipmapReupload()} is reached on the render thread, which {@code Display.update()}
 * does once per frame regardless of backend or whether a world is even loaded. Until that pump fires,
 * a texture pack that had no chain still has none; nothing samples garbage, it just keeps the vanilla
 * look for the handful of frames until the pump catches up.
 */
public final class RetroSettings {
	private static volatile boolean rgss = Config.RGSS;
	private static volatile boolean mipmap = Config.MIPMAP;
	/** Set when {@link #setMipmap} enables mipmaps from off the render thread; drained by the pump. */
	private static volatile boolean pendingMipmapReupload;

	/**
	 * The value {@link #setFrameLimit(int)} treats as "no numeric cap" -- {@link #getFrameLimit()}
	 * starts here, and passing it back to {@link #setFrameLimit(int)} clears whatever was set. {@code
	 * 0}, matching the convention {@code BenchMixin} already uses for vanilla's own {@code fpsLimit}
	 * field (0 = MAX, uncapped) and what {@code FramePacing} stores internally for the same reason: a
	 * reflective caller only ever needs to know the number, not read this constant, so keeping the two
	 * conventions identical means there is exactly one "unlimited" to remember, not two.
	 */
	public static final int NO_FRAME_LIMIT = 0;

	private RetroSettings() {
	}

	/** Whether the terrain shader currently applies rotated-grid supersampling. */
	public static boolean isRgss() {
		return rgss;
	}

	/**
	 * Enables or disables RGSS. Instant on both backends -- see the class javadoc. Safe from any
	 * thread. A call that repeats the current value is harmless (it is also cost-free, unlike
	 * {@link #setMipmap}, so no explicit no-op guard is needed here).
	 */
	public static void setRgss(boolean enabled) {
		rgss = enabled;
	}

	/** Whether the terrain shader currently samples the mip chain at all. */
	public static boolean isMipmap() {
		return mipmap;
	}

	/**
	 * Enables or disables terrain mipmaps. A no-op when {@code enabled} already matches the current
	 * value -- important here specifically, since turning mipmaps ON is not free. See the class
	 * javadoc for what turning them on actually costs and which thread it runs the reupload on.
	 */
	public static void setMipmap(boolean enabled) {
		if (enabled == mipmap) {
			return;
		}
		mipmap = enabled;
		if (!enabled) {
			// The shader clamps its LOD to 0 next frame; there is nothing to upload or to free.
			return;
		}
		if (Capture.isRenderThread()) {
			reuploadTerrain();
		} else {
			pendingMipmapReupload = true;
		}
	}

	/**
	 * The current numeric frame-rate cap, in frames per second, or {@link #NO_FRAME_LIMIT} when none
	 * is set. See the class javadoc for why this reads {@code FramePacing} rather than a field here.
	 */
	public static int getFrameLimit() {
		return com.periut.retrodragon.render.FramePacing.getFrameLimit();
	}

	/**
	 * Requests that the renderer hold this exact frame rate. Safe from any thread and safe on both
	 * backends -- it only ever writes one {@code volatile int}; see {@code FramePacing.setFrameLimit}
	 * for what reads it and when. Takes effect on the very next frame paced, on either backend: WebGPU
	 * paces at {@code WebGpuRenderer.endFrame}, GL paces at {@code Display}'s swap points, and both run
	 * unconditionally once per frame regardless of what a caller does.
	 *
	 * <p>{@code fps <= 0} clears the cap ({@link #NO_FRAME_LIMIT}) rather than being rejected -- the
	 * same convention {@code Display.sync(int)}, LWJGL 2's classic frame limiter, already uses, since
	 * both entry points end up setting the exact same value.
	 *
	 * <p>Interacts with vsync ({@code Display.setVSyncEnabled}) rather than overriding it: a caller
	 * that sets both gets both, the same way vanilla's own Power Saver tier already combines vsync
	 * with a fixed sleep. See {@code FramePacing}'s class javadoc for the full precedence, including
	 * what a numeric cap does to WebGPU's present-mode choice.
	 */
	public static void setFrameLimit(int fps) {
		com.periut.retrodragon.render.FramePacing.setFrameLimit(fps);
	}

	/**
	 * Applies a mipmap-enable that {@link #setMipmap} could not perform synchronously because it was
	 * called off the render thread. A no-op almost every time it runs: {@code Display.update()} calls
	 * this once per frame so the deferred case is covered without any render-path file needing to know
	 * this class exists.
	 */
	public static void pumpPendingMipmapReupload() {
		if (pendingMipmapReupload && Capture.isRenderThread()) {
			pendingMipmapReupload = false;
			// Re-checked: mipmap may have been toggled back off again before this ran, in which case
			// there is nothing to upload -- the OFF branch above already made that free.
			if (mipmap) {
				reuploadTerrain();
			}
		}
	}

	// --- render scale -----------------------------------------------------------------------------

	/**
	 * The world's render resolution as a multiple of the window's LOGICAL size.
	 *
	 * <p>1.0 means 854x480 on a default window, on every machine, whatever the display's scale factor
	 * is. Retina is folded in rather than being a second setting: a HiDPI window is simply scale 2.0,
	 * and the default is seeded from the window's pixels-per-point so an untouched install renders
	 * exactly what it did before this existed.
	 *
	 * <p>Only the WORLD scales. The HUD, text and any open screen stay at the drawable's native
	 * resolution, which is the point of doing it this way instead of scaling the whole frame.
	 */
	public static float getRenderScale() {
		return RenderScale.scale();
	}

	/**
	 * Sets the world render scale.
	 *
	 * <p>Clamped to {@code [0.1, 4.0]}; the value actually adopted is returned, so a caller can tell
	 * whether it got what it asked for without re-reading. Safe from any thread -- the render path
	 * reads it once per frame and resizes its target when it changes.
	 *
	 * @return the scale in force after the call
	 */
	public static float setRenderScale(float scale) {
		return RenderScale.setScale(scale);
	}

	/**
	 * The filter used to resolve the world target back to the window. Never null.
	 *
	 * <p>Names, not an enum, deliberately: a mod compiled against {@code "bicubic"} keeps working when
	 * a new technique is added, and this API is meant to be reachable by reflection.
	 */
	public static String getScaleFilter() {
		return RenderScale.filter();
	}

	/**
	 * Sets the resolve filter by name.
	 *
	 * <p>An unknown or unavailable name logs and falls back rather than throwing: this is called from
	 * another mod, and a bad string there should not take the game down.
	 *
	 * @return the filter in force after the call, which may not be the one requested
	 */
	public static String setScaleFilter(String name) {
		return RenderScale.setFilter(name);
	}

	/**
	 * The filters this run can actually use, for the given direction.
	 *
	 * <p><b>Ask, do not assume.</b> Availability is not fixed: {@code "metalfx-spatial"} needs macOS
	 * and the WebGPU backend, and the upsamplers ({@code "fsr1"}, {@code "metalfx-spatial"},
	 * {@code "integer"}) are not offered above 1.0, where the job is reconstructing a downsample
	 * rather than inventing detail. Hardcoding a list in the caller is the thing that rots.
	 *
	 * @param upscale true for the filters that apply when the world target is smaller than the window
	 *     (scale below 1.0), false for the supersampling direction
	 */
	public static String[] getAvailableScaleFilters(boolean upscale) {
		return RenderScale.availableFilters(upscale);
	}

	/** Whether the world is currently rendered at a different size from the window at all. */
	public static boolean isRenderScaleActive() {
		return RenderScale.active();
	}

	private static void reuploadTerrain() {
		Minecraft client = GameOptions.client();
		if (client == null || client.textureManager == null) {
			// No texture manager yet -- too early in boot for anything to have been uploaded once,
			// let alone need re-uploading. The flag is already true, so the very first load this run
			// builds the full chain and nothing here is lost.
			return;
		}
		try {
			client.textureManager.reload();
		} catch (Throwable t) {
			RetroDragon.LOGGER.warn("RetroSettings.setMipmap(true): reupload failed ({})", t.toString());
		}
	}

	/**
	 * {@code ./gradlew retroSettingsTest} -- the no-game-required half of this class's contract:
	 * defaults, the no-op guard, and the render-thread/deferred split. What it cannot check outside
	 * the game is the reupload actually reaching the GPU; that needs a running client (see the class
	 * javadoc for what to look for there). The frame-limit pass-through is checked here too; the
	 * pacing arithmetic it feeds is {@code FramePacing}'s own -- see {@code framePacingTest}.
	 */
	public static void main(String[] args) throws InterruptedException {
		expect(isRgss() == Config.RGSS, "isRgss() starts at Config.RGSS's launch-time default");
		expect(isMipmap() == Config.MIPMAP, "isMipmap() starts at Config.MIPMAP's launch-time default");
		expect(getFrameLimit() == NO_FRAME_LIMIT, "getFrameLimit() starts unset");

		// A thin pass-through to FramePacing, not a field owned here -- see the class javadoc. Just
		// the round trip and the sentinel; the precedence it feeds (vsyncOverride, the three-tier
		// setting) is FramePacing's own contract, checked in framePacingTest.
		setFrameLimit(75);
		expect(getFrameLimit() == 75, "setFrameLimit(75) reached FramePacing and read back");
		setFrameLimit(-1);
		expect(getFrameLimit() == NO_FRAME_LIMIT, "a negative fps reads back as NO_FRAME_LIMIT");
		setFrameLimit(NO_FRAME_LIMIT);
		expect(getFrameLimit() == NO_FRAME_LIMIT, "setFrameLimit(NO_FRAME_LIMIT) clears the cap");

		// RGSS: every toggle is instant and never touches the reupload machinery.
		setRgss(false);
		expect(!isRgss(), "setRgss(false) took");
		setRgss(true);
		expect(isRgss(), "setRgss(true) took");

		// setMipmap is a no-op when the value does not change -- no reupload attempt, no flag set.
		boolean before = isMipmap();
		setMipmap(before);
		expect(isMipmap() == before, "setMipmap(same value) left the flag alone");
		expect(!pendingMipmapReupload, "setMipmap(same value) queued no reupload");

		// Turning mipmaps OFF is unconditionally instant: no render thread is required and nothing is
		// queued, whatever Capture thinks about the calling thread.
		setMipmap(false);
		expect(!isMipmap(), "setMipmap(false) took immediately");
		expect(!pendingMipmapReupload, "turning mipmaps off never queues a reupload");

		// Turning mipmaps ON while Capture considers this the render thread (true by default, before
		// markRenderThread has ever run) applies synchronously. No game exists here, so the reupload
		// call finds no Minecraft instance and returns quietly rather than throwing -- that silence is
		// itself the thing being checked.
		setMipmap(true);
		expect(isMipmap(), "setMipmap(true) on the render thread took immediately");
		expect(!pendingMipmapReupload, "an on-render-thread enable does not queue a pump");

		// Off the render thread: pin THIS thread as the render thread, then enable mipmaps from a
		// worker thread. The enable must queue rather than touch GL/WebGPU state directly, and
		// isMipmap() must already report the new value before the queued reupload has run.
		setMipmap(false);
		Capture.markRenderThread();
		boolean[] threw = { false };
		Thread worker = new Thread(() -> {
			try {
				setMipmap(true);
			} catch (Throwable t) {
				threw[0] = true;
			}
		});
		worker.start();
		worker.join();
		expect(!threw[0], "an off-thread setMipmap(true) does not throw");
		expect(isMipmap(), "isMipmap() reflects the new value immediately even though the reupload is deferred");
		expect(pendingMipmapReupload, "an off-thread enable queues the deferred reupload");

		// The pump only drains on the render thread; called here it is, since markRenderThread pinned
		// this thread above.
		pumpPendingMipmapReupload();
		expect(!pendingMipmapReupload, "pumpPendingMipmapReupload drains the queued flag on the render thread");

		System.out.println("RetroSettings self-check OK");
	}

	private static void expect(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what + " -- rgss=" + rgss + " mipmap=" + mipmap
				+ " pendingMipmapReupload=" + pendingMipmapReupload);
		}
	}
}
