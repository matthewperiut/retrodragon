package com.periut.retrodragon.window.sdl;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;

/**
 * Keyboard and mouse state fed from the SDL3 event queue.
 *
 * Beta's input is LWJGL 2 shaped: a polled event *queue* (`Keyboard.next()`, `Mouse.next()`) plus
 * instantaneous state (`isKeyDown`, `getDX`). SDL is event-driven, so events are recorded into a
 * small ring here and replayed through the LWJGL 2 accessors.
 *
 * Scope note: this deliberately covers only what beta calls (see DESIGN-retroperf-webgpu.md -- ~30
 * methods across Display/Mouse/Keyboard). The rest of the LWJGL 2 API stays present via the shim
 * so mods still link; `tools/lwjgl2audit` is what keeps that honest.
 */
public final class Sdl3Input {
	private static final int QUEUE = 256;

	// Keyboard events: SDL scancode + pressed + character.
	private static final int[] keyCode = new int[QUEUE];
	private static final boolean[] keyState = new boolean[QUEUE];
	private static final char[] keyChar = new char[QUEUE];
	private static int keyHead;
	private static int keyTail;

	// Mouse events: button (-1 = motion/wheel only), state, position, wheel delta.
	private static final int[] mouseButton = new int[QUEUE];
	private static final boolean[] mouseState = new boolean[QUEUE];
	private static final int[] mouseX = new int[QUEUE];
	private static final int[] mouseY = new int[QUEUE];
	private static final int[] mouseWheel = new int[QUEUE];
	private static int mouseHead;
	private static int mouseTail;

	/** Held state, for the instantaneous isKeyDown/isButtonDown queries. */
	private static final boolean[] keyDown = new boolean[256];
	private static final boolean[] buttonDown = new boolean[16];

	private static int cursorX;
	private static int cursorY;
	private static int deltaX;
	private static int deltaY;

	private Sdl3Input() {
	}

	/** Called by {@link Sdl3Events#pump} for anything that is not a window event. */
	static void handle(SDL_Event event) {
		switch (event.type()) {
			case SDLEvents.SDL_EVENT_KEY_DOWN, SDLEvents.SDL_EVENT_KEY_UP -> {
				boolean down = event.type() == SDLEvents.SDL_EVENT_KEY_DOWN;
				// Translate to LWJGL 2 codes here, so everything downstream speaks beta's language.
				int key = Sdl3Keycodes.toLwjgl(event.key().scancode());
				if (key > 0 && key < keyDown.length) {
					keyDown[key] = down;
				}
				pushKey(key, down, '\0');
			}
			case SDLEvents.SDL_EVENT_TEXT_INPUT -> {
				// Text comes separately from key events in SDL; beta wants the character on the
				// key event, so it is pushed as a character-only entry.
				String text = event.text().textString();
				for (int i = 0; i < text.length(); i++) {
					pushKey(0, true, text.charAt(i));
				}
			}
			case SDLEvents.SDL_EVENT_MOUSE_MOTION -> {
				cursorX = (int) event.motion().x();
				cursorY = (int) event.motion().y();
				deltaX += (int) event.motion().xrel();
				// LWJGL 2's Y axis points up from the bottom; SDL's points down from the top.
				deltaY -= (int) event.motion().yrel();
				pushMouse(-1, false, cursorX, cursorY, 0);
			}
			case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN, SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP -> {
				boolean down = event.type() == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN;
				// SDL and LWJGL 2 disagree on the ORDER, not just the base.
				int button = lwjglButton(event.button().button());
				if (button >= 0 && button < buttonDown.length) {
					buttonDown[button] = down;
				}
				pushMouse(button, down, (int) event.button().x(), (int) event.button().y(), 0);
			}
			case SDLEvents.SDL_EVENT_MOUSE_WHEEL ->
				// LWJGL 2 reports wheel in 120ths of a notch.
				pushMouse(-1, false, cursorX, cursorY, (int) (event.wheel().y() * 120.0F));
			default -> {
				// not an input event
			}
		}
	}

	private static void pushKey(int code, boolean down, char character) {
		int next = (keyTail + 1) % QUEUE;
		if (next == keyHead) {
			return; // full; dropping is better than blocking the pump
		}
		keyCode[keyTail] = code;
		keyState[keyTail] = down;
		keyChar[keyTail] = character;
		keyTail = next;
	}

	private static void pushMouse(int button, boolean down, int x, int y, int wheel) {
		int next = (mouseTail + 1) % QUEUE;
		if (next == mouseHead) {
			return;
		}
		mouseButton[mouseTail] = button;
		mouseState[mouseTail] = down;
		mouseX[mouseTail] = x;
		mouseY[mouseTail] = y;
		mouseWheel[mouseTail] = wheel;
		mouseTail = next;
	}

