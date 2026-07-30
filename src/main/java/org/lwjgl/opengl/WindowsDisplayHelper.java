package org.lwjgl.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Windows-specific display helpers. Matches the titlebar to the system
 * dark/light theme and enables the Mica backdrop on Windows 11 for the
 * translucent titlebar effect.
 */
final class WindowsDisplayHelper {
	private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
	/** Windows 11 22H2+ system backdrop type. 2 = Mica, 4 = Mica Alt */
	private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;

	private static long hwnd;
	private static long dwmSetWindowAttribute;
	private static boolean currentDarkMode;
	private static boolean initialized;
	private static long lastThemeCheckNanos;

	private WindowsDisplayHelper() {}

	/**
	 * Reads the system app theme from the registry.
	 * Returns true if the system is using dark mode.
	 */
	private static boolean isSystemDarkMode() {
		try {
			Process p = new ProcessBuilder(
					"reg", "query",
					"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
					"/v", "AppsUseLightTheme"
			).redirectErrorStream(true).start();
			String output = new String(p.getInputStream().readAllBytes());
			p.waitFor();
			// Output contains "0x0" for dark, "0x1" for light
			return output.contains("0x0");
		} catch (Exception e) {
			return false;
		}
	}

	private static void setAttribute(int attribute, int value) {
		ByteBuffer buf = MemoryUtil.memAlloc(4);
		try {
			buf.putInt(0, value);
			JNI.invokePPI(hwnd, attribute, MemoryUtil.memAddress(buf), 4, dwmSetWindowAttribute);
		} finally {
			MemoryUtil.memFree(buf);
		}
	}

	/**
	 * Initializes the titlebar style: detects system theme, applies
	 * dark/light mode, and enables Mica backdrop on Windows 11.
	 *
	 * <p>GLFW entry point. The SDL3 backend has no {@code GLFWwindow*} to hand over, so it goes
	 * through {@link #initFromHwnd} with the HWND SDL publishes on its window properties.
	 */
	static void init(long glfwWindow) {
		long nativeHwnd;
		try {
			nativeHwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
		} catch (Throwable t) {
			// Kept inside a catch because the whole body used to be: glfwGetWin32Window raises if
			// GLFW is not on the Win32 platform, and a missing titlebar tint must never be fatal.
			System.err.println("[Display] Failed to init Windows titlebar: " + t.getMessage());
			return;
		}
		initFromHwnd(nativeHwnd);
	}

	/**
	 * Backend-independent half of {@link #init}: everything from the HWND onwards.
	 *
	 * <p>Split out for SDL3, where the HWND comes from
	 * {@code SDL_GetPointerProperty(SDL_PROP_WINDOW_WIN32_HWND_POINTER)} rather than from
	 * {@code glfwGetWin32Window}. DWM itself does not care which toolkit created the window -- only
	 * that the HWND exists, which is why this is the only Windows-theming code the port needed.
	 */
	static void initFromHwnd(long nativeHwnd) {
		try {
			hwnd = nativeHwnd;
			if (hwnd == 0L) return;

			SharedLibrary dwmapi = org.lwjgl.system.APIUtil.apiCreateLibrary("dwmapi");
			dwmSetWindowAttribute = dwmapi.getFunctionAddress("DwmSetWindowAttribute");
			if (dwmSetWindowAttribute == 0L) return;

			initialized = true;
			currentDarkMode = isSystemDarkMode();
			setAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE, currentDarkMode ? 1 : 0);

			// Enable Mica backdrop (Windows 11 22H2+). Silently ignored on older builds.
			setAttribute(DWMWA_SYSTEMBACKDROP_TYPE, 2);
		} catch (Exception e) {
			System.err.println("[Display] Failed to init Windows titlebar: " + e.getMessage());
		}
	}

	/**
	 * Polls the system theme and updates the titlebar if it changed.
	 * Called from Display.update().
	 */
	static void pollThemeChange() {
		if (!initialized) return;
		long now = System.nanoTime();
		if (now - lastThemeCheckNanos < 2_000_000_000L) return; // check every 2 seconds
		lastThemeCheckNanos = now;
		setDarkMode(isSystemDarkMode());
	}

	/**
	 * Applies a theme the caller already knows, skipping the {@code reg query} subprocess.
	 *
	 * <p>SDL3 raises {@code SDL_EVENT_SYSTEM_THEME_CHANGED} and answers {@code SDL_GetSystemTheme},
	 * so on the SDL backend the registry is only consulted once at init and then never again.
	 */
	static void setDarkMode(boolean dark) {
		if (!initialized || dark == currentDarkMode) return;
		currentDarkMode = dark;
		setAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE, dark ? 1 : 0);
	}

	/** True once a DWM handle has been acquired; the SDL path uses it to skip dead work. */
	static boolean isInitialized() {
		return initialized;
	}
}
