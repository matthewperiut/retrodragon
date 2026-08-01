package com.periut.retrodragon.window.sdl;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

/**
 * SDL3 event pump, and the window state beta reads through the LWJGL 2 API.
 *
 * SDL delivers everything through one queue, so this drains it once per frame and fans the results
 * out: window focus/close/resize land here, keyboard and mouse events are handed to the input
 * shims. That mirrors what GLFW's per-event callbacks did, minus the callback lifetime hazard --
 * GLFW upcall stubs are freed when their Java object is collected, and a freed stub segfaults the
 * JVM, which is why the GLFW path has to strongly reference every callback.
 */
public final class Sdl3Events {
	private static boolean closeRequested;
	private static boolean focused = true;
	private static boolean resized;
	private static boolean themeChanged;
	private static int width;
	private static int height;

	private Sdl3Events() {
	}

	/**
	 * Drains the queue. Call once per frame from Display.update.
	 *
	 * Bounced to thread 0 on macOS: SDL_PollEvent pumps the Cocoa run loop, which AppKit only
	 * permits on the process's first thread. The handlers below mutate plain statics that the game
	 * thread reads afterwards, so one marshalled call per frame covers the whole event batch.
	 */
	public static void pump() {
		com.periut.retrodragon.window.MainThread.run(Sdl3Events::pumpHere);
	}

	private static void pumpHere() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_Event event = SDL_Event.malloc(stack);
			while (SDLEvents.SDL_PollEvent(event)) {
				dispatch(event);
			}
		}
	}

	/**
	 * Blocks up to {@code timeoutMs} for one event, then drains whatever else arrived with it.
	 *
	 * <p>RetroCenter's hub park loop, and nothing else. While an in-process child instance owns the
	 * window, the hub's render thread still owns the EVENT QUEUE -- it created the window, and on
	 * Wayland the child's {@code SDL_GL_SwapWindow} blocks on frame callbacks that are delivered
	 * through this queue. So the hub cannot simply sleep: it has to keep dispatching at monitor rate
	 * or the child's framerate collapses to the poll interval. A timed WAIT rather than a poll+sleep
	 * because it wakes on the event, not on the timer, and costs nothing while idle.
	 *
	 * <p>Same thread-0 marshalling as {@link #pump()} for the same Cocoa reason. That means thread 0
	 * is occupied for up to {@code timeoutMs} per call on macOS, which is why the timeout is short.
	 */
	public static void pumpWait(int timeoutMs) {
		com.periut.retrodragon.window.MainThread.run(() -> pumpWaitHere(timeoutMs));
	}

	private static void pumpWaitHere(int timeoutMs) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_Event event = SDL_Event.malloc(stack);
			// SDL_WaitEventTimeout REMOVES the event it waited for, so it has to be dispatched here
			// too -- falling through to the drain loop would silently drop one event per wait.
			if (SDLEvents.SDL_WaitEventTimeout(event, timeoutMs)) {
				dispatch(event);
				while (SDLEvents.SDL_PollEvent(event)) {
					dispatch(event);
				}
			}
		}
	}

	/**
	 * Breaks a {@link #pumpWait} immediately from any thread.
	 *
	 * <p>The SDL counterpart of {@code glfwPostEmptyEvent}: RetroCenter's child launcher calls it
	 * when the child's game ends so the parked hub resumes on the next millisecond instead of
	 * waiting out the timeout. {@code SDL_PushEvent} is documented thread-safe. The event itself is
	 * inert -- {@code SDL_EVENT_USER} falls through {@link #dispatch}'s default into
	 * {@code Sdl3Input.handle}, which ignores types it does not know.
	 */
	public static void postWake() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_Event event = SDL_Event.calloc(stack);
			event.type(SDLEvents.SDL_EVENT_USER);
			SDLEvents.SDL_PushEvent(event);
		}
	}

	private static void dispatch(SDL_Event event) {
		switch (event.type()) {
			case SDLEvents.SDL_EVENT_QUIT, SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED ->
				closeRequested = true;
			case SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED -> focused = true;
			case SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST -> focused = false;
			// Anything that can change the framebuffer's PIXEL size. The last three matter on macOS
			// and are why this list is not just RESIZED: dragging a window from a 1x display onto a
			// Retina one leaves the window the same size in POINTS, so RESIZED may never fire, while
			// the drawable the renderer has to match doubles in both axes.
			case SDLEvents.SDL_EVENT_WINDOW_RESIZED,
				SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED,
				SDLEvents.SDL_EVENT_WINDOW_METAL_VIEW_RESIZED,
				SDLEvents.SDL_EVENT_WINDOW_DISPLAY_CHANGED,
				SDLEvents.SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED -> {
				// Deliberately NOT event.window().data1()/data2(). Those two fields carry different
				// units per event type -- RESIZED reports logical points, PIXEL_SIZE_CHANGED reports
				// pixels, and DISPLAY_CHANGED reports a display ID in data1 and nothing in data2 --
				// so reading them into one pair of fields mixed three coordinate spaces into one.
				// Asking the window is unambiguous, and this is the one thread allowed to ask.
				Sdl3Window.refreshSize();
				width = Sdl3Window.width();
				height = Sdl3Window.height();
				resized = true;
			}
			// The user flipped the OS between light and dark. Windows is the only place we act
			// on it (the titlebar is ours to paint through DWM), but SDL raises it on every
			// backend, so it is consumed here and read by Display.update.
			case SDLEvents.SDL_EVENT_SYSTEM_THEME_CHANGED -> themeChanged = true;
			default -> Sdl3Input.handle(event);
		}
	}

	public static boolean closeRequested() {
		return closeRequested;
	}

	/**
	 * Asks the game to quit exactly as clicking the window's close button would.
	 *
	 * <p>The point is that it goes through the game's OWN shutdown: it saves, tears down the
	 * renderer, releases the surface and then destroys the window, in that order. Killing the
	 * process instead leaves the compositor holding a drawable that is never returned, which on
	 * macOS can take the system render server down with it.
	 */
	public static void requestClose() {
		closeRequested = true;
	}

	public static boolean focused() {
		return focused;
	}

	public static int width() {
		return width;
	}

	public static int height() {
		return height;
	}

	/** Consumes the resize flag, matching the LWJGL 2 {@code wasResized} contract. */
	public static boolean consumeResized() {
		boolean was = resized;
		resized = false;
		return was;
	}

	/**
	 * Consumes the "system light/dark theme changed" flag.
	 *
	 * <p>This is the SDL3 replacement for starac's 2-second {@code reg query} subprocess poll on
	 * Windows: the compositor tells us, so nothing has to keep asking. The poll survives as a
	 * fallback for the configurations where {@code SDL_GetSystemTheme} answers UNKNOWN.
	 */
	public static boolean consumeThemeChanged() {
		boolean was = themeChanged;
		themeChanged = false;
		return was;
	}
}