	// --- LWJGL 2 style accessors ---------------------------------------------------------------

	public static boolean nextKey() {
		if (keyHead == keyTail) {
			return false;
		}
		currentKey = keyHead;
		keyHead = (keyHead + 1) % QUEUE;
		return true;
	}

	private static int currentKey;
	private static int currentMouse;

	public static int eventKey() {
		return keyCode[currentKey];
	}

	public static boolean eventKeyState() {
		return keyState[currentKey];
	}

	public static char eventCharacter() {
		return keyChar[currentKey];
	}

	public static boolean nextMouse() {
		if (mouseHead == mouseTail) {
			return false;
		}
		currentMouse = mouseHead;
		mouseHead = (mouseHead + 1) % QUEUE;
		return true;
	}

	public static int eventButton() {
		return mouseButton[currentMouse];
	}

	public static boolean eventButtonState() {
		return mouseState[currentMouse];
	}

	public static int eventX() {
		return mouseX[currentMouse];
	}

	public static int eventY() {
		return mouseY[currentMouse];
	}

	public static int eventWheel() {
		return mouseWheel[currentMouse];
	}

	public static int x() {
		return scale(cursorX);
	}

	public static int y() {
		return flipY(cursorY);
	}

	public static int eventXScaled() {
		return scale(mouseX[currentMouse]);
	}

	public static int eventYFlipped() {
		return flipY(mouseY[currentMouse]);
	}

	/**
	 * SDL reports pointer coordinates in logical POINTS; beta lays its GUI out in framebuffer
	 * PIXELS. On a HiDPI/fractional-scaling display those differ, and an unscaled coordinate puts
	 * every click in the wrong place -- which is invisible at 1:1 and completely broken at 2:1.
	 */
	private static int scale(int pointCoordinate) {
		return Math.round(pointCoordinate * Sdl3Window.pixelsPerPoint());
	}

	/** SDL's Y grows downward from the top; LWJGL 2's grows upward from the bottom. */
	private static int flipY(int pointCoordinate) {
		return Sdl3Window.height() - 1 - scale(pointCoordinate);
	}

	/**
	 * Relative mouse mode: SDL hides the cursor, stops reporting absolute position and delivers pure
	 * deltas. That is exactly LWJGL 2's grabbed contract, and it is what makes looking around work --
	 * without it the pointer hits the screen edge and the camera stops turning.
	 */
	public static void setGrabbed(boolean grab) {
		if (grabbed == grab || !Sdl3Window.isCreated()) {
			grabbed = grab;
			return;
		}
		grabbed = grab;
		org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode(Sdl3Window.handle(), grab);
		// Deltas accumulated while switching modes describe the jump to/from the warp position, not
		// player intent; dropping them stops the view snapping on every menu open and close.
		deltaX = 0;
		deltaY = 0;
	}

	public static boolean isGrabbed() {
		return grabbed;
	}

	/** Beta calls this to re-centre the pointer; the argument is in LWJGL 2's bottom-up pixel space. */
	public static void setCursorPosition(int pixelX, int pixelY) {
		if (!Sdl3Window.isCreated()) {
			return;
		}
		float perPoint = Sdl3Window.pixelsPerPoint();
		float pointX = pixelX / perPoint;
		float pointY = (Sdl3Window.height() - 1 - pixelY) / perPoint;
		org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(Sdl3Window.handle(), pointX, pointY);
		cursorX = Math.round(pointX);
		cursorY = Math.round(pointY);
	}

	private static boolean grabbed;

	/** Reads and clears, matching LWJGL 2's getDX/getDY contract. */
	public static boolean isKeyDown(int lwjglKey) {
		return lwjglKey >= 0 && lwjglKey < keyDown.length && keyDown[lwjglKey];
	}

	public static boolean isButtonDown(int button) {
		return button >= 0 && button < buttonDown.length && buttonDown[button];
	}

	public static int consumeDX() {
		int value = deltaX;
		deltaX = 0;
		return value;
	}

	public static int consumeDY() {
		int value = deltaY;
		deltaY = 0;
		return value;
	}

	/**
	 * SDL is left=1, middle=2, right=3; LWJGL 2 is left=0, right=1, middle=2.
	 *
	 * A plain {@code -1} lines up left but silently SWAPS right and middle, so every right-click
	 * reached beta as a middle-click -- "place block" did nothing and "pick block" fired instead.
	 */
	private static int lwjglButton(int sdlButton) {
		return switch (sdlButton) {
			case 1 -> 0;   // SDL_BUTTON_LEFT
			case 2 -> 2;   // SDL_BUTTON_MIDDLE
			case 3 -> 1;   // SDL_BUTTON_RIGHT
			default -> sdlButton - 1;
		};
	}
}
