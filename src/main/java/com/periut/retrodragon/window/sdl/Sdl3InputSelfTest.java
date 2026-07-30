package com.periut.retrodragon.window.sdl;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;

/**
 * Drives synthetic SDL events through the real input path and checks what beta would see.
 *
 * Input is the one subsystem that cannot be verified from a log or a screenshot: it needs a hand on
 * the mouse. This closes that gap by pushing events into SDL's own queue, so they travel the
 * complete route -- SDL queue, {@link Sdl3Events#pump}, {@link Sdl3Input}, the LWJGL 2 accessors --
 * and only the physical device is missing.
 *
 * It exists because defaulting the window to SDL3 while {@code Mouse} and {@code Keyboard} still
 * read the old opt-in property left the main menu unclickable, and nothing in the build or the logs
 * caught it.
 *
 * {@code -Dretrowindow.inputselftest=true}. Runs once, prints one line per check, then gets out of
 * the way.
 */
public final class Sdl3InputSelfTest {
	public static final boolean ENABLED = Boolean.getBoolean("retrowindow.inputselftest");

	private static boolean done;

	private Sdl3InputSelfTest() {
	}

	public static void runOnce() {
		if (done || !ENABLED || !Sdl3Window.isCreated()) {
			return;
		}
		done = true;

		int failures = 0;
		failures += checkMouseButton();
		failures += checkKey();
		failures += checkTextInput();
		failures += checkCoordinateMapping();

		if (failures == 0) {
			System.out.println("[RetroDragon] input self-test OK -- synthetic events reach the LWJGL 2 accessors");
		} else {
			System.out.println("[RetroDragon] input self-test FAILED on " + failures + " check(s)");
		}
	}

	/** A left-button press must arrive as LWJGL 2 button 0 with state true. */
	private static int checkMouseButton() {
		drain();
		try (SDL_Event event = SDL_Event.calloc()) {
			event.type(SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN);
			event.button().button((byte) 1); // SDL left button is 1; LWJGL 2 calls it 0
			event.button().x(40.0F);
			event.button().y(30.0F);
			SDLEvents.SDL_PushEvent(event);
		}
		Sdl3Events.pump();

		int failures = 0;
		if (!org.lwjgl.input.Mouse.next()) {
			return fail("Mouse.next() saw no event after a synthetic button press");
		}
		if (org.lwjgl.input.Mouse.getEventButton() != 0) {
			failures += fail("getEventButton()=" + org.lwjgl.input.Mouse.getEventButton() + ", expected 0");
		}
		// The bug that made the menu unclickable: this returned the GLFW field, which is never set.
		if (!org.lwjgl.input.Mouse.getEventButtonState()) {
			failures += fail("getEventButtonState()=false for a press -- clicks cannot register");
		}
		return failures;
	}

	/** A key press must arrive as the LWJGL 2 key code, held state included. */
	private static int checkKey() {
		drain();
		try (SDL_Event event = SDL_Event.calloc()) {
			event.type(SDLEvents.SDL_EVENT_KEY_DOWN);
			event.key().scancode(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_W);
			SDLEvents.SDL_PushEvent(event);
		}
		Sdl3Events.pump();

		int failures = 0;
		if (!org.lwjgl.input.Keyboard.next()) {
			return fail("Keyboard.next() saw no event after a synthetic key press");
		}
		int expected = org.lwjgl.input.Keyboard.KEY_W;
		if (org.lwjgl.input.Keyboard.getEventKey() != expected) {
			failures += fail("getEventKey()=" + org.lwjgl.input.Keyboard.getEventKey()
				+ ", expected KEY_W=" + expected);
		}
		if (!org.lwjgl.input.Keyboard.getEventKeyState()) {
			failures += fail("getEventKeyState()=false for a press");
		}
		if (!org.lwjgl.input.Keyboard.isKeyDown(expected)) {
			failures += fail("isKeyDown(KEY_W)=false after a press -- held state is not tracked");
		}
		return failures;
	}

