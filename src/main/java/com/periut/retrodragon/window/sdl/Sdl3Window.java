package com.periut.retrodragon.window.sdl;

import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * SDL3 window, GL context and native handles -- the replacement for the GLFW backend.
 *
 * Why SDL3 rather than GLFW, in the order the reasons actually matter here:
 *
 *  - It hands out the **native surface handles** (`wayland.surface`/`wayland.display`,
 *    `x11.window`, `win32.hwnd`, `cocoa.window`) that a WebGPU surface is created from, so the
 *    same window can host either the GL renderer or a future WebGPU one.
 *  - It handles Wayland pointer warping natively, replacing starac's custom C helper.
 *  - `SDL_WINDOW_HIDDEN` still yields a real GL context, which is a working **headless** mode for
 *    unattended screenshots and benchmarks. (The `dummy` video driver does not -- it has no GL.)
 *
 * Verified on Wayland/radeonsi: `SDL_GL_CONTEXT_PROFILE_COMPATIBILITY` gives GL 4.6 Compatibility,
 * which is the hard requirement -- beta's renderer is fixed-function throughout.
 */
public final class Sdl3Window {
	/** Kept dependency-free on purpose: this class initializes before the LWJGL/OS helpers do. */
	static final boolean MACOS = System.getProperty("os.name", "").toLowerCase().contains("mac");
	/** Same reason as {@link #MACOS}: no {@code OS.current()} here, it drags in the logger. */
	static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	/**
	 * The single source of truth for "are we on SDL3". {@code -Dretrowindow.sdl=false} reverts to
	 * GLFW; {@code -Dretrowindow.sdl=true} forces SDL3 back on where it is off by default.
	 *
	 * It lives here rather than being read in each shim because it was duplicated in Display, Mouse
	 * and Keyboard, and flipping the default in only one of them left the window on SDL3 while input
	 * still asked GLFW -- which owns no window, so the main menu stopped responding to the mouse
	 * entirely. One constant, one place to change.
	 *
	 * On by default everywhere, macOS included. SDL3's Cocoa backend only comes up on the process's
	 * FIRST thread, which needs both {@code -XstartOnFirstThread} and the call itself to run there;
	 * beta renders on a thread spawned by MinecraftApplet. {@link com.periut.retrodragon.window.MacBootstrap}
	 * resolves that by holding thread 0 and letting {@link com.periut.retrodragon.window.MainThread}
	 * marshal the handful of calls Cocoa restricts, so the game thread keeps the GL context and
	 * everything else stays where it was.
	 */
	public static final boolean ENABLED = !"false".equals(System.getProperty("retrowindow.sdl"));

	/**
	 * {@code -Dretrowindow.noGl=true}, or {@link #setNoGl} before {@link #create}, builds the window
	 * WITHOUT a GL context so a WebGPU surface can be created over it.
	 *
	 * <p>This is all-or-nothing by construction. A GL context and a WebGPU surface cannot share one
	 * window -- the surface is created from the raw native handle and takes ownership of presentation
	 * -- so the choice has to be made before {@code SDL_CreateWindow}, not after. That single fact is
	 * why the renderer picks its backend at startup rather than per-frame.
	 */
	public static final String NO_GL_PROPERTY = "retrowindow.noGl";

	/**
	 * {@code -Dretrowindow.highDpi=true|false} -- whether the drawable matches the display's physical
	 * pixels.
	 *
	 * <p><b>Off means a blurry window on any Retina display.</b> Without
	 * {@code SDL_WINDOW_HIGH_PIXEL_DENSITY} the backing surface stays at logical size -- 854x480 on a
	 * 3024x1964 panel -- and the window server scales it up with a smooth filter. Every hard pixel
	 * edge in the frame is softened, which looks exactly like anti-aliasing and cannot be fixed
	 * anywhere inside the renderer, because it happens after the frame is finished. In a game drawn
	 * from 16x16 pixel art that is the single most visible difference there is.
	 *
	 * <p>Defaults to ON for a GL-free (WebGPU) window and OFF for a GL one. Not because GL wants the
	 * blur, but because turning it on quadruples that backend's pixel count, and its performance
	 * numbers and post-process sizing were all measured without it. Set the property to opt either
	 * way.
	 */
	public static final String HIGH_DPI_PROPERTY = "retrowindow.highDpi";

