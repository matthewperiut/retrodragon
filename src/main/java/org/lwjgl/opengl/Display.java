package org.lwjgl.opengl;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

import com.periut.retrodragon.window.MainThread;
import com.periut.retrodragon.window.lwjgl3compat.DesktopFileInjector;
import com.periut.retrodragon.window.lwjgl3compat.wayland.WaylandCenterCursor;
import com.periut.retrodragon.window.lwjgl3compat.util.OS;
import com.periut.retrodragon.window.sdl.Sdl3Events;
import com.periut.retrodragon.window.sdl.Sdl3Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.glfw.GLFWImage.Buffer;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class Display {
	/**
	 * {@code -Dretrowindow.sdl=false} falls back to GLFW. SDL3 is the DEFAULT path: it owns the
	 * window, GL context and event pump, and it is what the WebGPU backend attaches its surface to,
	 * so it has to be the one that gets exercised. GLFW is kept only as an A/B escape hatch, on
	 * every platform including macOS -- SDL3 cannot initialize video off the process's first
	 * thread there, which is what MacBootstrap holds thread 0 for. Selecting GLFW also forces the
	 * GL renderer, since a WebGPU surface needs a native handle only the SDL3 path exposes.
	 * See {@link com.periut.retrodragon.window.sdl.Sdl3Window#ENABLED}.
	 */
	private static final boolean SDL = com.periut.retrodragon.window.sdl.Sdl3Window.ENABLED;
	/** {@code -Dretrowindow.headless=true}: real GL context, no mapped window. SDL path only. */
	private static final boolean HEADLESS = Boolean.getBoolean("retrowindow.headless");

	@NotNull
	private static String title = "";
	private static long handle = -1L;
	private static boolean resizable;
	@NotNull
	private static DisplayMode displayMode = new DisplayMode(640, 480, 24, 60);
	private static int width;
	private static int height;
	private static int xPos;
	private static int yPos;
	private static boolean window_resized = true;
	@Nullable
	private static GLFWWindowSizeCallback sizeCallback;
	// Must be strongly referenced: LWJGL upcall stubs are freed when their
	// Java callback object is GC'd, and a freed stub SIGSEGVs the JVM the
	// next time the native side fires it (upcall_stub_load_target crashes).
	@Nullable
	private static GLFWWindowFocusCallback focusCallback;
	@Nullable
	private static ByteBuffer[] cached_icons = null;
	private static boolean focused;
	private static boolean glfwInitialized = false;
	private static boolean usingGlfwAsync = false;
	private static boolean cinnamonSizeLimitActive = false;
	private static boolean forceX11Fallback = false;
	/**
	 * Windows + SDL only: true when {@code SDL_GetSystemTheme} gave a real answer at window
	 * creation, which means the theme can be tracked from SDL's event instead of by re-running the
	 * {@code reg query} subprocess every two seconds forever.
	 */
	private static boolean sdlThemeFromEvents = false;
	/**
	 * Wayland + SDL only: the last title written into the injected .desktop file.
	 *
	 * <p>Beta calls {@link #setTitle} on every FPS update -- about once a second -- and
	 * {@code DesktopFileInjector.updateTitle} is a file read, a regex, a file write and an
	 * {@code update-desktop-database} subprocess. Only the base title is meaningful in a launcher
	 * entry, so the FPS suffix is stripped and an unchanged title costs nothing.
	 */
	@Nullable
	private static String lastInjectedTitle = null;

	private Display() {
	}

	// --- hooks for the SDL3 backend ------------------------------------------------------------
	// MacOSDisplayHelper and WindowsDisplayHelper are package-private in org.lwjgl.opengl, and the
	// SDL classes live in com.periut.retrodragon.window.sdl. Rather than widening the helpers (which
	// would put raw ObjC/DWM entry points on the public API of a shim package), the two calls the
	// SDL path needs are exposed here.

	/**
	 * macOS: {@code [NSApp setAppearance:nil]} so the app inherits the system light/dark appearance.
	 * Called from {@code Sdl3Window.createOnMainThread} once SDL_Init has succeeded; the GLFW path
	 * does the same thing from {@link #ensureInitialized()}.
	 */
	public static void initMacAppearance() {
		MacOSDisplayHelper.initAppAppearance();
	}

	/**
	 * Windows: dark titlebar + Mica backdrop for an already-created native window.
	 * Called from {@code Sdl3Window.createOnMainThread} with the HWND SDL publishes on
	 * {@code SDL_PROP_WINDOW_WIN32_HWND_POINTER}, while the window is still hidden.
	 */
	public static void initWindowsTitlebar(long hwnd) {
		WindowsDisplayHelper.initFromHwnd(hwnd);
	}

	private static boolean needsLibdecor() {
		String desktop = System.getenv("XDG_CURRENT_DESKTOP");
		if (desktop == null) return false;
		String upper = desktop.toUpperCase();
		return upper.contains("GNOME") || upper.contains("CINNAMON");
	}

	private static boolean isCinnamonWayland() {
		String desktop = System.getenv("XDG_CURRENT_DESKTOP");
		return desktop != null && desktop.toUpperCase().contains("CINNAMON")
				&& GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
	}

	/**
	 * Sets XCURSOR_THEME and XCURSOR_SIZE from gsettings so GLFW uses the
	 * user's configured cursor theme on Wayland. GNOME (and some other DEs)
	 * set the cursor via gsettings but don't export the env vars.
	 */
	private static void setupCursorTheme() {
		try {
			// Skip if already set
			if (System.getenv("XCURSOR_THEME") != null) {
				return;
			}

			org.lwjgl.system.SharedLibrary libc = org.lwjgl.system.APIUtil.apiCreateLibrary("libc.so.6");
			long setenv = libc.getFunctionAddress("setenv");
			if (setenv == 0) return;

			// Read cursor theme from gsettings
			try {
				Process p = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "cursor-theme").start();
				String output = new String(p.getInputStream().readAllBytes()).trim();
				p.waitFor();
				// gsettings wraps in single quotes: 'Adwaita'
				if (output.contains("'")) {
					String theme = output.split("'")[1];
					nativeSetenv(setenv, "XCURSOR_THEME", theme);
					System.out.println("[RetroDragon] Set XCURSOR_THEME=" + theme);
				}
			} catch (Exception ignored) {}

			// Read cursor size
			if (System.getenv("XCURSOR_SIZE") == null) {
				try {
					Process p = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "cursor-size").start();
					String output = new String(p.getInputStream().readAllBytes()).trim();
					p.waitFor();
					if (!output.isEmpty()) {
						nativeSetenv(setenv, "XCURSOR_SIZE", output);
						System.out.println("[RetroDragon] Set XCURSOR_SIZE=" + output);
					}
				} catch (Exception ignored) {}
			}
		} catch (Exception e) {
			System.out.println("[RetroDragon] Could not setup cursor theme: " + e.getMessage());
		}
	}

	/**
	 * Sets an environment variable in the native process environment (visible
	 * to native libraries via getenv, unlike System.setProperty). Works on
	 * Linux (libc) and macOS (libSystem). Must be called before the library
	 * that reads the variable is loaded/initialized.
	 */
	public static void setNativeEnv(String name, String value) {
		try {
			String libName = OS.current() == OS.OSX ? "libSystem.B.dylib" : "libc.so.6";
			org.lwjgl.system.SharedLibrary libc = org.lwjgl.system.APIUtil.apiCreateLibrary(libName);
			long setenv = libc.getFunctionAddress("setenv");
			if (setenv == 0) {
				System.out.println("[RetroDragon] Could not find setenv in " + libName);
				return;
			}
			nativeSetenv(setenv, name, value);
		} catch (Exception e) {
			System.out.println("[RetroDragon] Could not set " + name + ": " + e.getMessage());
		}
	}

	private static void nativeSetenv(long setenvAddr, String name, String value) {
		ByteBuffer nameBuf = MemoryUtil.memASCII(name, true);
		ByteBuffer valueBuf = MemoryUtil.memASCII(value, true);
		org.lwjgl.system.JNI.invokePPI(MemoryUtil.memAddress(nameBuf), MemoryUtil.memAddress(valueBuf), 1, setenvAddr);
		MemoryUtil.memFree(nameBuf);
		MemoryUtil.memFree(valueBuf);
	}

	/**
	 * Sets up native Wayland decorations by:
	 * 1. Extracting a patched libdecor-gtk plugin (thread check removed)
	 * 2. Setting GDK_BACKEND=wayland so GTK initializes correctly
	 * 3. Setting LIBDECOR_PLUGIN_DIR to point to our patched plugin
	 */
	private static void setupLibdecorPlugin() {
		try {
			org.lwjgl.system.SharedLibrary libc = org.lwjgl.system.APIUtil.apiCreateLibrary("libc.so.6");
			long setenv = libc.getFunctionAddress("setenv");
			if (setenv == 0) {
				System.out.println("[RetroDragon] Could not find setenv in libc");
				return;
			}

			// Force GTK to use Wayland backend, otherwise gtk_init fails
			nativeSetenv(setenv, "GDK_BACKEND", "wayland");
			System.out.println("[RetroDragon] Set GDK_BACKEND=wayland");

			// Extract patched libdecor-gtk plugin
			String arch = System.getProperty("os.arch", "");
			String nativeDir;
			if (arch.contains("aarch64") || arch.contains("arm64")) {
				nativeDir = "natives/linux-aarch64";
			} else {
				nativeDir = "natives/linux-x86_64";
			}

			String resource = "/" + nativeDir + "/libdecor-gtk.so";
			try (InputStream in = Display.class.getResourceAsStream(resource)) {
				if (in == null) {
					System.out.println("[RetroDragon] Patched libdecor-gtk plugin not found for " + arch);
					return;
				}

				Path pluginDir = Files.createTempDirectory("retrodragon-libdecor");
				Path pluginFile = pluginDir.resolve("libdecor-gtk.so");
				Files.copy(in, pluginFile, StandardCopyOption.REPLACE_EXISTING);
				pluginFile.toFile().setExecutable(true);
				pluginDir.toFile().deleteOnExit();
				pluginFile.toFile().deleteOnExit();

				// Copy the system cairo plugin as fallback (e.g. if GTK3 is not installed)
				// Try arch-appropriate lib dir first (lib64 on Fedora/RHEL, then lib)
				String[] cairoCandidates = {
					"/usr/lib64/libdecor/plugins-1/libdecor-cairo.so",
					"/usr/lib/x86_64-linux-gnu/libdecor/plugins-1/libdecor-cairo.so",
					"/usr/lib/libdecor/plugins-1/libdecor-cairo.so",
				};
				Path systemCairo = null;
				for (String c : cairoCandidates) {
					Path p = Path.of(c);
					if (Files.exists(p)) {
						systemCairo = p;
						break;
					}
				}
				if (systemCairo != null) {
					Path cairoCopy = pluginDir.resolve("libdecor-cairo.so");
					Files.copy(systemCairo, cairoCopy, StandardCopyOption.REPLACE_EXISTING);
					cairoCopy.toFile().deleteOnExit();
				}

				nativeSetenv(setenv, "LIBDECOR_PLUGIN_DIR", pluginDir.toAbsolutePath().toString());
				System.out.println("[RetroDragon] Set LIBDECOR_PLUGIN_DIR=" + pluginDir.toAbsolutePath());
			}
		} catch (Exception e) {
			System.out.println("[RetroDragon] Could not setup libdecor plugin: " + e.getMessage());
		}
	}

	/**
	 * Wayland environment tweaks that are properties of the SESSION, not of the windowing toolkit,
	 * so both the GLFW and the SDL3 backend want them. Must run before the video backend
	 * initializes -- these are read by libGL/EGL and by the cursor-theme loader at that point.
	 *
	 * <p>Deliberately does NOT include {@link #setupLibdecorPlugin()}: that overrides
	 * {@code LIBDECOR_PLUGIN_DIR} to a directory holding only our patched GTK plugin, which is the
	 * right trade on GLFW (upstream libdecor-gtk refuses to initialize off the OS main thread, so
	 * the stock plugin always fails there) but would take SDL's own decoration fallbacks away from
	 * it. See the note in the stage report.
	 */
	private static void setupWaylandEnvironment() {
		// NVIDIA on Wayland: the driver's threaded optimizations break the
		// game (user-reported). The driver reads this env var when libGL/EGL
		// initializes, which happens at context creation -- after this point --
		// so setenv here is early enough. Respect a user-set value.
		if (System.getenv("__GL_THREADED_OPTIMIZATIONS") == null
				&& Files.exists(Path.of("/sys/module/nvidia"))) {
			setNativeEnv("__GL_THREADED_OPTIMIZATIONS", "0");
			System.out.println("[RetroDragon] NVIDIA on Wayland detected, set __GL_THREADED_OPTIMIZATIONS=0");
		}
		setupCursorTheme();
	}

	public static void ensureInitialized() {
		if (glfwInitialized) return;
		glfwInitialized = true;
		usingGlfwAsync = "glfw_async".equals(org.lwjgl.system.Configuration.GLFW_LIBRARY_NAME.get());
		GLFWErrorCallback.createPrint(System.err).set();
		// Fix X11 text input: Java never calls setlocale(), leaving the C
		// locale as "C", which causes GLFW's XIM to silently fail and the
		// char callback to never fire. We also disable input method daemons
		// (IBus, Fcitx, etc.) via XMODIFIERS because they process key events
		// asynchronously, causing the char callback to fire on a later frame
		// than the key callback -- breaking the event merge logic.
		if (OS.current() == OS.LINUX && System.getenv("WAYLAND_DISPLAY") == null) {
			try {
				org.lwjgl.system.SharedLibrary libc = org.lwjgl.system.APIUtil.apiCreateLibrary("libc.so.6");
				long setlocale = libc.getFunctionAddress("setlocale");
				if (setlocale != 0) {
					ByteBuffer empty = MemoryUtil.memASCII("", true);
					org.lwjgl.system.JNI.invokePP(0 /* LC_ALL */, MemoryUtil.memAddress(empty), setlocale);
					MemoryUtil.memFree(empty);
				}
				long setenv = libc.getFunctionAddress("setenv");
				if (setenv != 0) {
					nativeSetenv(setenv, "XMODIFIERS", "@im=none");
				}
			} catch (Exception e) {
				System.out.println("[RetroDragon] Could not setup X11 locale: " + e.getMessage());
			}
		}

		if (!forceX11Fallback && System.getenv("WAYLAND_DISPLAY") != null && GLFW.glfwPlatformSupported(GLFW.GLFW_PLATFORM_WAYLAND)) {
			setupWaylandEnvironment();
			// Compositors without xdg-decoration (e.g. GNOME, Cinnamon) need
			// a patched libdecor-gtk plugin for window decorations.
			if (needsLibdecor()) {
				setupLibdecorPlugin();
			}
			GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
			GLFW.glfwInitHint(GLFW.GLFW_WAYLAND_LIBDECOR, GLFW.GLFW_WAYLAND_PREFER_LIBDECOR);
		} else if (forceX11Fallback) {
			GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
		}

		if (!GLFW.glfwInit()) {
			if (!forceX11Fallback && System.getenv("WAYLAND_DISPLAY") != null) {
				System.out.println("[RetroDragon] Wayland GLFW init failed, falling back to X11/XWayland");
				forceX11Fallback = true;
				glfwInitialized = false;
				ensureInitialized();
				return;
			}
			throw new IllegalStateException("Unable to initialize GLFW");
		}
		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			WaylandCenterCursor.init();
		}
		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
			MacOSDisplayHelper.initAppAppearance();
		}
	}

	@NotNull
	public static String getTitle() {
		return title;
	}

	public static void setTitle(@NotNull String title) {
		if (SDL) {
			Display.title = title;
			Sdl3Window.setTitle(title);
			if (!HEADLESS && Sdl3Window.isWayland()) {
				// Only the base title belongs in a launcher entry, and rewriting the .desktop is
				// expensive (see lastInjectedTitle), so the FPS suffix is stripped and an unchanged
				// title is dropped.
				String base = baseTitle(title);
				if (!base.equals(lastInjectedTitle)) {
					lastInjectedTitle = base;
					DesktopFileInjector.updateTitle(base);
				}
			}
			return;
		}
		Display.title = title;
		if (isCreated()) {
			GLFW.glfwSetWindowTitle(handle, title);
		}
		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			DesktopFileInjector.updateTitle(title);
		}
	}

	/**
	 * Strips a trailing FPS/debug suffix so the launcher entry keeps a stable name.
	 *
	 * <p>Conservative on purpose: a separator is only treated as the start of a suffix when what
	 * follows actually mentions FPS. Launcher-supplied titles (Prism sets one that legitimately
	 * contains parentheses) are therefore left alone.
	 */
	@NotNull
	private static String baseTitle(@NotNull String full) {
		int cut = -1;
		for (String separator : new String[]{" - ", " | ", " ("}) {
			int index = full.indexOf(separator);
			if (index > 0 && full.substring(index).toLowerCase(java.util.Locale.ROOT).contains("fps")
					&& (cut < 0 || index < cut)) {
				cut = index;
			}
		}
		return cut > 0 ? full.substring(0, cut).trim() : full;
	}

	public static long getHandle() {
		return handle;
	}

	public static void setHandle(long handle) {
		Display.handle = handle;
	}

	@NotNull
	public static DisplayMode getDisplayMode() {
		return displayMode;
	}

	public static void setDisplayMode(@NotNull DisplayMode mode) {
		displayMode = mode;
	}

	public static int getWidth() {
		if (SDL) {
			return com.periut.retrodragon.window.sdl.Sdl3Window.width();
		}
		if (handle != -1L) {
			int[] w = new int[1];
			GLFW.glfwGetFramebufferSize(handle, w, null);
			return w[0];
		}
		return width;
	}

	public static void setWidth(int width) {
		Display.width = width;
	}

	public static int getHeight() {
		if (SDL) {
			return com.periut.retrodragon.window.sdl.Sdl3Window.height();
		}
		if (handle != -1L) {
			int[] h = new int[1];
			GLFW.glfwGetFramebufferSize(handle, null, h);
			return h[0];
		}
		return height;
	}

	public static void setHeight(int height) {
		Display.height = height;
	}

	public static int getXPos() {
		return xPos;
	}

	public static void setXPos(int XPos) {
		xPos = XPos;
	}

	public static int getYPos() {
		return yPos;
	}

	public static void setYPos(int YPos) {
		yPos = YPos;
	}

	/**
	 * SDL3's desktop mode as an LWJGL 2 {@link DisplayMode}, or null if SDL cannot report one.
	 *
	 * <p>SDL reports mode geometry in logical POINTS while everything downstream of
	 * {@code Display.getWidth()} is in pixels, so the density is folded in when the window was
	 * created with {@code SDL_WINDOW_HIGH_PIXEL_DENSITY}. Beta only ever reads bpp as "is this
	 * 32-bit", so the GLFW path's 8+8+8 is reproduced verbatim.
	 */
	@Nullable
	private static DisplayMode sdlDesktopDisplayMode() {
		SDL_DisplayMode mode = SDLVideo.SDL_GetDesktopDisplayMode(SDLVideo.SDL_GetPrimaryDisplay());
		return mode == null ? null : toDisplayMode(mode);
	}

	@NotNull
	private static DisplayMode toDisplayMode(@NotNull SDL_DisplayMode mode) {
		float density = Sdl3Window.highDpi() ? mode.pixel_density() : 1.0F;
		if (!(density > 0.0F)) {
			density = 1.0F;
		}
		return new DisplayMode(Math.round(mode.w() * density), Math.round(mode.h() * density),
			24, Math.round(mode.refresh_rate()));
	}

	@Nullable
	public static DisplayMode getDesktopDisplayMode() {
		if (SDL) {
			// NOT ensureInitialized(): that boots GLFW in a process that already owns an SDL video
			// subsystem -- two window systems, two event queues, and on macOS two claims on thread 0.
			DisplayMode desktop = sdlDesktopDisplayMode();
			if (desktop != null) {
				return desktop;
			}
			return Arrays.stream(getAvailableDisplayModes())
				.max(Comparator.comparingInt(d -> d.getWidth() * d.getHeight())).orElse(null);
		}
		ensureInitialized();
		long mon = GLFW.glfwGetPrimaryMonitor();
		GLFWVidMode mode = GLFW.glfwGetVideoMode(mon);
		if (mode == null) {
			return Arrays.stream(getAvailableDisplayModes()).max(Comparator.comparingInt(d -> d.getWidth() * d.getHeight())).orElse(null);
		}
		return new DisplayMode(mode.width(), mode.height(), mode.redBits() + mode.greenBits() + mode.blueBits(),
				mode.refreshRate());
	}


	/**
	 * Position-preserving deep copy into direct memory, so the icons survive until the window that
	 * needs them exists. Position-preserving matters: {@code MinecraftMixin} hands the same buffers
	 * to several consumers.
	 */
	@NotNull
	private static ByteBuffer[] copyIcons(@NotNull ByteBuffer[] icons) {
		return Arrays.stream(icons).map(buf -> {
			ByteBuffer copy = ByteBuffer.allocateDirect(buf.remaining());
			int old_pos = buf.position();
			copy.put(buf);
			buf.position(old_pos);
			copy.flip();
			return copy;
		}).toArray(ByteBuffer[]::new);
	}

	/**
	 * Encodes the largest of the ARGB icon buffers as a PNG.
	 *
	 * <p>Only macOS needs this: the Dock icon goes through {@code [NSImage initWithContentsOfFile:]},
	 * which wants a real image file, not raw pixels. BufferedImage/ImageIO are headless-safe -- they
	 * do not start the AWT event loop the way {@code new Frame()} does, which is the thing that
	 * fights {@code -XstartOnFirstThread} on macOS. Do NOT reach for {@code java.awt.Taskbar} here;
	 * that one does initialize the full AWT/AppKit bridge.
	 */
	@Nullable
	private static byte[] encodeLargestIconAsPng(@NotNull ByteBuffer[] icons) {
		ByteBuffer largest = icons[0];
		for (ByteBuffer buf : icons) {
			if (buf.limit() > largest.limit()) largest = buf;
		}
		int size = largest.limit() / 4;
		int dim = (int) Math.sqrt(size);
		if (dim <= 0) return null;
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(dim, dim, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		int oldPos = largest.position();
		for (int i = 0; i < size; i++) {
			int a = largest.get(oldPos + i * 4) & 0xFF;
			int r = largest.get(oldPos + i * 4 + 1) & 0xFF;
			int g = largest.get(oldPos + i * 4 + 2) & 0xFF;
			int b = largest.get(oldPos + i * 4 + 3) & 0xFF;
			img.setRGB(i % dim, i / dim, (a << 24) | (r << 16) | (g << 8) | b);
		}
		try {
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "png", baos);
			return baos.toByteArray();
		} catch (java.io.IOException e) {
			System.err.println("[Display] Failed to encode icon as PNG: " + e.getMessage());
			return null;
		}
	}

	public static int setIcon(@NotNull ByteBuffer[] icons) {
		if (SDL) {
			// Headless writes nothing anywhere: no XDG files, no subprocesses, no temp PNG.
			if (HEADLESS) {
				return 0;
			}
			// The launcher/taskbar icon and the WINDOW icon are different surfaces, and on Wayland
			// the launcher one can only come from a .desktop entry, so both run there. This is the
			// one place SDL beats GLFW outright: glfwSetWindowIcon is a documented no-op on Wayland,
			// while SDL_SetWindowIcon feeds xdg-toplevel-icon where the compositor supports it.
			if (Sdl3Window.isWayland()) {
				DesktopFileInjector.setIcon(icons);
			}
			if (!Sdl3Window.isCreated()) {
				cached_icons = copyIcons(icons);
				return 0;
			}
			if (OS.current() == OS.OSX) {
				// The app icon lives on NSApplication on macOS, not on the window -- both
				// glfwSetWindowIcon and SDL_SetWindowIcon are no-ops there.
				byte[] png = encodeLargestIconAsPng(icons);
				if (png == null) {
					return 0;
				}
				// MainThread.run rather than setDockIcon's own performSelectorOnMainThread: bounce:
				// it makes the ObjC isMainThread() check take the direct-invoke path, which gives a
				// deterministic ordering instead of a fire-and-forget runloop enqueue.
				MainThread.run(() -> MacOSDisplayHelper.setDockIcon(png));
				return 1;
			}
			Sdl3Window.setIcon(icons);
			return 1;
		}

		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			return DesktopFileInjector.setIcon(icons);
		}

		if (!isCreated()) {
			// Cache icons for when the window is created
			cached_icons = copyIcons(icons);
			return 0;
		}

		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
			// glfwSetWindowIcon is a no-op on macOS; use NSApplication API instead.
			byte[] png = encodeLargestIconAsPng(icons);
			if (png != null) {
				MacOSDisplayHelper.setDockIcon(png);
			}
			return 1;
		}

		// X11 / Windows: convert ARGB to RGBA and pass to GLFW
		try (MemoryStack memoryStack = MemoryStack.stackPush()) {
			Buffer glfwBuffer = GLFWImage.malloc(icons.length, memoryStack);

			for (ByteBuffer buf : icons) {
				int pixelCount = buf.remaining() / 4;
				int dimension = (int) Math.sqrt(pixelCount);

				ByteBuffer rgba = MemoryUtil.memAlloc(pixelCount * 4);
				for (int i = 0; i < pixelCount; i++) {
					int base = buf.position() + i * 4;
					byte a = buf.get(base);
					byte r = buf.get(base + 1);
					byte g = buf.get(base + 2);
					byte b = buf.get(base + 3);
					rgba.put(r).put(g).put(b).put(a);
				}
				rgba.flip();

				GLFWImage image = GLFWImage.malloc(memoryStack);
				image.set(dimension, dimension, rgba);
				glfwBuffer.put(image);
			}
			glfwBuffer.flip();

			System.out.println("[RetroDragon] Setting window icon (" + icons.length + " sizes, handle=" + handle + ")");
			GLFW.glfwSetWindowIcon(handle, glfwBuffer);
		}
		return 1;
	}

	public static void update() {
		if (SDL) {
			// RetroCenter window ownership, SDL3 edition. Structurally identical to the GLFW block
			// below -- same bridge protocol, same three roles (parked hub / child owner / sole
			// owner) -- with SDL's equivalents substituted:
			//   glfwMakeContextCurrent(NULL)   -> Sdl3Window.releaseContext()
			//   glfwWaitEventsTimeout(0.05)    -> Sdl3Events.pumpWait(50)
			//   glfwMakeContextCurrent(handle) -> Sdl3Window.acquireContext()
			//   glfwSwapBuffers(handle)        -> swapBuffers()  (SDL_GL_SwapWindow / present hook)
			// WaylandCenterCursor.finishWarp() has NO counterpart here on purpose: SDL warps the
			// pointer natively, so there is no deferred warp to dispatch from the park loop.
			// callerInstance() short-circuits to HUB until a child classloader has ever been
			// registered, so the common case costs one volatile read per frame, not a stack walk.
			int rcCaller = com.periut.retrodragon.retrocenter.bridge.HubBridge.callerInstance();
			if (!com.periut.retrodragon.retrocenter.bridge.HubBridge.isOwnerInstance(rcCaller)) {
				if (rcCaller == com.periut.retrodragon.retrocenter.bridge.HubBridge.HUB) {
					com.periut.retrodragon.retrocenter.bridge.HubBridge.hubParkBegin();
					Sdl3Window.releaseContext();
					// A PUMP loop, not a sleep: this thread owns the event queue (and on macOS is
					// the only one allowed to marshal to thread 0 for it), and the child's swap
					// blocks on frame callbacks delivered through it.
					while (!com.periut.retrodragon.retrocenter.bridge.HubBridge.hubOwnsWindow()) {
						Sdl3Events.pumpWait(50);
					}
					com.periut.retrodragon.retrocenter.bridge.HubBridge.hubParkEnd();
					Sdl3Window.acquireContext();
					// Set the hub's destination screen BEFORE anything renders.
					Runnable rcWake =
						com.periut.retrodragon.retrocenter.bridge.HubBridge.consumeHubWakeCallback();
					if (rcWake != null) {
						try {
							rcWake.run();
						} catch (Throwable t) {
							t.printStackTrace();
						}
					}
					window_resized = true;
				}
				return; // hub: first frame after wake; child: post-teardown safety
			}
			boolean rcChildCaller = rcCaller != com.periut.retrodragon.retrocenter.bridge.HubBridge.HUB;
			if (rcChildCaller) {
				// A child owner must NOT pump: the parked hub thread (the window's creator, and on
				// macOS the only pump thread 0 will accept work from) is doing that. The child only
				// presents -- and only once the present gate lifts.
				if (com.periut.retrodragon.retrocenter.bridge.HubBridge.mayChildPresent()) {
					swapBuffers();
				}
				return;
			}
			com.periut.retrodragon.window.sdl.Sdl3InputSelfTest.runOnce();
			Sdl3Events.pump();
			// Always consume, even off Windows, so the flag cannot go stale.
			boolean themeChanged = Sdl3Events.consumeThemeChanged();
			if (OS.current() == OS.WINDOWS) {
				if (sdlThemeFromEvents) {
					if (themeChanged) {
						int theme = SDLVideo.SDL_GetSystemTheme();
						if (theme == SDLVideo.SDL_SYSTEM_THEME_UNKNOWN) {
							WindowsDisplayHelper.pollThemeChange();
						} else {
							WindowsDisplayHelper.setDarkMode(theme == SDLVideo.SDL_SYSTEM_THEME_DARK);
						}
					}
				} else {
					// SDL could not tell us the theme at startup, so fall back to starac's registry
					// poll. It self-rate-limits to one `reg query` every two seconds.
					WindowsDisplayHelper.pollThemeChange();
				}
			}
			swapBuffers();
			return;
		}
		// RetroCenter window ownership: when an in-process child instance
		// owns the window, the hub's render thread parks here (releasing the
		// GL context first so the child can bind it). It wakes when the
		// child gives the window back, re-acquires the context and resumes.
		int retrocenterCaller = com.periut.retrodragon.retrocenter.bridge.HubBridge.callerInstance();
		if (!com.periut.retrodragon.retrocenter.bridge.HubBridge.isOwnerInstance(retrocenterCaller)) {
			if (retrocenterCaller == com.periut.retrodragon.retrocenter.bridge.HubBridge.HUB) {
				// Park as a PUMP LOOP: this is the thread that created the
				// window, so it must keep polling GLFW events -- keeps the
				// window responsive and feeds input callbacks while the
				// child renders (GL moves threads; the event pump doesn't).
				com.periut.retrodragon.retrocenter.bridge.HubBridge.hubParkBegin();
				GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
				// glfwWaitEventsTimeout (NOT a sleep loop): on Wayland the
				// child's swapBuffers blocks on frame callbacks that arrive
				// through THIS event queue -- the pump must dispatch them at
				// monitor rate or the child's framerate caps at the poll
				// interval. WaitEvents wakes per event; idle costs nothing.
				while (!com.periut.retrodragon.retrocenter.bridge.HubBridge.hubOwnsWindow()) {
					GLFW.glfwWaitEventsTimeout(0.05);
					WaylandCenterCursor.finishWarp(); // dispatch child-requested warps
				}
				com.periut.retrodragon.retrocenter.bridge.HubBridge.hubParkEnd();
				GLFW.glfwMakeContextCurrent(handle);
				GL.createCapabilities();
				// Set the hub's destination screen BEFORE anything renders.
				Runnable retrocenterWake = com.periut.retrodragon.retrocenter.bridge.HubBridge.consumeHubWakeCallback();
				if (retrocenterWake != null) {
					try {
						retrocenterWake.run();
					} catch (Throwable t) {
						t.printStackTrace();
					}
				}
				window_resized = true;
			}
			return; // skip this frame (hub: first frame after wake; child: post-teardown safety)
		}
		// A child owner must NOT pump GLFW events -- the parked hub thread
		// (the window's creator) does that; the child only swaps + drains
		// the shared input queues.
		boolean retrocenterChildCaller = retrocenterCaller != com.periut.retrodragon.retrocenter.bridge.HubBridge.HUB;
		window_resized = false;
		if (retrocenterChildCaller) {
			if (Mouse.isCreated()) {
				Mouse.poll();
			}
			if (Keyboard.isCreated()) {
				Keyboard.poll();
			}
			// Present gate: during the child's boot (Mojang splash, texture
			// loading) the hub's last frame stays on screen -- the child's
			// frames are held until it reaches its logging-in screen.
			if (com.periut.retrodragon.retrocenter.bridge.HubBridge.mayChildPresent()) {
				GLFW.glfwSwapBuffers(handle);
			}
			return;
		}
		pollEvents();
		if (OS.current() == OS.WINDOWS) {
			WindowsDisplayHelper.pollThemeChange();
		}
		if (Mouse.isCreated()) {
			Mouse.poll();
		}

		if (Keyboard.isCreated()) {
			Keyboard.poll();
		}

		GLFW.glfwSwapBuffers(handle);
		// After GLFW commits the surface via swapBuffers, dispatch any
		// pending cursor warp so the lock callback fires.
		WaylandCenterCursor.finishWarp();
	}

	/**
	 * Async-safe glfwPollEvents. ALWAYS use this instead of calling
	 * GLFW.glfwPollEvents() directly: with glfw_async the poll is dispatched
	 * to the macOS main thread, and the game thread holds the CGL context
	 * lock -- if a screen/dock notification makes GLFW's observer call
	 * [NSOpenGLContext update] (which takes the CGL lock) on the main thread
	 * at the same time, a raw poll deadlocks (main thread waits on the CGL
	 * lock, game thread waits on the main thread). Unlocking around the poll
	 * also lets the compositor safely access the GL surface during event
	 * processing (e.g. window resize) -- without it, the compositor and game
	 * thread race on the surface, causing SIGSEGV in AppleMetalOpenGLRenderer.
	 */
	/**
	 * Wakes a thread blocked waiting for a window event, from any thread.
	 *
	 * <p>RetroCenter's only caller: when a child instance's game ends, the hub is sitting in
	 * {@code update()}'s park loop on a timed event wait. Ownership has already changed, so the
	 * loop will exit -- this just makes it exit now instead of at the end of the current timeout.
	 * Backend-agnostic because the child launcher has no business knowing which backend is live.
	 */
	public static void wakeEventLoop() {
		if (SDL) {
			com.periut.retrodragon.window.sdl.Sdl3Events.postWake();
			return;
		}
		GLFW.glfwPostEmptyEvent();
	}

	public static void pollEvents() {
		if (SDL) {
			com.periut.retrodragon.window.sdl.Sdl3Events.pump();
			return;
		}
		if (usingGlfwAsync) {
			GL11.glFinish();
			MacOSDisplayHelper.unlockCGLContext();
			GLFW.glfwPollEvents();
			MacOSDisplayHelper.relockCGLContext();
		} else {
			GLFW.glfwPollEvents();
		}
	}

	public static void create() {
		try {
			create(new PixelFormat());
		} catch (LWJGLException e) {
			throw new RuntimeException(e);
		}
	}

	public static void create(@NotNull PixelFormat pixelFormat) throws LWJGLException {
		if (SDL) {
			// RetroCenter: an in-process child instance attaches to the hub's existing window
			// instead of creating one. Same window, same GL context -- only the owning thread
			// changes. It blocks until the hub has parked (and therefore released the context),
			// then binds it here, on the child's own render thread.
			if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild() && isCreated()) {
				System.out.println("[RetroCenter] child attaching to existing SDL3 window");
				com.periut.retrodragon.retrocenter.bridge.HubBridge.awaitHubParked();
				Sdl3Window.acquireContext();
				Sdl3Window.setSwapInterval(0);
				com.periut.retrodragon.retrocenter.bridge.HubBridge.noteChildAttached();
				handle = Sdl3Window.handle();
				focused = true;
				window_resized = true;
				return;
			}
			width = displayMode.getWidth();
			height = displayMode.getHeight();
			// Session-level Wayland setup (cursor theme, NVIDIA workaround) has to happen before the
			// video backend initializes, exactly as it does on the GLFW path. SDL picks the Wayland
			// backend off the same WAYLAND_DISPLAY signal, and its driver name is not knowable until
			// after SDL_Init -- which is already too late for these.
			if (OS.current() == OS.LINUX && System.getenv("WAYLAND_DISPLAY") != null) {
				setupWaylandEnvironment();
			}
			// Sdl3Window.create also does the app-id hint, [NSApp setAppearance:nil], the Wayland
			// .desktop injection and the Windows DWM titlebar -- all of them have to happen either
			// side of SDL_Init/SDL_CreateWindow, which only it can see.
			if (!Sdl3Window.create(width, height, title, HEADLESS)) {
				throw new LWJGLException("SDL3 window creation failed");
			}
			handle = Sdl3Window.handle();
			focused = true;
			window_resized = true;
			if (OS.current() == OS.WINDOWS) {
				// Decides once whether the titlebar theme can be event-driven; see update().
				sdlThemeFromEvents = WindowsDisplayHelper.isInitialized()
					&& SDLVideo.SDL_GetSystemTheme() != SDLVideo.SDL_SYSTEM_THEME_UNKNOWN;
			}
			// Anything that asked for an icon before the window existed. In practice MinecraftMixin
			// sets the icon just AFTER Display.create() returns, so this is normally empty -- it is
			// here so the cache is not silently dropped on the SDL path the way it was.
			if (cached_icons != null) {
				ByteBuffer[] pending = cached_icons;
				cached_icons = null;
				setIcon(pending);
			}
			return;
		}
		if (HEADLESS) {
			// SDL_WINDOW_HIDDEN has no GLFW equivalent that still yields a GL context, so on the
			// GLFW backend (macOS by default) a headless run would silently show a real window.
			System.err.println("[RetroDragon] -Dretrowindow.headless=true is ignored on the GLFW"
				+ " backend; the window will be visible.");
		}
		// RetroCenter: an in-process child instance attaches to the existing
		// window instead of creating one. It blocks until the hub has parked
		// (released the GL context), then binds the context to its own
		// render thread. Same window, same context -- only the owner changes.
		if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild() && isCreated()) {
			System.out.println("[RetroCenter] child attaching to existing window");
			com.periut.retrodragon.retrocenter.bridge.HubBridge.awaitHubParked();
			GLFW.glfwMakeContextCurrent(handle);
			GL.createCapabilities();
			GLFW.glfwSwapInterval(0);
			com.periut.retrodragon.retrocenter.bridge.HubBridge.noteChildAttached();
			window_resized = true;
			return;
		}
		ensureInitialized();
		// Configure GLFW
		GLFW.glfwDefaultWindowHints();

		if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			DesktopFileInjector.inject();
			GLFW.glfwWindowHintString(GLFW.GLFW_WAYLAND_APP_ID, DesktopFileInjector.APP_ID);
		}

		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
		if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_COCOA) { // macOS does not support the compat profile
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
		}
		GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, pixelFormat.getAlphaBits());
		GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, pixelFormat.getDepthBits());
		GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, pixelFormat.getStencilBits());
		GLFW.glfwWindowHint(GLFW.GLFW_STEREO, pixelFormat.isStereo() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);

		GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
		GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, 1);
		handle =
				GLFW.glfwCreateWindow(displayMode.getWidth(), displayMode.getHeight(), title, MemoryUtil.NULL, MemoryUtil.NULL);
		if (handle == MemoryUtil.NULL && !forceX11Fallback && GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
			System.out.println("[RetroDragon] Wayland window creation failed, falling back to X11/XWayland");
			GLFW.glfwTerminate();
			forceX11Fallback = true;
			glfwInitialized = false;
			ensureInitialized();
			create(pixelFormat);
			return;
		}
		if (handle == MemoryUtil.NULL) {
			throw new LWJGLException("Failed to create GLFW window");
		}
		width = displayMode.getWidth();
		height = displayMode.getHeight();
		// Cinnamon's Muffin attaches libdecor decorations late, sending a
		// configure that shrinks the window. Lock the minimum size until
		// the first resize callback fires.
		if (isCinnamonWayland()) {
			GLFW.glfwSetWindowSizeLimits(handle, displayMode.getWidth(), displayMode.getHeight(), GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);
			cinnamonSizeLimitActive = true;
		}
		GLFW.glfwMakeContextCurrent(handle);
		GL.createCapabilities();
		GLFW.glfwSwapInterval(0); // disable vsync by default
		// create general callbacks
		sizeCallback = GLFWWindowSizeCallback.create(Display::resizeCallback);
		GLFW.glfwSetWindowSizeCallback(handle, sizeCallback);
		focusCallback = GLFWWindowFocusCallback.create((window, focused1) -> {
			if (window == handle) {
				focused = focused1;
			}
		});
		GLFW.glfwSetWindowFocusCallback(handle, focusCallback);
		Mouse.create();
		Keyboard.create();
		// Center window on primary monitor (not supported on Wayland)
		if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
			long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
			if (primaryMonitor != MemoryUtil.NULL) {
				GLFWVidMode vidMode = GLFW.glfwGetVideoMode(primaryMonitor);
				if (vidMode != null) {
					GLFW.glfwSetWindowPos(handle,
							(vidMode.width() - displayMode.getWidth()) / 2,
							(vidMode.height() - displayMode.getHeight()) / 2);
				}
			}
		}
		// Enable dark titlebar on Windows 10/11
		if (OS.current() == OS.WINDOWS) {
			WindowsDisplayHelper.init(handle);
		}
		GLFW.glfwShowWindow(handle);
		if (cached_icons != null) {
			setIcon(cached_icons);
		}
		if (usingGlfwAsync && GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
			// Lock the CGL context for the game thread. This is unlocked
			// around glfwPollEvents in update() so the compositor can
			// safely access the GL surface during resize.
			MacOSDisplayHelper.lockCGLContext(handle);
		}
	}

	private static int windowedX, windowedY, windowedWidth, windowedHeight;

	public static void setFullscreen(boolean fullscreen) {
		// Windows 11: retract the Mica backdrop before the window goes fullscreen and restore it on
		// the way back. Left on, DWM composites the client area against a blurred desktop sample and
		// every frame beta leaves below full alpha lifts toward it -- the washed-out fullscreen. See
		// WindowsDisplayHelper.setBackdropEnabled. Backend-independent by construction: it is an
		// attribute of the HWND, so it is done here rather than in either renderer.
		if (OS.current() == OS.WINDOWS) {
			WindowsDisplayHelper.setBackdropEnabled(!fullscreen);
		}
		if (SDL) {
			long window = Sdl3Window.handle();
			// Headless is a hidden window by definition; making it fullscreen would be meaningless
			// at best and would map it at worst.
			if (window == 0L || HEADLESS) {
				return;
			}
			MainThread.run(() -> {
				// NULL mode = borderless-desktop fullscreen: keep the desktop resolution instead of
				// switching modes. Matches the GLFW path, which deliberately uses the monitor's
				// native vidmode rather than changing it.
				SDLVideo.SDL_SetWindowFullscreenMode(window, (SDL_DisplayMode) null);
				if (!SDLVideo.SDL_SetWindowFullscreen(window, fullscreen)) {
					System.err.println("[RetroDragon] SDL_SetWindowFullscreen failed: "
						+ org.lwjgl.sdl.SDLError.SDL_GetError());
					return;
				}
				// Fullscreen transitions are asynchronous in SDL3; without this the very next
				// SDL_GetWindowSizeInPixels still reports the old geometry.
				SDLVideo.SDL_SyncWindow(window);
			});
			// No windowedX/Y/Width/Height bookkeeping on this path: SDL restores the previous
			// windowed geometry itself. No manual resizeCallback either -- Sdl3Events already turns
			// SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED into the resize flag, and Display.getWidth/Height
			// read the live window size rather than a cached one.
			return;
		}
		boolean isWayland = GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
		try {
			if (fullscreen) {
				// Save windowed position and size for restoration
				if (!isWayland) {
					int[] wx = new int[1], wy = new int[1];
					GLFW.glfwGetWindowPos(handle, wx, wy);
					windowedX = wx[0];
					windowedY = wy[0];
				}
				int[] ww = new int[1], wh = new int[1];
				GLFW.glfwGetWindowSize(handle, ww, wh);
				windowedWidth = ww[0];
				windowedHeight = wh[0];

				// Use the monitor's native resolution -- no display mode change
				long monitor = GLFW.glfwGetPrimaryMonitor();
				GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
				if (vidMode != null) {
					GLFW.glfwSetWindowMonitor(handle, monitor,
							0, 0,
							vidMode.width(), vidMode.height(),
							vidMode.refreshRate());
					resizeCallback(handle, vidMode.width(), vidMode.height());
				}
			} else {
				// Restore windowed mode with saved position and size
				GLFW.glfwSetWindowMonitor(handle, MemoryUtil.NULL,
						isWayland ? 0 : windowedX,
						isWayland ? 0 : windowedY,
						windowedWidth, windowedHeight,
						-1);
				resizeCallback(handle, windowedWidth, windowedHeight);
			}

			// Surface may have been recreated -- reset native cursor warp state
			if (isWayland) {
				WaylandCenterCursor.reset();
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@NotNull
	public static DisplayMode[] getAvailableDisplayModes() {
		if (SDL) {
			// Same reason as getDesktopDisplayMode: never call ensureInitialized() here.
			PointerBuffer modes = SDLVideo.SDL_GetFullscreenDisplayModes(SDLVideo.SDL_GetPrimaryDisplay());
			if (modes == null || modes.remaining() == 0) {
				if (modes != null) {
					SDLStdinc.SDL_free(modes);
				}
				// A compositor that exposes no fullscreen mode list (common on Wayland) still has a
				// desktop mode, and beta needs at least one entry to pick from.
				DisplayMode desktop = sdlDesktopDisplayMode();
				return desktop == null ? new DisplayMode[0] : new DisplayMode[]{desktop};
			}
			try {
				DisplayMode[] result = new DisplayMode[modes.remaining()];
				for (int i = 0; i < result.length; i++) {
					result[i] = toDisplayMode(SDL_DisplayMode.create(modes.get(i)));
				}
				return result;
			} finally {
				// SDL_GetFullscreenDisplayModes hands back an SDL-allocated array; LWJGL only wraps
				// it, so it is ours to release.
				SDLStdinc.SDL_free(modes);
			}
		}
		ensureInitialized();
		long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
		if (primaryMonitor == MemoryUtil.NULL) {
			return new DisplayMode[0];
		} else {
			GLFWVidMode.Buffer videoModes = GLFW.glfwGetVideoModes(primaryMonitor);
			if (videoModes == null) {
				throw new IllegalStateException("No video modes found");
			} else {
				return videoModes.stream().map(mode -> new DisplayMode(mode.width(),
						mode.height(), mode.redBits() + mode.blueBits() + mode.greenBits(),
						mode.refreshRate())).toArray(DisplayMode[]::new);
			}
		}
	}

	public static void destroy() {
		if (SDL) {
			// RetroCenter: a child DETACHES, it never destroys. The window, the context and the
			// SDL video subsystem belong to the hub, which re-acquires the context the moment its
			// park loop sees ownership come back. Destroying here would take the hub's window --
			// and on macOS the whole Cocoa video backend -- down with the child.
			if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild()) {
				System.out.println("[RetroCenter] child detaching from SDL3 window");
				com.periut.retrodragon.window.sdl.Sdl3Window.releaseContext();
				com.periut.retrodragon.retrocenter.bridge.HubBridge.childGameEnded(null);
				return;
			}
			com.periut.retrodragon.window.sdl.Sdl3Window.destroy();
			handle = -1L;
			return;
		}
		if (handle == -1L) {
			return; // Not created, nothing to destroy
		}
		// RetroCenter: a child instance detaches instead of destroying -- the
		// window belongs to the hub, which re-acquires the context when the
		// child's launcher returns ownership.
		if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild()) {
			System.out.println("[RetroCenter] child detaching from window");
			GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
			com.periut.retrodragon.retrocenter.bridge.HubBridge.childGameEnded(null);
			return;
		}
		if (usingGlfwAsync && GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
			MacOSDisplayHelper.unlockCGLContext();
		}
		WaylandCenterCursor.destroy();
		// free callbacks (with null checks)
		if (sizeCallback != null) {
			sizeCallback.free();
			sizeCallback = null;
		}
		if (focusCallback != null) {
			focusCallback.free();
			focusCallback = null;
		}
		Mouse.destroy();
		Keyboard.destroy();
		GLFWErrorCallback callback = GLFW.glfwSetErrorCallback(null);
		if (callback != null) {
			callback.free();
		}
		// Destroy the window
		GLFW.glfwDestroyWindow(handle);
		GLFW.glfwTerminate();
		handle = -1L;
	}

	public static boolean isCreated() {
		return handle != -1L;
	}

	public static boolean isCloseRequested() {
		if (SDL) {
			return com.periut.retrodragon.window.sdl.Sdl3Events.closeRequested();
		}
		return GLFW.glfwWindowShouldClose(handle);
	}

	public static boolean isActive() {
		if (SDL) {
			// Headless has no focus concept; report active so nothing auto-pauses.
			return HEADLESS || com.periut.retrodragon.window.sdl.Sdl3Events.focused();
		}
		return focused;
	}

	public static void setResizable(boolean isResizable) {
		resizable = isResizable;
		if (SDL) {
			long window = Sdl3Window.handle();
			if (window != 0L) {
				// Unlike the GLFW branch below -- which sets a window HINT, i.e. a no-op once the
				// window exists -- SDL can actually change this after creation.
				MainThread.run(() -> SDLVideo.SDL_SetWindowResizable(window, isResizable));
			}
			return;
		}
		if (isCreated()) {
			GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, resizable ? 1 : 0);
		}
	}

	public static void sync(int fps) {
		Sync.sync(fps);
	}


	public static void setVSyncEnabled(boolean enabled) {
		if (SDL) {
			com.periut.retrodragon.window.sdl.Sdl3Window.setSwapInterval(enabled ? 1 : 0);
			return;
		}
		if (GLFW.glfwGetCurrentContext() != 0) {
			GLFW.glfwSwapInterval(enabled ? 1 : 0);
		}
	}

	public static boolean wasResized() {
		if (SDL) {
			return com.periut.retrodragon.window.sdl.Sdl3Events.consumeResized();
		}
		return window_resized;
	}

	public static boolean isVisible() {
		if (SDL) {
			return !HEADLESS;
		}
		return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) != 0;
	}

	public static float getContentScaleX() {
		// Sdl3Window.pixelsPerPoint() is exactly this ratio (SDL_GetWindowSizeInPixels over
		// SDL_GetWindowSize) and is already the number the SDL input path scales by.
		if (SDL) return Sdl3Window.pixelsPerPoint();
		if (handle == -1L) return 1.0f;
		int[] fbW = new int[1], winW = new int[1];
		GLFW.glfwGetFramebufferSize(handle, fbW, null);
		GLFW.glfwGetWindowSize(handle, winW, null);
		return winW[0] > 0 ? (float) fbW[0] / winW[0] : 1.0f;
	}

	public static float getContentScaleY() {
		if (SDL) return Sdl3Window.pixelsPerPoint();
		if (handle == -1L) return 1.0f;
		int[] fbH = new int[1], winH = new int[1];
		GLFW.glfwGetFramebufferSize(handle, null, fbH);
		GLFW.glfwGetWindowSize(handle, null, winH);
		return winH[0] > 0 ? (float) fbH[0] / winH[0] : 1.0f;
	}

	private static void resizeCallback(long window, int width, int height) {
		if (window == handle) {
			if (cinnamonSizeLimitActive) {
				cinnamonSizeLimitActive = false;
				GLFW.glfwSetWindowSizeLimits(handle, GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE, GLFW.GLFW_DONT_CARE);
			}
			window_resized = true;
			Display.width = width;
			Display.height = height;
		}
	}

	public static void swapBuffers() {
		if (SDL) {
			// Present gate (see update()): the Mojang splash and the texture-load frames present
			// through THIS path, not update(), so a booting child's frames are held here until it
			// reaches its logging-in screen. Until then the hub's last frame stays on screen.
			if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild()
					&& !com.periut.retrodragon.retrocenter.bridge.HubBridge.mayChildPresent()) {
				return;
			}
			com.periut.retrodragon.window.sdl.Sdl3Window.swapBuffers();
			return;
		}
		// Present gate (see update()): the Mojang splash presents through
		// THIS path, not update() -- a booting child's frames are held until
		// it reaches its logging-in screen.
		if (com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild()
				&& !com.periut.retrodragon.retrocenter.bridge.HubBridge.mayChildPresent()) {
			return;
		}
		GLFW.glfwSwapBuffers(handle);
	}

	/**
	 * Clipboard text, or {@code ""} when the clipboard is empty or unreadable.
	 *
	 * <p>Backend-agnostic on purpose: {@code glfwGetClipboardString(getHandle())} is a silent no-op
	 * on the SDL3 path (GLFW is never initialized there, so it early-returns on
	 * {@code _GLFW_REQUIRE_INIT} and returns NULL), which made copy/paste in text fields look
	 * merely "empty" rather than broken. Callers must route through here, never through raw GLFW.
	 *
	 * <p>Marshalled to thread 0 for the same reason {@code Sdl3Window.setTitle} is: SDL's Cocoa
	 * clipboard backend talks to {@code NSPasteboard}, and AppKit is not thread-safe.
	 */
	@NotNull
	public static String getClipboard() {
		if (SDL) {
			String text = MainThread.get(org.lwjgl.sdl.SDLClipboard::SDL_GetClipboardText);
			return text != null ? text : "";
		}
		String text = GLFW.glfwGetClipboardString(handle);
		return text != null ? text : "";
	}

	/** Replaces the clipboard contents. See {@link #getClipboard()} for why this is not raw GLFW. */
	public static void setClipboard(@Nullable String text) {
		String value = text != null ? text : "";
		if (SDL) {
			MainThread.run(() -> org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardText(value));
			return;
		}
		GLFW.glfwSetClipboardString(handle, value);
	}
}
