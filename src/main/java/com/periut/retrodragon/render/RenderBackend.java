package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.window.sdl.Sdl3Window;

/**
 * Chooses the rendering backend once, at startup, before the window exists.
 *
 * <h2>Why this is a startup decision and not a runtime one</h2>
 *
 * A WebGPU surface is created from a window's raw native handle and takes over presentation. A GL
 * context does the same thing to the same window. One window cannot host both, so the choice has to
 * be made before {@code SDL_CreateWindow} -- which means before beta's {@code Display.create()}, and
 * therefore in a mod entrypoint rather than anywhere in the render loop.
 *
 * <p>That constraint is what makes a staged migration safe. Both paths stay compiled in; exactly one
 * is live per run. While the WebGPU renderer is incomplete it refuses to select itself, and GL runs
 * byte-for-byte as it does today -- there is no intermediate state where half the frame is missing.
 *
 * <h2>Selection order</h2>
 *
 * <ol>
 *   <li>{@code -Dretroperf.backend=gl} -- GL, unconditionally.</li>
 *   <li>{@code -Dretroperf.backend=webgpu} -- WebGPU if a device comes up, ignoring the completeness
 *       gate. This is the switch for developing the backend: it is expected to render an incomplete
 *       frame.</li>
 *   <li>otherwise WebGPU if {@link Config#WEBGPU} is on, a device comes up, <em>and</em>
 *       {@link #FEATURE_COMPLETE} says this build can actually draw the game.</li>
 *   <li>otherwise GL.</li>
 * </ol>
 */
public final class RenderBackend {
	public enum Api {
		GL,
		WEBGPU
	}

	/**
	 * Whether the WebGPU path can render the game well enough to be the default.
	 *
	 * <p>Now true: the title screen, world, terrain, sky, entities, text and GUI all draw. It stays a
	 * constant rather than a heuristic because there is no way to detect at runtime that a renderer
	 * is missing, say, entity drawing -- the frame just comes out wrong.
	 *
	 * <p>This used to add "and the measured frame time is well under the GL path's at the same
	 * vantage". Nobody could run the game when that was written, and when it finally was measured it
	 * turned out to be false: WebGPU was 2.55 ms median against GL's 2.34. It is true now -- 1.57 ms
	 * against 2.34, <b>1.49x</b> -- but only after the four-vertex quad and 20-byte terrain vertex
	 * work. Left here as a reminder that this comment asserted a benchmark it did not have.
	 *
	 * <p>{@code -Dretroperf.backend=gl} is the way back to the GL path, and it remains the
	 * comparison to make when something looks wrong: both renderers are still compiled in and
	 * exactly one is live per run.
	 */
	public static final boolean FEATURE_COMPLETE = true;

	private static final String PROPERTY = "retroperf.backend";

	private static Api selected;
	private static String reason = "not selected";

	private RenderBackend() {
	}

	/**
	 * Decides the backend and, if it is WebGPU, tells the window layer to skip the GL context.
	 *
	 * <p>Idempotent: the first call wins, because by the second one the window may already exist.
	 */
	public static synchronized Api select() {
		if (selected != null) {
			return selected;
		}

		String forced = System.getProperty(PROPERTY, "").toLowerCase(java.util.Locale.ROOT);
		if ("gl".equals(forced) || "opengl".equals(forced)) {
			return settle(Api.GL, "forced by -D" + PROPERTY + "=gl");
		}

		boolean forceWebGpu = "webgpu".equals(forced) || "wgpu".equals(forced) || "dawn".equals(forced);
		if (!forceWebGpu && !Config.WEBGPU) {
			return settle(Api.GL, "WebGPU disabled by config");
		}
		if (!forceWebGpu && !FEATURE_COMPLETE) {
			return settle(Api.GL, "WebGPU backend is not feature-complete in this build"
				+ " (use -D" + PROPERTY + "=webgpu to run it anyway)");
		}

		// Checked BEFORE the device is opened, because it cannot be satisfied at any cost: every
		// native handle a surface can be built from -- CAMetalLayer, HWND, wl_surface, Window --
		// comes from Sdl3Window, and the GLFW path exposes none of them. Without this the run gets
		// as far as WindowSurface.create and dies there, having already paid for an adapter and a
		// device, which reads as a WebGPU fault rather than the window choice it actually is.
		if (!Sdl3Window.ENABLED) {
			return settle(Api.GL, "the GLFW window has no surface handle to attach to"
				+ " (drop -Dretrowindow.sdl=false to run WebGPU)");
		}

		GpuBackend.init();
		if (!GpuBackend.available()) {
			return settle(Api.GL, "no WebGPU device: " + GpuBackend.status());
		}

		if (!Sdl3Window.setNoGl(true)) {
			// The window already exists with a GL context, so the surface can never be created over
			// it. Selecting WebGPU here would produce a black screen instead of a working game.
			return settle(Api.GL, "window was created before the backend was selected");
		}
		// The game's per-frame buffer swap becomes the WebGPU present. Installed here rather than
		// after the window exists, because by then beta is already drawing.
		Sdl3Window.setPresentHook(WebGpuFrame::present);
		// And the surface is released BEFORE the window it was created over. Getting this backwards
		// leaves macOS's render server waiting on a drawable that never comes back.
		Sdl3Window.setShutdownHook(WebGpuFrame::shutdown);
		return settle(Api.WEBGPU, GpuBackend.context().apiSummary()
			+ (forceWebGpu && !FEATURE_COMPLETE ? " (forced; expect an incomplete frame)" : ""));
	}

	private static Api settle(Api api, String why) {
		selected = api;
		reason = why;
		// Here rather than lazily, because the geometry shape has to be decided before anything
		// builds any: a buffer packed four-to-a-quad and drawn as a triangle list is garbage.
		QuadVertices.select(api == Api.WEBGPU);
		TerrainVertex.select(api == Api.WEBGPU);
		// Through the logger, not System.out: this is the one line that says which graphics API the
		// run is actually on, and it has to be in the log file a bug report attaches.
		com.periut.retrodragon.RetroDragon.LOGGER.info("graphics: {}",
			api == Api.WEBGPU ? why : "OpenGL (LWJGL) -- " + why);
		return api;
	}

	/** GL until {@link #select()} has run, which is the safe answer for anything asking early. */
	public static Api current() {
		return selected == null ? Api.GL : selected;
	}

	public static boolean isWebGpu() {
		return current() == Api.WEBGPU;
	}

	public static String reason() {
		return reason;
	}
}
