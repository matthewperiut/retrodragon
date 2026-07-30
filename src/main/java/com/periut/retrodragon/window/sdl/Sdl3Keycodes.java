package com.periut.retrodragon.window.sdl;

import org.lwjgl.input.Keyboard;
import org.lwjgl.sdl.SDLScancode;

/**
 * SDL3 scancode to LWJGL 2 key code.
 *
 * This table is the difference between working keybinds and garbage input. LWJGL 2's codes are
 * DirectInput scancodes (W = 17, A = 30 ...), which beta stores directly in its options file and
 * compares against `Keyboard.KEY_*`. SDL uses USB HID scancodes. They are unrelated numbering
 * schemes, so every key has to be mapped explicitly -- there is no arithmetic shortcut.
 *
 * Scancodes rather than keycodes on purpose: scancodes are physical positions, so a French AZERTY
 * player still gets WASD on the same physical keys, which is what the LWJGL 2 codes meant.
 */
public final class Sdl3Keycodes {
	private static final int[] TO_LWJGL = new int[512];

	static {
		map(SDLScancode.SDL_SCANCODE_A, Keyboard.KEY_A);
		map(SDLScancode.SDL_SCANCODE_B, Keyboard.KEY_B);
		map(SDLScancode.SDL_SCANCODE_C, Keyboard.KEY_C);
		map(SDLScancode.SDL_SCANCODE_D, Keyboard.KEY_D);
		map(SDLScancode.SDL_SCANCODE_E, Keyboard.KEY_E);
		map(SDLScancode.SDL_SCANCODE_F, Keyboard.KEY_F);
		map(SDLScancode.SDL_SCANCODE_G, Keyboard.KEY_G);
		map(SDLScancode.SDL_SCANCODE_H, Keyboard.KEY_H);
		map(SDLScancode.SDL_SCANCODE_I, Keyboard.KEY_I);
		map(SDLScancode.SDL_SCANCODE_J, Keyboard.KEY_J);
		map(SDLScancode.SDL_SCANCODE_K, Keyboard.KEY_K);
		map(SDLScancode.SDL_SCANCODE_L, Keyboard.KEY_L);
		map(SDLScancode.SDL_SCANCODE_M, Keyboard.KEY_M);
		map(SDLScancode.SDL_SCANCODE_N, Keyboard.KEY_N);
		map(SDLScancode.SDL_SCANCODE_O, Keyboard.KEY_O);
		map(SDLScancode.SDL_SCANCODE_P, Keyboard.KEY_P);
		map(SDLScancode.SDL_SCANCODE_Q, Keyboard.KEY_Q);
		map(SDLScancode.SDL_SCANCODE_R, Keyboard.KEY_R);
		map(SDLScancode.SDL_SCANCODE_S, Keyboard.KEY_S);
		map(SDLScancode.SDL_SCANCODE_T, Keyboard.KEY_T);
		map(SDLScancode.SDL_SCANCODE_U, Keyboard.KEY_U);
		map(SDLScancode.SDL_SCANCODE_V, Keyboard.KEY_V);
		map(SDLScancode.SDL_SCANCODE_W, Keyboard.KEY_W);
		map(SDLScancode.SDL_SCANCODE_X, Keyboard.KEY_X);
		map(SDLScancode.SDL_SCANCODE_Y, Keyboard.KEY_Y);
		map(SDLScancode.SDL_SCANCODE_Z, Keyboard.KEY_Z);

		map(SDLScancode.SDL_SCANCODE_1, Keyboard.KEY_1);
		map(SDLScancode.SDL_SCANCODE_2, Keyboard.KEY_2);
		map(SDLScancode.SDL_SCANCODE_3, Keyboard.KEY_3);
		map(SDLScancode.SDL_SCANCODE_4, Keyboard.KEY_4);
		map(SDLScancode.SDL_SCANCODE_5, Keyboard.KEY_5);
		map(SDLScancode.SDL_SCANCODE_6, Keyboard.KEY_6);
		map(SDLScancode.SDL_SCANCODE_7, Keyboard.KEY_7);
		map(SDLScancode.SDL_SCANCODE_8, Keyboard.KEY_8);
		map(SDLScancode.SDL_SCANCODE_9, Keyboard.KEY_9);
		map(SDLScancode.SDL_SCANCODE_0, Keyboard.KEY_0);

		map(SDLScancode.SDL_SCANCODE_RETURN, Keyboard.KEY_RETURN);
		map(SDLScancode.SDL_SCANCODE_ESCAPE, Keyboard.KEY_ESCAPE);
		map(SDLScancode.SDL_SCANCODE_BACKSPACE, Keyboard.KEY_BACK);
		map(SDLScancode.SDL_SCANCODE_TAB, Keyboard.KEY_TAB);
		map(SDLScancode.SDL_SCANCODE_SPACE, Keyboard.KEY_SPACE);
		map(SDLScancode.SDL_SCANCODE_MINUS, Keyboard.KEY_MINUS);
		map(SDLScancode.SDL_SCANCODE_EQUALS, Keyboard.KEY_EQUALS);
		map(SDLScancode.SDL_SCANCODE_LEFTBRACKET, Keyboard.KEY_LBRACKET);
		map(SDLScancode.SDL_SCANCODE_RIGHTBRACKET, Keyboard.KEY_RBRACKET);
		map(SDLScancode.SDL_SCANCODE_BACKSLASH, Keyboard.KEY_BACKSLASH);
		map(SDLScancode.SDL_SCANCODE_SEMICOLON, Keyboard.KEY_SEMICOLON);
		map(SDLScancode.SDL_SCANCODE_APOSTROPHE, Keyboard.KEY_APOSTROPHE);
		map(SDLScancode.SDL_SCANCODE_GRAVE, Keyboard.KEY_GRAVE);
		map(SDLScancode.SDL_SCANCODE_COMMA, Keyboard.KEY_COMMA);
		map(SDLScancode.SDL_SCANCODE_PERIOD, Keyboard.KEY_PERIOD);
		map(SDLScancode.SDL_SCANCODE_SLASH, Keyboard.KEY_SLASH);

		map(SDLScancode.SDL_SCANCODE_LCTRL, Keyboard.KEY_LCONTROL);
		map(SDLScancode.SDL_SCANCODE_RCTRL, Keyboard.KEY_RCONTROL);
		map(SDLScancode.SDL_SCANCODE_LSHIFT, Keyboard.KEY_LSHIFT);
		map(SDLScancode.SDL_SCANCODE_RSHIFT, Keyboard.KEY_RSHIFT);
		map(SDLScancode.SDL_SCANCODE_LALT, Keyboard.KEY_LMENU);
		map(SDLScancode.SDL_SCANCODE_RALT, Keyboard.KEY_RMENU);
		map(SDLScancode.SDL_SCANCODE_LGUI, Keyboard.KEY_LMETA);
		map(SDLScancode.SDL_SCANCODE_RGUI, Keyboard.KEY_RMETA);
		map(SDLScancode.SDL_SCANCODE_CAPSLOCK, Keyboard.KEY_CAPITAL);

		map(SDLScancode.SDL_SCANCODE_F1, Keyboard.KEY_F1);
		map(SDLScancode.SDL_SCANCODE_F2, Keyboard.KEY_F2);
		map(SDLScancode.SDL_SCANCODE_F3, Keyboard.KEY_F3);
		map(SDLScancode.SDL_SCANCODE_F4, Keyboard.KEY_F4);
		map(SDLScancode.SDL_SCANCODE_F5, Keyboard.KEY_F5);
		map(SDLScancode.SDL_SCANCODE_F6, Keyboard.KEY_F6);
		map(SDLScancode.SDL_SCANCODE_F7, Keyboard.KEY_F7);
		map(SDLScancode.SDL_SCANCODE_F8, Keyboard.KEY_F8);
		map(SDLScancode.SDL_SCANCODE_F9, Keyboard.KEY_F9);
		map(SDLScancode.SDL_SCANCODE_F10, Keyboard.KEY_F10);
		map(SDLScancode.SDL_SCANCODE_F11, Keyboard.KEY_F11);
		map(SDLScancode.SDL_SCANCODE_F12, Keyboard.KEY_F12);

		map(SDLScancode.SDL_SCANCODE_UP, Keyboard.KEY_UP);
		map(SDLScancode.SDL_SCANCODE_DOWN, Keyboard.KEY_DOWN);
		map(SDLScancode.SDL_SCANCODE_LEFT, Keyboard.KEY_LEFT);
		map(SDLScancode.SDL_SCANCODE_RIGHT, Keyboard.KEY_RIGHT);
		map(SDLScancode.SDL_SCANCODE_INSERT, Keyboard.KEY_INSERT);
		map(SDLScancode.SDL_SCANCODE_DELETE, Keyboard.KEY_DELETE);
		map(SDLScancode.SDL_SCANCODE_HOME, Keyboard.KEY_HOME);
		map(SDLScancode.SDL_SCANCODE_END, Keyboard.KEY_END);
		map(SDLScancode.SDL_SCANCODE_PAGEUP, Keyboard.KEY_PRIOR);
		map(SDLScancode.SDL_SCANCODE_PAGEDOWN, Keyboard.KEY_NEXT);
		map(SDLScancode.SDL_SCANCODE_PAUSE, Keyboard.KEY_PAUSE);
		map(SDLScancode.SDL_SCANCODE_SCROLLLOCK, Keyboard.KEY_SCROLL);

		map(SDLScancode.SDL_SCANCODE_KP_0, Keyboard.KEY_NUMPAD0);
		map(SDLScancode.SDL_SCANCODE_KP_1, Keyboard.KEY_NUMPAD1);
		map(SDLScancode.SDL_SCANCODE_KP_2, Keyboard.KEY_NUMPAD2);
		map(SDLScancode.SDL_SCANCODE_KP_3, Keyboard.KEY_NUMPAD3);
		map(SDLScancode.SDL_SCANCODE_KP_4, Keyboard.KEY_NUMPAD4);
		map(SDLScancode.SDL_SCANCODE_KP_5, Keyboard.KEY_NUMPAD5);
		map(SDLScancode.SDL_SCANCODE_KP_6, Keyboard.KEY_NUMPAD6);
		map(SDLScancode.SDL_SCANCODE_KP_7, Keyboard.KEY_NUMPAD7);
		map(SDLScancode.SDL_SCANCODE_KP_8, Keyboard.KEY_NUMPAD8);
		map(SDLScancode.SDL_SCANCODE_KP_9, Keyboard.KEY_NUMPAD9);
		map(SDLScancode.SDL_SCANCODE_KP_ENTER, Keyboard.KEY_NUMPADENTER);
		map(SDLScancode.SDL_SCANCODE_KP_PLUS, Keyboard.KEY_ADD);
		map(SDLScancode.SDL_SCANCODE_KP_MINUS, Keyboard.KEY_SUBTRACT);
		map(SDLScancode.SDL_SCANCODE_KP_MULTIPLY, Keyboard.KEY_MULTIPLY);
		map(SDLScancode.SDL_SCANCODE_KP_DIVIDE, Keyboard.KEY_DIVIDE);
		map(SDLScancode.SDL_SCANCODE_KP_PERIOD, Keyboard.KEY_DECIMAL);
		map(SDLScancode.SDL_SCANCODE_NUMLOCKCLEAR, Keyboard.KEY_NUMLOCK);
	}

	private Sdl3Keycodes() {
	}

	private static void map(int scancode, int lwjglKey) {
		if (scancode >= 0 && scancode < TO_LWJGL.length) {
			TO_LWJGL[scancode] = lwjglKey;
		}
	}

	/** @return the LWJGL 2 key code, or {@code Keyboard.KEY_NONE} for keys we do not map. */
	public static int toLwjgl(int scancode) {
		return scancode >= 0 && scancode < TO_LWJGL.length ? TO_LWJGL[scancode] : Keyboard.KEY_NONE;
	}
}
