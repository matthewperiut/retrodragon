package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.DepthBuffer;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.Surface;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.window.sdl.Sdl3Window;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The frame loop: acquire the backbuffer, open a pass, draw, present.
 *
 * <p>Everything that has to be re-created when the window resizes lives here -- the swapchain
 * configuration and the depth buffer -- so resize is one method rather than a rule the caller has to
 * remember. Beta resizes constantly (the window is user-draggable and the game re-lays-out the GUI
 * on every change), so getting that wrong is not a rare-path bug.
 *
 * <h2>Arenas</h2>
 *
 * Two, on purpose. The long-lived one holds the surface descriptor and configuration, which Dawn
 * reads after the call returns. The per-frame one holds the render-pass descriptor and is reset
 * every frame -- a confined arena per frame would allocate and free ~200 bytes 60 times a second for
 * no benefit, and a shared arena that never resets would grow without bound.
 */
public final class WebGpuRenderer implements AutoCloseable {
	private final WebGPUContext ctx;
	private final Arena arena;
	private final Surface surface;

	private DepthBuffer depth;
	private int width;
	private int height;

	/**
	 * Where frames are drawn when the renderer is uncapped, instead of straight into a drawable.
	 *
	 * <p>Presenting cannot outrun the compositor: acquiring a drawable blocks until one frees, which
	 * happens at the display's refresh rate. Asking for more -- which is what {@code Immediate} present
	 * mode does -- floods the window server and hung this machine repeatedly. So "no frame limiter"
	 * cannot mean "present more often"; it has to mean <b>render</b> more often.
	 *
	 * <p>Every frame is drawn here, and only when the display is ready for another is the latest one
	 * copied into a drawable. The frame rate is genuinely uncapped, the compositor sees at most one
	 * present per refresh -- strictly gentler than presenting every frame -- and frames that are
	 * superseded before a present are simply overwritten, which is what dropping a frame means.
	 */
	private com.periut.retrodragon.gpu.RenderTarget offscreen;
	private boolean renderingOffscreen;

	private Arena frameArena;
	private Frame frame;
	private MemorySegment view = MemorySegment.NULL;

	private WebGpuRenderer(WebGPUContext ctx, Arena arena, Surface surface) {
		this.ctx = ctx;
		this.arena = arena;
		this.surface = surface;
	}

	public static WebGpuRenderer create(WebGPUContext ctx, int width, int height) {
		Arena arena = Arena.ofShared();
		Surface surface;
		try {
			surface = WindowSurface.create(ctx, arena);
		} catch (RuntimeException e) {
			arena.close();
			throw e;
		}
		WebGpuRenderer renderer = new WebGpuRenderer(ctx, arena, surface);
		renderer.resize(width, height);
		return renderer;
	}

	private int presentMode = -1;

