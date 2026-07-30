package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.periut.retrodragon.window.sdl.Sdl3Window;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Draws wide GL lines as expanded quads, so the GL backend produces the same outline WebGPU does.
 *
 * <h2>Why GL needs this even though it HAS glLineWidth</h2>
 *
 * {@link LineExpander} exists because WebGPU has no line width. GL does, so the obvious thing is to
 * let the driver handle it -- and that is what produced two different-looking games:
 *
 * <ul>
 *   <li><b>HiDPI.</b> {@code glLineWidth(2)} means two PHYSICAL pixels. With the retina drawable on,
 *       beta's two-pixel outline is half as thick as it is at 1x, and half as thick as the WebGPU
 *       one, which scales the width by {@link Sdl3Window#pixelsPerPoint()}. Now that retina defaults
 *       to ON everywhere this is what the outline looks like by default, not a corner case.</li>
 *   <li><b>Snapping.</b> The expander puts the centre line on the logical pixel grid according to the
 *       width's parity, so the edges land on whole pixels. A driver rasterising a wide line makes its
 *       own choices there, and they are not beta's, not consistent between vendors, and not
 *       consistent with the other backend.</li>
 *   <li><b>Depth.</b> The expander pulls the line towards the camera along the view ray, which does
 *       not move it on screen; the GL path was relying on beta's own 0.002 box inflation instead.</li>
 * </ul>
 *
 * <p>So both backends now run the same geometry through the same code, from the same inputs, and the
 * only thing that differs afterwards is which API rasterises the quads. The expander itself is
 * untouched -- it already took the matrices, the viewport and the pixel scale as parameters rather
 * than reading them from the shim, which is what made it reusable here.
 *
 * <h2>State</h2>
 *
 * Everything is restored. beta's {@code Tessellator.draw()} is cancelled when this runs, so its own
 * client-state setup and teardown never happen and this has to be self-contained: the modelview is
 * pushed (the expander emits EYE space, so the modelview goes identity while the projection stays),
 * the enable and polygon bits are pushed, and the client arrays are turned off again on the way out.
 */
public final class GlLineExpansion {

	/** Matches {@code WebGpuFrame}'s outline bias, in the same glPolygonOffset units it names. */
	private static final float BIAS = Integer.getInteger("retroperf.outlineBias", -2);
	private static final float SLOPE = Integer.getInteger("retroperf.outlineSlope", -1);

	/** Beta's Tessellator vertex: 8 ints, colour at byte 20. Shared with fixedfunc.wgsl. */
	private static final int STRIDE = LineExpander.STRIDE_BYTES;
	private static final int COLOR_OFFSET = 20;

	private static final LineExpander EXPANDER = new LineExpander();
	private static final float[] MODELVIEW = new float[16];
	private static final float[] PROJECTION = new float[16];
	private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);
	private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);

	private GlLineExpansion() {
	}

	/** Whether a batch is a candidate at all, cheap enough to ask before copying the vertices. */
	public static boolean isLineMode(int glMode) {
		return glMode == Primitives.GL_LINES
			|| glMode == Primitives.GL_LINE_STRIP
			|| glMode == Primitives.GL_LINE_LOOP;
	}

	/**
	 * Expands and draws a line batch.
	 *
	 * @param hasColor whether the batch carries per-vertex colour. Passed through rather than
	 *     assumed: beta draws the outline with {@code glColor4f} and no vertex colour at all, so
	 *     enabling the colour array would read the expander's copied-but-unused slot and paint the
	 *     outline whatever happened to be there. The same reasoning as the WebGPU path's uniform.
	 * @return true if this handled the batch and the caller should not fall through to GL
	 */
	public static boolean draw(ByteBuffer source, int vertexCount, int glMode, boolean hasColor) {
		if (RenderBackend.isWebGpu() || !isLineMode(glMode)) {
			return false;
		}
		float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
		if (lineWidth <= 1.0F) {
			// A one-pixel line is one pixel under any rasteriser; expanding it would only add work
			// and a chance to disagree with the driver about which pixel.
			return false;
		}

		MATRIX.clear();
		GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MATRIX);
		MATRIX.get(MODELVIEW);
		MATRIX.clear();
		GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, MATRIX);
		MATRIX.get(PROJECTION);
		VIEWPORT.clear();
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
		int viewportW = VIEWPORT.get(2);
		int viewportH = VIEWPORT.get(3);

		if (!EXPANDER.expand(source, vertexCount, glMode, MODELVIEW, PROJECTION,
				viewportW, viewportH, lineWidth, Sdl3Window.pixelsPerPoint())) {
			// Entirely behind the eye, or degenerate. Handled -- drawing the raw line instead would
			// put the un-expanded outline on screen for that frame, which is a visible flicker.
			return true;
		}

		ByteBuffer data = EXPANDER.data();
		int quadVertices = EXPANDER.vertexCount();

		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_POLYGON_BIT);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		// The projection is deliberately left alone: the vertices are in eye space so the GPU still
		// does the perspective divide, which is what keeps depth and fog behaving like every other
		// batch. See LineExpander's class docs.
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
		GL11.glPolygonOffset(SLOPE, BIAS);

		ByteBuffer positions = data.duplicate();
		positions.position(0);
		GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, positions);
		if (hasColor) {
			ByteBuffer colors = data.duplicate();
			colors.position(COLOR_OFFSET);
			GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
			GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, colors);
		}

		GL11.glDrawArrays(GL11.GL_QUADS, 0, quadVertices);

		if (hasColor) {
			GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
		}
		GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glPopMatrix();
		GL11.glPopAttrib();
		return true;
	}
}