	private static long window;
	private static long context;
	private static boolean initialized;
	private static boolean noGl = Boolean.getBoolean(NO_GL_PROPERTY);
	/** macOS only: the SDL_Metal view and the CAMetalLayer Dawn attaches to. */
	private static long metalView;
	private static long metalLayer;

	private Sdl3Window() {
	}

	public static boolean isCreated() {
		return window != 0L;
	}

	public static long handle() {
		return window;
	}

	/**
	 * Selects a GL-free window. Must be called before {@link #create}; after the window exists there
	 * is nothing to change, and silently doing nothing is friendlier than throwing into a renderer
	 * that is already half up.
	 *
	 * @return false if the window was already created, meaning the request had no effect
	 */
	public static boolean setNoGl(boolean value) {
		if (window != 0L) {
			return false;
		}
		noGl = value;
		return true;
	}

	public static boolean isNoGl() {
		return noGl;
	}

	/** See {@link #HIGH_DPI_PROPERTY}: defaults on without a GL context, off with one. */
	public static boolean highDpi() {
		String property = System.getProperty(HIGH_DPI_PROPERTY);
		return property == null ? noGl : !"false".equals(property);
	}

	/**
	 * The {@code CAMetalLayer} to build a WebGPU surface from on macOS; 0 elsewhere or when the
	 * window owns a GL context.
	 *
	 * <p>Dawn's Metal backend attaches to the LAYER, not the {@code NSWindow} -- handing it
	 * {@code SDL.window.cocoa.window} produces a surface that validates and then never presents.
	 */
	public static long metalLayer() {
		return metalLayer;
	}