	/** Reconfigures the swapchain and depth buffer. Cheap and idempotent when nothing changed. */
	public void resize(int width, int height) {
		int w = Math.max(1, width);
		int h = Math.max(1, height);
		int wanted = FramePacing.presentMode();
		if (w == this.width && h == this.height && wanted == this.presentMode && depth != null) {
			return;
		}
		boolean sizeChanged = w != this.width || h != this.height || depth == null;
		this.width = w;
		this.height = h;
		this.presentMode = wanted;
		// Drain before reconfiguring. Configuring a surface replaces its swapchain, and doing that
		// while frames are still executing leaves the compositor holding drawables from a swapchain
		// that no longer exists -- the same hazard as releasing a surface during teardown, on a path
		// the player can trigger at will by changing the Performance option mid-game.
		ctx.drain(2000);
		// Marshalled to thread 0 on macOS, where wgpuSurfaceConfigure is not a GPU call but a WINDOW
		// call: Dawn's Metal backend implements it by writing `drawableSize`, `contentsScale` and
		// `pixelFormat` on the window's CAMetalLayer, which rebuilds the layer's drawable pool.
		//
		// SDL writes those same properties, from thread 0, inside AppKit's
		// windowDidChangeBackingProperties -- which is precisely the callback that fires when the
		// window is dragged onto a display with a different scale factor, and precisely the moment
		// this method is reached, because that is what changed the size we are reconfiguring to. Two
		// threads rebuilding one drawable pool at once is how a drawable stops coming back, and a
		// drawable that never comes back does not hang the game, it hangs the system compositor (see
		// the class notes on Immediate present mode, which found the same wall from the other side).
		//
		// Safe to block here: ctx.drain above means no frame is executing, and nothing has acquired
		// this frame's backbuffer yet -- beginFrame calls resize before currentView, and
		// syncPresentMode runs before beginFrame -- so thread 0 cannot be waiting on anything we hold.
		// Off macOS MainThread.run is a direct call.
		com.periut.retrodragon.window.MainThread.run(() -> surface.configure(arena, w, h, wanted));
		if (surface.presentMode() != wanted) {
			// A silent fallback to Fifo is indistinguishable from Balanced on screen, so say so
			// rather than leaving "Max FPS behaves exactly like vsync" to be puzzled over.
			com.periut.retrodragon.RetroDragon.LOGGER.warn(
				"present mode {} unsupported by this surface; using {}", wanted, surface.presentMode());
		}
		if (sizeChanged) {
			if (depth != null) {
				depth.close();
			}
			depth = DepthBuffer.create(ctx, arena, w, h);
			if (offscreen != null) {
				offscreen.close();
			}
			// Same format as the swapchain: a texture-to-texture copy requires it, and the swapchain
			// is BGRA on Metal while the offscreen default is RGBA.
			offscreen = com.periut.retrodragon.gpu.RenderTarget.create(ctx, arena, w, h,
				surface.format());
			// The old capture is the wrong size now; readPixels must not hand back a stale frame
			// scaled to the new dimensions.
			lastFrameValid = false;
		}
	}

	/**
	 * Picks up a change to the Performance option, which alters the swapchain's present mode.
	 *
	 * <p>Polled rather than pushed: the options screen writes the field directly and there is
	 * nothing to hook. The check is two field reads on a frame that is about to do far more.
	 */
	public void syncPresentMode() {
		if (FramePacing.presentMode() != presentMode) {
			resize(width, height);
		}
	}

	/**
	 * Acquires this frame's backbuffer and opens a command encoder. Passes are opened by the caller,
	 * because how many there are depends on how often the game cleared mid-frame.
	 *
	 * @return false when the backbuffer could not be acquired -- minimised, occluded, or mid-resize.
	 *         The caller must skip the frame and must NOT call {@link #endFrame()}.
	 */
	/**
	 * How many submitted frames may still be executing before the CPU waits.
	 *
	 * <p>Two: the GPU works on one while the CPU prepares the next. Nothing in a submit-and-present
	 * loop waits for the GPU on its own, so without a bound a cheap CPU frame -- world load, where the
	 * cost is upload rather than draw -- queues work far faster than it retires. On macOS that starves
	 * the window server's drawable pool and takes the whole desktop down with it.
	 */
	private static final int MAX_FRAMES_IN_FLIGHT =
		Math.max(1, Integer.getInteger("retroperf.framesInFlight", 2));

