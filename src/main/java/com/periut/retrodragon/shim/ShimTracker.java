package com.periut.retrodragon.shim;

import java.nio.FloatBuffer;

import com.periut.retrodragon.RetroDragon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Holds the live shim and checks it against real GL while the game renders.
 *
 * The synthetic validation proved the maths; this proves the *interception* -- that the shim sees
 * every state change beta and its mods actually make, in the order they make them. A WebGPU backend
 * has no driver to fall back on, so a single missed entry point means geometry silently drawn with
 * stale state. Catching that while a real GL context still exists to compare against is the whole
 * point.
 *
 * {@code -Dretroperf.trackShim=true} enables it. Divergence is reported once with both matrices,
 * then tracking stops so the log does not flood.
 */
public final class ShimTracker {
	public static final boolean ENABLED = Boolean.getBoolean("retroperf.trackShim");

	private static final GlShim SHIM = new GlShim();
	/**
	 * Immediate-mode geometry for the frame, fed by GlInterceptMixin.
	 *
	 * Lives beside the state shim because a draw is state + vertices: {@link GlShim} reduces the
	 * fixed-function state to a pipeline key, and this holds the vertices that key applies to.
	 */
	private static final GeometryCapture GEOMETRY = new GeometryCapture();
	private static final FloatBuffer GL_MATRIX = BufferUtils.createFloatBuffer(16);

	private static int frames;
	private static int checks;
	private static boolean diverged;

	private ShimTracker() {
	}

	public static GlShim shim() {
		return SHIM;
	}

	/**
	 * Compare at a frame boundary. Called after the game has issued a full frame of GL calls, so
	 * any missed entry point shows up as accumulated drift.
	 */
	public static void checkFrame() {
		if (!ENABLED || diverged) {
			return;
		}
		frames++;
		// Let the first frames settle: the shim starts at identity while the game may already have
		// state from startup that predates interception.
		if (frames < 200) {
			return;
		}
		if (frames % 60 != 0) {
			return;
		}

		GL_MATRIX.clear();
		GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, GL_MATRIX);
		float[] mine = SHIM.state().modelView();

		float worst = 0.0F;
		int worstIndex = -1;
		for (int i = 0; i < 16; i++) {
			float delta = Math.abs(GL_MATRIX.get(i) - mine[i]);
			if (delta > worst) {
				worst = delta;
				worstIndex = i;
			}
		}
		checks++;

		if (worst > 1e-3F) {
			diverged = true;
			StringBuilder sb = new StringBuilder();
			sb.append("shim DIVERGED from live GL after ").append(frames)
				.append(" frames (worst=").append(worst).append(" at [").append(worstIndex)
				.append("])\n  gl  =");
			for (int i = 0; i < 16; i++) {
				sb.append(' ').append(String.format("%.3f", GL_MATRIX.get(i)));
			}
			sb.append("\n  shim=");
			for (int i = 0; i < 16; i++) {
				sb.append(' ').append(String.format("%.3f", mine[i]));
			}
			sb.append("\n  A mismatch here means an un-intercepted GL entry point -- the shim is "
				+ "missing a call the game makes. Tracking stopped.");
			RetroDragon.LOGGER.error(sb.toString());
		} else if (checks % 5 == 0) {
			RetroDragon.LOGGER.info("shim tracking live GL: {} checks clean over {} frames (worst {})",
				checks, frames, String.format("%.6f", worst));
		}
	}

	/** Geometry captured this frame; see {@link GeometryCapture}. */
	public static GeometryCapture geometry() {
		return GEOMETRY;
	}
}
