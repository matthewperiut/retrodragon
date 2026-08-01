package com.periut.retrodragon.api;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.render.Capture;
import com.periut.retrodragon.render.GameOptions;

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
	 * javadoc for what to look for there).
	 */
	public static void main(String[] args) throws InterruptedException {
		expect(isRgss() == Config.RGSS, "isRgss() starts at Config.RGSS's launch-time default");
		expect(isMipmap() == Config.MIPMAP, "isMipmap() starts at Config.MIPMAP's launch-time default");

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
