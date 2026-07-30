package com.periut.retrodragon.render;

import org.lwjgl.opengl.GL11;

/**
 * Mirrors beta's fixed-function fog mode so our terrain shader can reproduce it.
 *
 * The shader has to know whether fog is LINEAR or EXP, and GLSL 120's {@code gl_Fog} struct exposes
 * start/end/scale/density/colour but *not* the mode. Rather than query GL -- which this mod never
 * does -- we observe beta setting it: {@code GameRenderer} calls {@code glFogi(GL_FOG_MODE, ...)}
 * and a redirect records the value on the way through.
 *
 * Beta uses EXP with density 0.1 underwater and 2.0 in lava, LINEAR everywhere else. Without this,
 * the shader applied linear fog in all three cases and got water and lava visibly wrong.
 */
public final class FogState {
	public static final int LINEAR = 0;
	public static final int EXP = 1;
	public static final int EXP2 = 2;

	private static int mode = LINEAR;

	private FogState() {
	}

	/** Called from the GameRenderer redirect with the raw GL enum beta passed. */
	public static void noteMode(int glMode) {
		mode = switch (glMode) {
			case GL11.GL_EXP -> EXP;
			case GL11.GL_EXP2 -> EXP2;
			default -> LINEAR;
		};
	}

	public static int mode() {
		return mode;
	}
}