	/**
	 * @param hidden true for headless: a real GL context with no mapped window.
	 * @return false if SDL or the context could not be created; the caller should fall back.
	 */
	/**
	 * Video init, window and context creation all happen on thread 0 (see
	 * {@link com.periut.retrodragon.window.MainThread}); the context is then handed to the calling
	 * render thread. Off macOS the marshalling collapses to a direct call.
	 */
	public static boolean create(int width, int height, String title, boolean hidden) {
		if (!com.periut.retrodragon.window.MainThread.get(() -> createOnMainThread(width, height, title, hidden))) {
			return false;
		}

		if (!noGl) {
			// The context was created on thread 0 and released there. Claiming it here is what makes
			// every later GL call -- all of which run on this thread -- legal.
			if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) {
				System.err.println("[RetroDragon] SDL_GL_MakeCurrent on the render thread failed: "
					+ SDLError.SDL_GetError());
				return false;
			}
			GL.createCapabilities();
		}
		// Thread 0 keeps the Cocoa run loop serviced from here on, so window close, resize and drag
		// stay live even when the game thread is not pumping (notably during shutdown).
		com.periut.retrodragon.window.MainThread.setIdleTask(org.lwjgl.sdl.SDLEvents::SDL_PumpEvents);
		System.out.println("[RetroDragon] SDL3 " + SDLVideo.SDL_GetCurrentVideoDriver()
			+ (noGl
				? ", no GL context (WebGPU surface"
					+ (metalLayer != 0L ? " via CAMetalLayer" : "") + ")"
				: ", GL " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION))
			+ ", " + width() + "x" + height() + " px at " + pixelsPerPoint() + "x"
			+ (hidden ? " (headless)" : "")
			+ (com.periut.retrodragon.window.MainThread.available() ? " (window on thread 0)" : ""));
		return true;
	}

	/** The thread-0 half of {@link #create}: everything Cocoa refuses to do anywhere else. */
	private static boolean createOnMainThread(int width, int height, String title, boolean hidden) {
		if (!initialized) {
			// BEFORE SDL_Init, not before SDL_CreateWindow: the app id is read when the video
			// backend comes up, and it is what the compositor matches against StartupWMClass in the
			// .desktop file injected below. Without it the toplevel gets SDL's default app id and
			// GNOME/KDE show a generic icon no matter what SDL_SetWindowIcon says. This is the SDL
			// equivalent of the GLFW path's glfwWindowHintString(GLFW_WAYLAND_APP_ID, ...), and it
			// covers X11 too -- SDL derives WM_CLASS from the same value.
			// Both operands are compile-time String constants, so nothing extra is class-loaded.
			org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_APP_ID,
				com.periut.retrodragon.window.lwjgl3compat.DesktopFileInjector.APP_ID);
			SDLInit.SDL_SetAppMetadata(com.periut.retrodragon.window.Starac.WINDOW_TITLE, "1.7.3",
				com.periut.retrodragon.window.lwjgl3compat.DesktopFileInjector.APP_ID);

			if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) {
				System.err.println("[RetroDragon] SDL_Init failed: " + SDLError.SDL_GetError());
				if (MACOS && !com.periut.retrodragon.window.MainThread.available()) {
					// Reachable when the bootstrap did not claim thread 0 -- usually a launcher
					// that dropped -XstartOnFirstThread. Say so; "No available video device" on
					// its own reads like a driver fault.
					System.err.println("[RetroDragon] SDL3 needs the process's first thread on macOS"
						+ " and nothing claimed it. Launch through MacBootstrap with"
						+ " -XstartOnFirstThread, or use -Dretrowindow.sdl=false for GLFW.");
				}
				return false;
			}
			initialized = true;

			// [NSApp setAppearance:nil] -- inherit the system light/dark appearance. On the GLFW
			// path this happened in Display.ensureInitialized(), which the SDL path never calls, and
			// without it the window chrome can stay stuck in aqua on a dark-mode system. We are on
			// thread 0 here by construction, so AppKit's main-thread assertion cannot fire.
			if (MACOS) {
				org.lwjgl.opengl.Display.initMacAppearance();
			}

			// The .desktop file is what carries StartupWMClass and the launcher icon on Wayland,
			// where there is no _NET_WM_ICON. Injected after SDL_Init (the video driver name is only
			// known once the backend is up) and before the toplevel is mapped, matching the GLFW
			// path's ordering. Skipped when hidden: an unattended headless run has no business
			// writing into the user's XDG data dir or spawning update-desktop-database.
			if (!hidden && isWayland()) {
				com.periut.retrodragon.window.lwjgl3compat.DesktopFileInjector.inject();
			}
		}

		if (!noGl) {
			// Compatibility profile is non-negotiable: beta uses glBegin, the matrix stack, display
			// lists and fixed-function lighting, none of which exist in a core profile.
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK,
				SDLVideo.SDL_GL_CONTEXT_PROFILE_COMPATIBILITY);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_DOUBLEBUFFER, 1);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_DEPTH_SIZE, 24);
			SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_STENCIL_SIZE, 8);
		}

		long flags = SDLVideo.SDL_WINDOW_RESIZABLE;
		if (highDpi()) {
			flags |= SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
		}
		if (!noGl) {
			flags |= SDLVideo.SDL_WINDOW_OPENGL;
		} else if (MACOS) {
			// SDL_WINDOW_METAL makes SDL give the window a Metal-capable content view up front.
			// SDL_Metal_CreateView still works without it, but the hint costs nothing and keeps the
			// window's backing consistent from creation rather than after the fact.
			flags |= SDLVideo.SDL_WINDOW_METAL;
		}
		// Windows: come up hidden so the DWM attributes land BEFORE the window is ever composited.
		// Set them on a visible window instead and a dark-themed desktop gets a white titlebar for
		// a frame or two. The GLFW path does exactly this (GLFW_VISIBLE=0, init, glfwShowWindow).
		boolean showAfterTheming = WINDOWS && !hidden;
		if (hidden || showAfterTheming) {
			flags |= SDLVideo.SDL_WINDOW_HIDDEN;
		}
		window = SDLVideo.SDL_CreateWindow(title, width, height, flags);
		if (window == 0L) {
			System.err.println("[RetroDragon] SDL_CreateWindow failed: " + SDLError.SDL_GetError());
			return false;
		}

		if (showAfterTheming) {
			// Dark titlebar + Mica, while nothing has composited the window yet. Routed through
			// Display because WindowsDisplayHelper is package-private in org.lwjgl.opengl.
			org.lwjgl.opengl.Display.initWindowsTitlebar(win32Hwnd());
		}

		if (noGl) {
			if (MACOS && !createMetalLayer()) {
				SDLVideo.SDL_DestroyWindow(window);
				window = 0L;
				return false;
			}
		} else {
			context = SDLVideo.SDL_GL_CreateContext(window);
			if (context == 0L) {
				System.err.println("[RetroDragon] SDL_GL_CreateContext failed: " + SDLError.SDL_GetError());
				SDLVideo.SDL_DestroyWindow(window);
				window = 0L;
				return false;
			}
			// Creating a context makes it current on the creating thread. Release it so the render
			// thread can take it; a context current on two threads is undefined behaviour.
			SDLVideo.SDL_GL_MakeCurrent(window, 0L);
		}

		// Last, so the first thing the compositor ever sees is a themed window with its backing
		// surface already in place. Mirrors the GLFW path's glfwShowWindow ordering.
		if (showAfterTheming) {
			SDLVideo.SDL_ShowWindow(window);
		}
		return true;
	}

	/**
	 * macOS: attaches a Metal-backed subview and takes its {@code CAMetalLayer}.
	 *
	 * <p>Thread 0 only, like every other AppKit call here -- it adds a view to the window.
	 */
	private static boolean createMetalLayer() {
		metalView = org.lwjgl.sdl.SDLMetal.SDL_Metal_CreateView(window);
		if (metalView == 0L) {
			System.err.println("[RetroDragon] SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
			return false;
		}
		metalLayer = org.lwjgl.sdl.SDLMetal.SDL_Metal_GetLayer(metalView);
		if (metalLayer == 0L) {
			System.err.println("[RetroDragon] SDL_Metal_GetLayer returned NULL: " + SDLError.SDL_GetError());
			org.lwjgl.sdl.SDLMetal.SDL_Metal_DestroyView(metalView);
			metalView = 0L;
			return false;
		}
		return true;
	}

	/**
	 * Framebuffer size in PIXELS, not logical units -- they differ under display scaling, and the
	 * renderer needs pixels. This is what Display.getWidth/getHeight must return; forgetting it
	 * made the whole frame render at 1x1.
	 */
	public static int width() {
		return size(true);
	}

	public static int height() {
		return size(false);
	}

	/**
	 * Framebuffer pixels per logical point. 1.0 on an unscaled display; 2.0 on a HiDPI one.
	 *
	 * Pointer coordinates arrive in points while the frame is drawn in pixels, so every mouse
	 * coordinate has to be multiplied by this before beta sees it.
	 */
	public static float pixelsPerPoint() {
		if (window == 0L) {
			return 1.0F;
		}
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.IntBuffer pw = stack.mallocInt(1);
			java.nio.IntBuffer ph = stack.mallocInt(1);
			java.nio.IntBuffer lw = stack.mallocInt(1);
			java.nio.IntBuffer lh = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowSizeInPixels(window, pw, ph);
			SDLVideo.SDL_GetWindowSize(window, lw, lh);
			int logical = lw.get(0);
			return logical <= 0 ? 1.0F : (float) pw.get(0) / logical;
		}
	}

	private static int size(boolean wantWidth) {
		if (window == 0L) {
			return 0;
		}
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.IntBuffer w = stack.mallocInt(1);
			java.nio.IntBuffer h = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowSizeInPixels(window, w, h);
			return wantWidth ? w.get(0) : h.get(0);
		}
	}

	/**
	 * Runs at buffer-swap time when the window has no GL context.
	 *
	 * <p>A GL-free window still has a frame boundary -- the game calls {@code Display.update()} once
	 * per frame regardless -- but nothing here knows how the frame gets to the screen. Whoever owns
	 * the surface (RetroDragon's WebGPU renderer) installs the present call here, which keeps this
	 * class free of any dependency on a graphics API it does not create.
	 */
	private static Runnable presentHook;

	public static void setPresentHook(Runnable hook) {
		presentHook = hook;
	}

	/**
	 * Runs at the START of {@link #destroy}, before the Metal view or the window go away.
	 *
	 * <p>Ordering is the whole point. A WebGPU surface holds a reference to the window's
	 * {@code CAMetalLayer}, and may be holding an acquired-but-unpresented drawable. Destroying the
	 * layer out from under it leaves the compositor waiting on a drawable that will never be
	 * returned -- which does not crash the game, it wedges the SYSTEM render server, and the only way
	 * out is a reboot. Whoever created the surface has to tear it down first.
	 */
	private static Runnable shutdownHook;

	public static void setShutdownHook(Runnable hook) {
		shutdownHook = hook;
	}

	/** Swaps under GL; hands off to the present hook without one. */
	public static void swapBuffers() {
		if (window == 0L) {
			return;
		}
		if (noGl) {
			if (presentHook != null) {
				presentHook.run();
			}
			return;
		}
		SDLVideo.SDL_GL_SwapWindow(window);
	}

	// --- RetroCenter window handoff -----------------------------------------------------------
	//
	// The hub and an in-process child instance render on DIFFERENT threads into the SAME window and
	// the SAME GL context, one at a time. A GL context current on two threads at once is undefined
	// behaviour, so ownership moves by releasing on one thread and claiming on the other -- exactly
	// what create() already does once between thread 0 and the render thread (see there). These are
	// that same handoff, exposed so org.lwjgl.opengl.Display can drive it repeatedly.
	//
	// Deliberately NOT marshalled to thread 0: SDL_GL_MakeCurrent is legal on any thread and MUST
	// run on the thread that is going to issue the GL calls. Marshalling it would bind the context
	// to thread 0 and leave the caller with no context at all.

	/**
	 * Releases the GL context from the calling thread. The window, the context and everything in it
	 * (textures, display lists, the framebuffer's current contents) survive untouched.
	 */
	public static void releaseContext() {
		if (window != 0L && !noGl) {
			SDLVideo.SDL_GL_MakeCurrent(window, 0L);
		}
	}

	/**
	 * Claims the window's GL context on the calling thread and rebuilds LWJGL's per-thread
	 * capabilities for it.
	 *
	 * @return false when there is no window/context to claim, or SDL refused the bind.
	 */
	public static boolean acquireContext() {
		if (window == 0L || noGl || context == 0L) {
			return false;
		}
		if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) {
			System.err.println("[RetroDragon] SDL_GL_MakeCurrent (RetroCenter handoff) failed: "
				+ SDLError.SDL_GetError());
			return false;
		}
		GL.createCapabilities();
		return true;
	}

	/** 0 = adaptive off, 1 = vsync, -1 = adaptive vsync where supported. */
	public static void setSwapInterval(int interval) {
		if (window != 0L && !noGl) {
			SDLVideo.SDL_GL_SetSwapInterval(interval);
		}
	}

	/**
	 * Marshalled: retitling resizes the titlebar, and AppKit hard-asserts
	 * ("NSWindow geometry should only be modified on the main thread!") rather than misbehaving
	 * quietly. Beta sets the title during startup and on FPS updates, so this runs off the game
	 * thread constantly.
	 */
	public static void setTitle(String title) {
		if (window != 0L) {
			com.periut.retrodragon.window.MainThread.run(() -> SDLVideo.SDL_SetWindowTitle(window, title));
		}
	}

	/**
	 * Multi-size window icon: taskbar, Alt-Tab, X11 {@code _NET_WM_ICON}, Wayland xdg-toplevel.
	 *
	 * <p>SDL takes ONE base surface plus alternates and lets the compositor pick the size it wants,
	 * so the largest image becomes the base and the rest are attached to it. The alternates have to
	 * be attached BEFORE {@code SDL_SetWindowIcon}: SDL converts only the BASE surface and re-adds
	 * the very same alternate surface OBJECTS to the converted icon by reference (refcount++), so
	 * anything attached afterwards is not seen -- and every alternate has to stay valid for as long
	 * as the window holds its icon, which is why SDL owns their pixels here (see below).
	 *
	 * <p>The buffers arrive in memory order A,R,G,B (see {@code MinecraftMixin}), which is exactly
	 * what the endian-aware {@code SDL_PIXELFORMAT_ARGB32} alias means, so unlike the GLFW path
	 * there is no channel-reordering loop here.
	 *
	 * <p>Marshalled to thread 0 for the same reason {@link #setTitle} is: this repaints the titlebar
	 * and the dock tile, and AppKit hard-asserts on off-main-thread window changes.
	 *
	 * @param argb one or more square ARGB images; positions are left untouched for the caller
	 */
	public static void setIcon(java.nio.ByteBuffer[] argb) {
		if (window == 0L || argb == null || argb.length == 0) {
			return;
		}
		com.periut.retrodragon.window.MainThread.run(() -> {
			java.util.List<org.lwjgl.sdl.SDL_Surface> surfaces = new java.util.ArrayList<>();
			try {
				// Largest first: index 0 becomes the base, everything after it an alternate.
				// Nulls are dropped BEFORE the sort -- Display.setIcon is public API and a null
				// element would otherwise blow up inside the comparator rather than here.
				java.util.List<java.nio.ByteBuffer> sorted = new java.util.ArrayList<>(argb.length);
				for (java.nio.ByteBuffer src : argb) {
					if (src != null) {
						sorted.add(src);
					}
				}
				sorted.sort(java.util.Comparator.comparingInt(java.nio.ByteBuffer::remaining).reversed());
				for (java.nio.ByteBuffer src : sorted) {
					int dim = (int) Math.sqrt(src.remaining() / 4.0);
					if (dim <= 0 || dim * dim * 4 != src.remaining()) {
						continue; // not a square RGBA image; the loader only ever produces those
					}
					// SDL_CreateSurface, NOT SDL_CreateSurfaceFrom: SDL must own the pixels.
					// SDL_SetWindowIcon retains the ALTERNATE surfaces by reference (its
					// SDL_ConvertSurface re-adds them to window->icon and bumps their refcount), so
					// they outlive this call -- freeing pixels we allocated ourselves would leave
					// live SDL_Surfaces pointing at freed memory. With SDL-allocated pixels,
					// SDL_DestroySurface below only drops OUR reference and the surface + its pixels
					// are released together when the window releases its icon.
					org.lwjgl.sdl.SDL_Surface surface = org.lwjgl.sdl.SDLSurface.SDL_CreateSurface(
						dim, dim, org.lwjgl.sdl.SDLPixels.SDL_PIXELFORMAT_ARGB32);
					if (surface == null) {
						continue;
					}
					java.nio.ByteBuffer dst = surface.pixels();
					if (dst == null) {
						org.lwjgl.sdl.SDLSurface.SDL_DestroySurface(surface);
						continue;
					}
					// Row by row through the surface's own pitch, which is padded for SIMD
					// alignment and is therefore not necessarily dim * 4.
					int pitch = surface.pitch();
					java.nio.ByteBuffer row = src.duplicate();
					for (int y = 0; y < dim; y++) {
						row.limit(row.position() + dim * 4);
						dst.position(y * pitch).put(row);
						row.limit(src.limit());
					}
					surfaces.add(surface);
				}
				if (surfaces.isEmpty()) {
					return;
				}
				for (int i = 1; i < surfaces.size(); i++) {
					org.lwjgl.sdl.SDLSurface.SDL_AddSurfaceAlternateImage(surfaces.get(0), surfaces.get(i));
				}
				// SDL CONVERTS the base surface into its own icon representation, so the base is
				// genuinely done with once this returns -- but the alternates are only RETAINED, so
				// the DestroySurface loop below drops our reference without freeing them.
				if (!SDLVideo.SDL_SetWindowIcon(window, surfaces.get(0))) {
					System.err.println("[RetroDragon] SDL_SetWindowIcon failed: " + SDLError.SDL_GetError());
				}
			} finally {
				// Mandatory even though SDL keeps the alternates alive: without it every alternate
				// stays at refcount 2 and leaks when the window drops its icon.
				for (org.lwjgl.sdl.SDL_Surface surface : surfaces) {
					org.lwjgl.sdl.SDLSurface.SDL_DestroySurface(surface);
				}
			}
		});
	}

	public static void destroy() {
		// Before anything else: whoever owns a surface over this window must release it while the
		// window and its layer still exist. See setShutdownHook.
		if (shutdownHook != null) {
			try {
				shutdownHook.run();
			} catch (Throwable t) {
				System.err.println("[RetroDragon] shutdown hook failed: " + t);
			}
			shutdownHook = null;
		}
		// The context belongs to the render thread that made it current, so it is released here;
		// the window and the video subsystem belong to thread 0 and must be torn down there.
		if (context != 0L) {
			SDLVideo.SDL_GL_DestroyContext(context);
			context = 0L;
		}
		com.periut.retrodragon.window.MainThread.run(() -> {
			// The Metal view is a subview of the window, so it goes first -- destroying the window
			// out from under it leaves the layer dangling, and Dawn may still hold a reference.
			if (metalView != 0L) {
				org.lwjgl.sdl.SDLMetal.SDL_Metal_DestroyView(metalView);
				metalView = 0L;
				metalLayer = 0L;
			}
			if (window != 0L) {
				SDLVideo.SDL_DestroyWindow(window);
				window = 0L;
			}
			if (initialized) {
				SDLInit.SDL_Quit();
				initialized = false;
			}
		});
	}

	// --- native handles, for a WebGPU surface -------------------------------------------------
	// jWebGPU takes these as jParser NativeObjects wrapping the raw address:
	//     new NativeObject().native_setAddress(waylandDisplay())
	// See DESIGN-retroperf-webgpu.md.

	public static boolean isWayland() {
		return "wayland".equals(SDLVideo.SDL_GetCurrentVideoDriver());
	}

	public static long waylandDisplay() {
		return pointer("SDL.window.wayland.display");
	}

	public static long waylandSurface() {
		return pointer("SDL.window.wayland.surface");
	}

	public static long x11Display() {
		return pointer("SDL.window.x11.display");
	}

	public static long x11Window() {
		return number("SDL.window.x11.window");
	}

	public static long win32Hwnd() {
		return pointer("SDL.window.win32.hwnd");
	}

	/** The module handle Dawn wants alongside the HWND when creating a Windows surface. */
	public static long win32Instance() {
		return pointer("SDL.window.win32.instance");
	}

	public static long cocoaWindow() {
		return pointer("SDL.window.cocoa.window");
	}

	private static long pointer(String name) {
		return window == 0L ? 0L
			: SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window), name, 0L);
	}

	private static long number(String name) {
		return window == 0L ? 0L
			: SDLProperties.SDL_GetNumberProperty(SDLVideo.SDL_GetWindowProperties(window), name, 0L);
	}
}