	/**
	 * Typing: text input must be ACTIVE, and the character must reach beta on an event it will read.
	 *
	 * <p>Two separate failures, which is why both halves are checked. SDL3 starts text input off and
	 * emits no {@code SDL_EVENT_TEXT_INPUT} at all until it is started -- that is invisible from a
	 * synthetic event, because pushing one onto the queue bypasses the gate, so the flag is asserted
	 * directly. And {@code GuiScreen} only calls {@code keyTyped} when {@code getEventKeyState()} is
	 * true, so a character delivered on a released-key event would be dropped.
	 */
	private static int checkTextInput() {
		int failures = 0;
		if (!org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(Sdl3Window.handle())) {
			failures += fail("SDL_TextInputActive=false -- SDL3 generates no SDL_EVENT_TEXT_INPUT,"
				+ " so chat and signs receive '\\0' for every key");
		}

		drain();
		// Off-heap and freed only after the pump, NOT stack-allocated: SDL_PushEvent copies the event
		// struct but not the string it points at, so a stack buffer is already dead by the time the
		// pump reads it -- which showed up here as no event arriving at all.
		java.nio.ByteBuffer text = org.lwjgl.system.MemoryUtil.memUTF8("a");
		try (SDL_Event event = SDL_Event.calloc()) {
			event.type(SDLEvents.SDL_EVENT_TEXT_INPUT);
			event.text().text(text);
			SDLEvents.SDL_PushEvent(event);
			Sdl3Events.pump();
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(text);
		}

		if (!org.lwjgl.input.Keyboard.next()) {
			return failures + fail("Keyboard.next() saw no event after a synthetic text input");
		}
		if (org.lwjgl.input.Keyboard.getEventCharacter() != 'a') {
			failures += fail("getEventCharacter()='" + org.lwjgl.input.Keyboard.getEventCharacter()
				+ "', expected 'a'");
		}
		if (!org.lwjgl.input.Keyboard.getEventKeyState()) {
			failures += fail("getEventKeyState()=false on a character event -- GuiScreen skips keyTyped");
		}

		// The coupling itself: grabbed means no screen is open, so text input follows the grab
		// inverted. Restored afterwards, because the game is mid-run and owns this state.
		boolean wasGrabbed = Sdl3Input.isGrabbed();
		Sdl3Input.setGrabbed(true);
		if (org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(Sdl3Window.handle())) {
			failures += fail("text input still active while the pointer is grabbed -- the IME stays"
				+ " armed during play");
		}
		Sdl3Input.setGrabbed(false);
		if (!org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(Sdl3Window.handle())) {
			failures += fail("text input not re-armed on ungrab -- typing breaks in every screen"
				+ " opened from inside the world");
		}
		Sdl3Input.setGrabbed(wasGrabbed);
		return failures;
	}

	/**
	 * The Y axis and the point/pixel scale, which are invisible at 1:1 on an unscaled display and
	 * put every click in the wrong place on a HiDPI one.
	 */
	private static int checkCoordinateMapping() {
		drain();
		float perPoint = Sdl3Window.pixelsPerPoint();
		int height = Sdl3Window.height();
		try (SDL_Event event = SDL_Event.calloc()) {
			event.type(SDLEvents.SDL_EVENT_MOUSE_MOTION);
			event.motion().x(10.0F);
			event.motion().y(20.0F);
			SDLEvents.SDL_PushEvent(event);
		}
		Sdl3Events.pump();

		int failures = 0;
		int expectedX = Math.round(10.0F * perPoint);
		int expectedY = height - 1 - Math.round(20.0F * perPoint);
		if (org.lwjgl.input.Mouse.getX() != expectedX) {
			failures += fail("Mouse.getX()=" + org.lwjgl.input.Mouse.getX() + ", expected " + expectedX);
		}
		// SDL's origin is top-left, LWJGL 2's is bottom-left. Without the flip the menu responds to
		// clicks mirrored about the horizontal centre line.
		if (org.lwjgl.input.Mouse.getY() != expectedY) {
			failures += fail("Mouse.getY()=" + org.lwjgl.input.Mouse.getY() + ", expected " + expectedY
				+ " (height " + height + ", scale " + perPoint + ") -- Y axis not flipped");
		}
		return failures;
	}

	/** Empties both queues so a check only sees its own event. */
	private static void drain() {
		Sdl3Events.pump();
		while (org.lwjgl.input.Mouse.next()) {
			// discard
		}
		while (org.lwjgl.input.Keyboard.next()) {
			// discard
		}
	}

	private static int fail(String message) {
		System.out.println("[RetroDragon] input self-test: " + message);
		return 1;
	}
}