	public boolean beginFrame() {
		// Before acquiring anything: if the GPU is behind, wait for it rather than piling on.
		ctx.awaitFramesInFlight(MAX_FRAMES_IN_FLIGHT);
		// The window is asked directly rather than waited on to be reported stale. Two reasons, and
		// both had to be true for resize to look like it worked and not:
		//
		//   - `outdated` only ever gets set inside currentView(), and an UNCAPPED frame never calls
		//     it -- it renders into `offscreen` and touches no drawable at all. So in Max FPS the
		//     flag could not be raised however the window was dragged.
		//   - Even on the vsync path, Dawn reports Success for a CAMetalLayer whose window has
		//     changed size; the layer keeps whatever drawableSize it was configured with, so the
		//     swapchain is stale without ever saying so and the frame is silently scaled.
		//
		// SDL_GetWindowSizeInPixels is the drawable size, which is precisely what the swapchain has
		// to match, and it is what the renderer was created from. Two integers per frame.
		int windowWidth = Sdl3Window.width();
		int windowHeight = Sdl3Window.height();
		if (windowWidth > 0 && windowHeight > 0
				&& (windowWidth != width || windowHeight != height)) {
			forceResize(windowWidth, windowHeight);
		} else if (surface.isOutdated()) {
			// Still honoured: a swapchain can go stale for reasons that are not a size change, such
			// as the window moving to a display with a different scale factor.
			forceResize(Sdl3Window.width(), Sdl3Window.height());
		}

		frameArena = Arena.ofConfined();
		renderingOffscreen = FramePacing.uncapped() && offscreen != null;
		if (renderingOffscreen) {
			// No drawable is touched at all on an uncapped frame, which is the entire point: it is
			// acquiring one that blocks on the display.
			view = offscreen.view();
		} else {
			view = surface.currentView(frameArena);
			if (view.equals(MemorySegment.NULL)) {
				frameArena.close();
				frameArena = null;
				return false;
			}
		}

		frame = Frame.begin(ctx);
		return true;
	}

	/** Nanoseconds between presents while uncapped; one display refresh, assuming a fast panel. */
	private static final long PRESENT_INTERVAL_NANOS =
		1_000_000_000L / Math.max(1, Integer.getInteger("retroperf.presentHz", 120));

	private long nextPresentAt;

