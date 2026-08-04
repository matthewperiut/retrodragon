package com.periut.retrodragon.window;

import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.MacOSXLibrary;

/**
 * Stops macOS from eating key combinations the game needs while the player is actually playing.
 *
 * <h2>The problem</h2>
 *
 * A handful of shortcuts never reach the application at all on macOS. Ctrl+Space is the visible
 * one: it is the default binding for "Select the previous input source", so a player who has bound
 * anything to control -- sprint, most obviously -- gets the language picker every time they jump.
 * Cmd+Tab, Cmd+Space and the F-key shortcuts are the same story. These are SYMBOLIC HOT KEYS: the
 * window server matches them before the event is dispatched to any process, so no amount of work at
 * the SDL, GLFW or NSEvent layer can see them, let alone swallow them. Monitoring events is what
 * mods like MCMacOSInputFixes do, and it fixes the things that DO arrive (ctrl+click, scroll) --
 * it cannot fix this one.
 *
 * <h2>The fix</h2>
 *
 * HIToolbox exposes {@code PushSymbolicHotKeyMode}/{@code PopSymbolicHotKeyMode}, the pair remote
 * desktop clients and full screen games use to hold the system's shortcuts back while they own the
 * keyboard. Pushing {@code kHIHotKeyModeAllDisabled} suppresses them as a group -- there is no
 * public way to disable one -- so this is tied to the pointer grab rather than left on:
 *
 * <ul>
 *   <li>grabbed and focused: no screen is open, the player is looking around, and every keystroke
 *       is meant for the game. Suppress.</li>
 *   <li>anything else -- a GUI is up, or the window lost focus: restore immediately. Chat is
 *       exactly where switching input source is a reasonable thing to do, and leaving the system's
 *       shortcuts disabled while the player is in another application would be indefensible.</li>
 * </ul>
 *
 * The cost, which is inherent to the API rather than to this implementation: while you are in game
 * Cmd+Tab does nothing. Press escape first, the way every full screen game has always worked.
 *
 * <p>Both functions are looked up through the already-loaded HIToolbox bundle, so nothing is
 * dlopened and no native library ships with the mod. They are Carbon-era SPI: if a future macOS
 * drops them the lookup fails once, the class marks itself unavailable and every call after that is
 * a no-op, which costs the player nothing but the original annoyance.
 */
public final class MacSystemHotkeys {
	private static final boolean MACOS = System.getProperty("os.name", "").toLowerCase().contains("mac");

	/** {@code kHIHotKeyModeAllDisabled}. */
	private static final int ALL_DISABLED = 0xFFFFFFFF;

	private static boolean resolved;
	private static boolean unavailable;
	private static long pushFunction;
	private static long popFunction;

	/** Non-zero while suppression is active: the opaque token Push returned, needed to Pop. */
	private static long pushedMode;

	private static boolean grabbed;
	private static boolean focused = true;

	private MacSystemHotkeys() {
	}

	/** Called when the pointer grab changes, i.e. whenever a screen opens or closes. */
	public static void setGrabbed(boolean grab) {
		grabbed = grab;
		apply();
	}

	/** Called when the window gains or loses focus. */
	public static void setFocused(boolean focus) {
		focused = focus;
		apply();
	}

	/** Restores the system's shortcuts unconditionally; for window teardown. */
	public static void release() {
		grabbed = false;
		apply();
	}

	private static void apply() {
		if (!MACOS) {
			return;
		}

		boolean suppress = grabbed && focused;
		if (suppress == (pushedMode != 0L)) {
			return;
		}

		if (!resolve()) {
			return;
		}

		// Thread 0, in keeping with everything else that talks to the window server from here. The
		// queue is FIFO, so a push can never overtake the pop that has to precede it.
		MainThread.run(() -> {
			if (suppress) {
				pushedMode = JNI.invokeP(ALL_DISABLED, pushFunction);
			} else {
				JNI.invokePV(pushedMode, popFunction);
				pushedMode = 0L;
			}
		});
	}

	private static boolean resolve() {
		if (resolved) {
			return !unavailable;
		}

		resolved = true;
		try {
			MacOSXLibrary hiToolbox = MacOSXLibrary.getWithIdentifier("com.apple.HIToolbox");
			pushFunction = hiToolbox.getFunctionAddress("PushSymbolicHotKeyMode");
			popFunction = hiToolbox.getFunctionAddress("PopSymbolicHotKeyMode");
			unavailable = pushFunction == 0L || popFunction == 0L;
		} catch (Throwable t) {
			unavailable = true;
		}

		if (unavailable) {
			System.err.println("[RetroDragon] HIToolbox symbolic hot key control is unavailable;"
				+ " macOS shortcuts such as ctrl+space will keep taking priority over key binds.");
		}

		return !unavailable;
	}
}
