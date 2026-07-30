package com.periut.retrodragon.shim;

import java.nio.FloatBuffer;

import com.periut.retrodragon.RetroDragon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Checks the shim's matrix maths against the real GL implementation.
 *
 * {@link GlState}'s own self-check proves it is internally consistent; it does not prove it matches
 * what OpenGL actually does. Multiplication order, rotation handedness and column-major layout are
 * all easy to get self-consistently wrong, and under WebGPU there is no driver left to compare
 * against -- every such error would surface as geometry in the wrong place, with nothing to diff.
 *
 * So this runs the same sequence of operations through both the real fixed-function matrix stack
 * and the shim, and compares the resulting matrices element by element. It runs in-game because
 * that is where a real GL context exists.
 *
 * Enable with {@code -Dretroperf.validateShim=true}; it runs once and logs the result.
 */
public final class ShimValidator {
	private static boolean done;

	private ShimValidator() {
	}

	/** Call with a current GL context. Runs once. */
	public static void runOnce() {
		if (done) {
			return;
		}
		done = true;

		FloatBuffer glMatrix = BufferUtils.createFloatBuffer(16);
		GlShim shim = new GlShim();
		int failures = 0;

		// Preserve whatever the game had; this must not disturb rendering.
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();

		// 1. identity
		GL11.glLoadIdentity();
		shim.glLoadIdentity();
		failures += compare(glMatrix, shim, "identity");

		// 2. translate
		GL11.glTranslatef(1.0F, 2.0F, 3.0F);
		shim.glTranslatef(1.0F, 2.0F, 3.0F);
		failures += compare(glMatrix, shim, "translate");

		// 3. rotate -- handedness and axis convention
		GL11.glRotatef(37.0F, 0.0F, 1.0F, 0.0F);
		shim.glRotatef(37.0F, 0.0F, 1.0F, 0.0F);
		failures += compare(glMatrix, shim, "rotate Y 37");

		// 4. a second rotate about a different axis; catches multiplication ORDER, which a single
		//    rotation cannot.
		GL11.glRotatef(-22.0F, 1.0F, 0.0F, 0.0F);
		shim.glRotatef(-22.0F, 1.0F, 0.0F, 0.0F);
		failures += compare(glMatrix, shim, "rotate X -22 after Y");

		// 5. non-uniform scale
		GL11.glScalef(2.0F, 0.5F, 1.5F);
		shim.glScalef(2.0F, 0.5F, 1.5F);
		failures += compare(glMatrix, shim, "scale");

		// 6. translate again on top of a rotated+scaled basis -- the case where a wrong
		//    multiplication order finally diverges visibly.
		GL11.glTranslatef(-4.0F, 7.0F, 0.25F);
		shim.glTranslatef(-4.0F, 7.0F, 0.25F);
		failures += compare(glMatrix, shim, "translate after rotate+scale");

		// 7. push/pop must restore exactly.
		GL11.glPushMatrix();
		shim.glPushMatrix();
		GL11.glTranslatef(100.0F, 0.0F, 0.0F);
		shim.glTranslatef(100.0F, 0.0F, 0.0F);
		failures += compare(glMatrix, shim, "inside push");
		GL11.glPopMatrix();
		shim.glPopMatrix();
		failures += compare(glMatrix, shim, "after pop");

		// 8. an arbitrary rotation axis, which exercises the full Rodrigues form.
		GL11.glRotatef(63.0F, 0.3F, -0.7F, 0.5F);
		shim.glRotatef(63.0F, 0.3F, -0.7F, 0.5F);
		failures += compare(glMatrix, shim, "rotate arbitrary axis");

		GL11.glPopMatrix();

		if (failures == 0) {
			RetroDragon.LOGGER.info("shim validation OK -- matrix maths matches real GL on all 9 checks");
		} else {
			RetroDragon.LOGGER.error("shim validation FAILED on {} check(s) -- see above", failures);
		}
	}

	private static int compare(FloatBuffer glMatrix, GlShim shim, String what) {
		glMatrix.clear();
		GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, glMatrix);
		float[] mine = shim.state().modelView();

		float worst = 0.0F;
		int worstIndex = -1;
		for (int i = 0; i < 16; i++) {
			float delta = Math.abs(glMatrix.get(i) - mine[i]);
			if (delta > worst) {
				worst = delta;
				worstIndex = i;
			}
		}
		// Single-precision accumulated over several multiplies; anything real is far larger.
		if (worst > 1e-4F) {
			StringBuilder sb = new StringBuilder();
			sb.append("shim MISMATCH at '").append(what).append("' worst=").append(worst)
				.append(" at [").append(worstIndex).append("]\n  gl  =");
			for (int i = 0; i < 16; i++) {
				sb.append(' ').append(String.format("%.4f", glMatrix.get(i)));
			}
			sb.append("\n  shim=");
			for (int i = 0; i < 16; i++) {
				sb.append(' ').append(String.format("%.4f", mine[i]));
			}
			RetroDragon.LOGGER.error(sb.toString());
			return 1;
		}
		return 0;
	}
}