	/**
	 * Copies the offscreen frame into a drawable and presents it, if the display is ready.
	 *
	 * <p>Rate-limited rather than presenting every frame: at most one present per refresh keeps the
	 * compositor's queue empty, which is the property that makes uncapped rendering safe here.
	 */
	private void presentOffscreen() {
		long now = System.nanoTime();
		if (now < nextPresentAt) {
			// Superseded before it could be shown. That IS dropping a frame, and it is what lets the
			// render loop run ahead of the display.
			return;
		}
		nextPresentAt = now + PRESENT_INTERVAL_NANOS;

		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment drawable = surface.currentView(tmp);
			if (drawable.equals(MemorySegment.NULL)) {
				return;
			}
			com.periut.retrodragon.gpu.Blit.copy(ctx, offscreen.handle(), surface.currentTexture(),
				width, height);
			ctx.markFrameSubmitted();
			surface.present();
		}
	}

	/** Convenience for a frame that only clears: opens the encoder and one clearing pass. */
	public MemorySegment beginClearedFrame(float r, float g, float b, float a) {
		if (!beginFrame()) {
			return MemorySegment.NULL;
		}
		return frame.beginPass(frameArena, view, true, r, g, b, a, depth.view(), true);
	}

	public MemorySegment colorView() {
		return view;
	}

	public MemorySegment depthView() {
		return depth.view();
	}

	private void forceResize(int width, int height) {
		this.width = -1;
		resize(width, height);
	}

	/**
	 * Writes the frame that is about to be presented to a PNG.
	 *
	 * <p>Must be called after the draws are recorded and before {@link #endFrame()} -- the backbuffer
	 * is only valid between acquire and present. Stalls the GPU, so this is a diagnostic, not a
	 * feature.
	 *
	 * <p>It exists because there is no reliable way to screenshot the window from outside: a capture
	 * tool without screen-recording permission silently returns the desktop, which is indications
	 * indistinguishable from a window that draws nothing.
	 */
	public void screenshot(java.nio.file.Path path) {
		if (frame == null || view.equals(MemorySegment.NULL)) {
			return;
		}
		// Submit first: readback copies from the texture, and the draws have to have happened.
		frame.submit();
		byte[] pixels = com.periut.retrodragon.gpu.Readback.rgba(ctx, surface.currentTexture(),
			width, height);
		try {
			java.awt.image.BufferedImage image =
				new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
			boolean bgra = surface.format() == com.periut.webgpu.webgpu_h.WGPUTextureFormat_BGRA8Unorm();
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int i = (y * width + x) * 4;
					int c0 = pixels[i] & 0xFF;
					int c1 = pixels[i + 1] & 0xFF;
					int c2 = pixels[i + 2] & 0xFF;
					// The swapchain is BGRA on Metal; readback is raw bytes, so the swizzle is here.
					int rgb = bgra ? c2 << 16 | c1 << 8 | c0 : c0 << 16 | c1 << 8 | c2;
					image.setRGB(x, y, rgb);
				}
			}
			javax.imageio.ImageIO.write(image, "png", path.toFile());
			System.out.println("[RetroDragon] wrote " + path.toAbsolutePath());
		} catch (java.io.IOException e) {
			System.err.println("[RetroDragon] screenshot failed: " + e);
		}
	}

	/** Whether {@link #offscreen} currently holds a finished frame that can be read back. */
	private boolean lastFrameValid;

	/** Copies the drawable about to be presented into {@link #offscreen}. */
	private void captureLastFrame() {
		if (offscreen == null || surface.currentTexture().equals(MemorySegment.NULL)) {
			return;
		}
		com.periut.retrodragon.gpu.Blit.copy(ctx, surface.currentTexture(), offscreen.handle(),
			width, height);
		lastFrameValid = true;
	}

	/**
	 * {@code glReadPixels} against the last frame that was presented.
	 *
	 * <p>Reads {@link #offscreen}, which holds that frame in both modes -- an uncapped frame renders
	 * into it directly, and a presented one is copied into it by {@link #captureLastFrame()}. That
	 * indirection is the point: beta takes its screenshot after the swap, when the drawable it drew
	 * into no longer exists.
	 *
	 * <p>Rows go out BOTTOM-UP because that is where GL's origin is, and beta's
	 * {@code Screenshot.save} flips them back on the way into the image. Handing it top-down would
	 * produce a perfectly good upside-down screenshot.
	 *
	 * @param format GL_RGB or GL_RGBA; beta asks for GL_RGB
	 * @return false if there is nothing to read, so the caller can leave the buffer untouched
	 */
	public boolean readPixels(int x, int y, int w, int h, int format, java.nio.ByteBuffer out) {
		if (!lastFrameValid || offscreen == null || out == null || w <= 0 || h <= 0) {
			return false;
		}
		byte[] pixels = com.periut.retrodragon.gpu.Readback.rgba(ctx, offscreen.handle(), width, height);
		boolean bgra = offscreen.format()
			== com.periut.webgpu.webgpu_h.WGPUTextureFormat_BGRA8Unorm();
		int components = format == GL_RGBA ? 4 : 3;
		int base = out.position();
		for (int row = 0; row < h; row++) {
			// Flip: GL row 0 is the BOTTOM of the image, the readback's row 0 is the top.
			int sourceY = height - 1 - (y + row);
			if (sourceY < 0 || sourceY >= height) {
				continue;
			}
			for (int col = 0; col < w; col++) {
				int sourceX = x + col;
				if (sourceX < 0 || sourceX >= width) {
					continue;
				}
				int i = (sourceY * width + sourceX) * 4;
				int c0 = pixels[i] & 0xFF;
				int c1 = pixels[i + 1] & 0xFF;
				int c2 = pixels[i + 2] & 0xFF;
				int r = bgra ? c2 : c0;
				int b = bgra ? c0 : c2;
				int o = base + (row * w + col) * components;
				if (o + components > out.limit()) {
					return true;
				}
				out.put(o, (byte) r);
				out.put(o + 1, (byte) c1);
				out.put(o + 2, (byte) b);
				if (components == 4) {
					out.put(o + 3, (byte) (pixels[i + 3] & 0xFF));
				}
			}
		}
		return true;
	}

	private static final int GL_RGBA = 0x1908;

	/** Ends the pass, submits the frame, presents, and drives Dawn's callbacks. */
	public void endFrame() {
		if (frame == null) {
			return;
		}
		frame.close();
		frame = null;
		// Counted as in flight now that it is submitted, so the next frame can wait on it.
		ctx.markFrameSubmitted();
		// The cap is applied here, between submit and present: the frame is already recorded, so
		// waiting now shows the freshest possible image rather than an extra frame of latency.
		FramePacing.await();
		if (renderingOffscreen) {
			// The frame was drawn straight into `offscreen`, so it IS the last frame -- no copy
			// needed, only the flag that says readPixels may look at it.
			//
			// Without this, screenshots came out BLACK in Max FPS and only in Max FPS. The flag was
			// set solely by captureLastFrame(), which runs in the vsync branch, so the uncapped path
			// -- the one whose whole design is "render into offscreen" -- never marked the frame it
			// had just rendered as readable. readPixels then returned false and beta wrote out the
			// untouched buffer. It bites hardest under the bench, which forces the frame limiter off
			// and so takes every appearance A/B screenshot down this exact branch.
			lastFrameValid = true;
			presentOffscreen();
		} else {
			// Keep a copy of what is about to be shown. beta calls handleScreenshotKey() AFTER
			// Display.update(), so by the time it asks for pixels the drawable has been presented and
			// released and there is nothing left to read -- which is why screenshots came out black.
			//
			// Only in this branch: an uncapped frame already rendered into `offscreen`, so it is
			// the last frame by construction. Here the cost lands in the vsync path, where the GPU
			// is waiting on the display anyway, rather than in the one being optimised.
			captureLastFrame();
			surface.present();
		}
		view = MemorySegment.NULL;
		ctx.processEvents();
		frameArena.close();
		frameArena = null;
	}

	public Frame frame() {
		return frame;
	}

	public Arena frameArena() {
		return frameArena;
	}

	/** Whether the surface offers a present mode, so a silent fallback can be reported. */
	public boolean supportsPresentMode(int mode) {
		return surface.supportsPresentMode(mode);
	}

	public int surfaceFormat() {
		return surface.format();
	}

	public int depthFormat() {
		return depth.format();
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() {
		if (frame != null) {
			frame.close();
			frame = null;
		}
		// WAIT FOR THE GPU before releasing anything it might still be reading.
		//
		// Ordering alone is not enough, and that gap cost a hung desktop: the surface was released in
		// the right order -- before the window and its CAMetalLayer -- but while frames were still
		// executing. Unconfiguring and releasing a surface out from under work the compositor has not
		// finished with leaves it holding a reference to something being destroyed, and on macOS that
		// takes the whole render server down. The game exits cleanly and the desktop dies anyway,
		// which is why it looked like teardown had nothing to do with it.
		//
		// Time-bounded rather than spin-bounded: the per-frame wait must never stall a frame, but
		// here it is worth milliseconds to be certain the pipeline is empty.
		if (!ctx.drain(2000)) {
			// LEAK EVERYTHING, DELIBERATELY, and say so.
			//
			// The old behaviour was to log this and carry on releasing, which is the one thing the
			// drain exists to prevent -- every release below (the depth texture, the offscreen target,
			// the surface) is a release of something work still executing may be reading. "Free it
			// anyway" turns a failed wait into the exact hung-desktop case, so the warning was
			// describing the hazard while walking into it.
			//
			// Not releasing costs nothing that matters: this runs during process teardown, so the OS
			// reclaims the memory a few milliseconds later, and the GPU work finishes against objects
			// that are still alive. A leak that lasts until exit beats a reboot.
			System.err.println("[RetroDragon] GPU did not drain before teardown; "
				+ ctx.framesInFlight() + " frame(s) still in flight."
				+ " Leaving the surface and its textures alive rather than releasing them under"
				+ " live work -- they will go away with the process.");
			return;
		}
		if (frameArena != null) {
			frameArena.close();
			frameArena = null;
		}
		if (depth != null) {
			depth.close();
			depth = null;
		}
		if (offscreen != null) {
			offscreen.close();
			offscreen = null;
		}
		// Thread 0 on macOS, for the reason spelled out in resize(): unconfiguring and releasing a
		// surface is a CAMetalLayer operation, and thread 0 is running AppKit against that same layer
		// on its idle pump right up until the window is destroyed. This is the last and most dangerous
		// layer call of the run -- it is the one that hands the drawable pool back -- so it is the one
		// that least tolerates racing the window server.
		com.periut.retrodragon.window.MainThread.run(surface::close);
		arena.close();
	}
}
