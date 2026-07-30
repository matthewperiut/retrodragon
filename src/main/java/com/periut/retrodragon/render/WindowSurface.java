package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.Surface;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.window.sdl.Sdl3Window;

import java.lang.foreign.Arena;

/**
 * Builds a WebGPU {@link Surface} from whatever native handle the SDL3 window turned out to have.
 *
 * <p>This is the one place the two libraries meet: {@code retrowindow} knows how to get handles out
 * of SDL and nothing about WebGPU; {@code retrogpu} knows how to wrap a handle and nothing about
 * windows. Keeping the join here is also what lets {@code retrowindow} stay on Java 21 while the
 * FFM bindings need 25.
 */
public final class WindowSurface {
	private WindowSurface() {
	}

	/**
	 * @throws IllegalStateException if the window has no handle this platform's Dawn backend accepts
	 *         -- which on macOS almost always means the window was built with a GL context, so
	 *         {@code SDL_Metal_CreateView} was never called.
	 */
	public static Surface create(WebGPUContext ctx, Arena arena) {
		if (!Sdl3Window.isCreated()) {
			throw new IllegalStateException("no SDL window to create a surface over");
		}

		long metalLayer = Sdl3Window.metalLayer();
		if (metalLayer != 0L) {
			return Surface.fromMetalLayer(ctx, arena, metalLayer);
		}

		long hwnd = Sdl3Window.win32Hwnd();
		if (hwnd != 0L) {
			return Surface.fromWin32(ctx, arena, Sdl3Window.win32Instance(), hwnd);
		}

		if (Sdl3Window.isWayland()) {
			long display = Sdl3Window.waylandDisplay();
			long wlSurface = Sdl3Window.waylandSurface();
			if (display != 0L && wlSurface != 0L) {
				return Surface.fromWayland(ctx, arena, display, wlSurface);
			}
		}

		long x11Display = Sdl3Window.x11Display();
		long x11Window = Sdl3Window.x11Window();
		if (x11Display != 0L && x11Window != 0L) {
			return Surface.fromXlib(ctx, arena, x11Display, x11Window);
		}

		throw new IllegalStateException("no usable native window handle for a WebGPU surface"
			+ " (video driver " + org.lwjgl.sdl.SDLVideo.SDL_GetCurrentVideoDriver()
			+ ", noGl=" + Sdl3Window.isNoGl() + ")");
	}
}
