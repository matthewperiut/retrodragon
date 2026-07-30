package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.window.sdl.Sdl3Events;
import com.periut.retrodragon.window.sdl.Sdl3Window;

import java.lang.foreign.MemorySegment;

/**
 * Opens a GL-free SDL3 window and clears it through WebGPU. {@code ./gradlew webgpuWindow}.
 *
 * <p>This is the first thing in the project that puts a WebGPU pixel on a display, and it exists
 * separately from the game because the surface path could not be tested at all until the window
 * layer learned to skip the GL context -- the two changes only mean something together.
 *
 * <p>Runs headless in CI with {@code -Dretroperf.frames=N}: it renders N frames and exits non-zero
 * if any of them failed to acquire a backbuffer, so "the swapchain works" is a check rather than a
 * thing someone has to look at.
 */
public final class WebGpuWindowTest {
	public static void main(String[] args) throws Exception {
		int frameLimit = Integer.getInteger("retroperf.frames", 0);
		boolean hidden = Boolean.getBoolean("retroperf.headless");

		if (!Sdl3Window.setNoGl(true)) {
			fail("window already exists -- nothing to test");
		}
		if (!Sdl3Window.create(854, 480, "RetroDragon -- WebGPU surface test", hidden)) {
			fail("SDL3 window creation failed");
		}
		System.out.println("window     = " + Sdl3Window.width() + "x" + Sdl3Window.height()
			+ (Sdl3Window.metalLayer() != 0L ? " (CAMetalLayer " + Long.toHexString(Sdl3Window.metalLayer()) + ")" : ""));
		// Whether the window is actually on screen is not something the render loop can tell you:
		// a surface over a hidden or zero-size window presents happily and shows nothing.
		// If the drawable is smaller than the window's physical pixels, the compositor upscales it
		// with a smooth filter and every hard pixel edge in the frame is softened -- indistinguishable
		// from anti-aliasing, and impossible to fix anywhere inside the renderer.
		System.out.println("density    = " + Sdl3Window.pixelsPerPoint() + " px/pt, drawable "
			+ Sdl3Window.width() + "x" + Sdl3Window.height()
			+ (Sdl3Window.pixelsPerPoint() > 1.01F ? " (HiDPI)" : " (1x)"));
		long flags = org.lwjgl.sdl.SDLVideo.SDL_GetWindowFlags(Sdl3Window.handle());
		System.out.println("flags      = 0x" + Long.toHexString(flags)
			+ (( flags & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN) != 0 ? " HIDDEN" : " shown")
			+ ((flags & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_MINIMIZED) != 0 ? " MINIMIZED" : "")
			+ ((flags & org.lwjgl.sdl.SDLVideo.SDL_WINDOW_METAL) != 0 ? " METAL" : ""));

		int presented = 0;
		int skipped = 0;
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("device     = ok (" + ctx.backendName() + ")");
			try (WebGpuRenderer renderer =
					WebGpuRenderer.create(ctx, Sdl3Window.width(), Sdl3Window.height())) {
				System.out.println("surface    = configured, format " + renderer.surfaceFormat()
					+ ", depth " + renderer.depthFormat());
				// Which present modes this surface actually offers. Max FPS depends on Mailbox
				// being available; without it the setting silently behaves like vsync.
				System.out.println("present    = fifo " + renderer.supportsPresentMode(
						com.periut.webgpu.webgpu_h.WGPUPresentMode_Fifo())
					+ ", mailbox " + renderer.supportsPresentMode(
						com.periut.webgpu.webgpu_h.WGPUPresentMode_Mailbox())
					+ ", immediate " + renderer.supportsPresentMode(
						com.periut.webgpu.webgpu_h.WGPUPresentMode_Immediate()));

				while (frameLimit == 0 || presented + skipped < frameLimit) {
					Sdl3Events.pump();
					if (Sdl3Events.closeRequested()) {
						break;
					}
					if (Sdl3Events.consumeResized()) {
						renderer.resize(Sdl3Window.width(), Sdl3Window.height());
						System.out.println("resize     = " + renderer.width() + "x" + renderer.height());
					}

					// A slow colour cycle, so a still screenshot cannot be mistaken for a frozen
					// window: if it is animating, the present loop is actually running.
					float t = (presented % 240) / 240.0F;
					MemorySegment pass = renderer.beginClearedFrame(
						0.15F + 0.15F * (float) Math.sin(t * Math.PI * 2),
						0.20F,
						0.35F + 0.15F * (float) Math.cos(t * Math.PI * 2),
						1.0F);
					if (pass.equals(MemorySegment.NULL)) {
						skipped++;
						continue;
					}
					renderer.endFrame();
					presented++;
				}
			}
		}

		System.out.println("frames     = " + presented + " presented, " + skipped + " skipped");
		Sdl3Window.destroy();
		if (presented == 0) {
			fail("no frame ever reached the screen");
		}
		System.out.println("WEBGPU WINDOW TEST PASSED");
	}

	private static void fail(String message) {
		System.err.println("WEBGPU WINDOW TEST FAILED: " + message);
		System.exit(1);
	}
}
