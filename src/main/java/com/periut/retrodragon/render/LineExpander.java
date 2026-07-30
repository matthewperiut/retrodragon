package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns wide GL lines into quads, because WebGPU has no line width.
 *
 * <p>{@code glLineWidth(2)} is not a thing a modern API can be asked for -- every line is exactly one
 * pixel -- so the width has to become geometry. b1.7.3 uses it for one visible thing, the block
 * selection outline, which vanilla draws two pixels wide.
 *
 * <h2>Why the CPU, and why EYE space</h2>
 *
 * A line's width is a SCREEN-space quantity: it stays two pixels whether the block is at arm's
 * length or thirty metres away. Widening it in object space would make it taper with distance, so
 * the segment has to know where it lands on screen before it can be widened -- and the shim already
 * owns the modelview and projection, so that happens here rather than in a shader.
 *
 * <p>The quad is emitted in EYE space, with the game's real projection left in place and only the
 * modelview replaced by identity. That is the difference between a shortcut and a general
 * mechanism. Pre-dividing to NDC would hand the rasteriser vertices that have already had their
 * perspective removed, which throws away correct depth interpolation and stops fog -- which is a
 * function of eye-space distance -- from working at all. Keeping the projection means the GPU does
 * the divide, so depth, fog and blending behave exactly as they do for every other batch, and any
 * GL code that draws lines gets the same treatment rather than the outline being special-cased.
 *
 * <p>The width is converted into an eye-space offset per endpoint, scaled by that endpoint's own
 * clip {@code w}, which is what makes it constant on screen. The same arithmetic covers an
 * orthographic projection, where {@code w} is 1 and nothing varies with depth.
 *
 * <h2>HiDPI</h2>
 *
 * All the geometry is computed in LOGICAL pixels and scaled up at the end. On a 2x display that
 * makes a two-pixel line four physical pixels wide, and -- because the endpoints are snapped to the
 * logical pixel grid first -- its edges land exactly on logical pixel boundaries. So the line is
 * blocky in the same places at 1x and 2x, and toggling HiDPI changes the resolution of everything
 * else without changing how the outline looks.
 */
public final class LineExpander {
	/** ints per vertex, matching beta's Tessellator and {@code fixedfunc.wgsl}. */
	private static final int STRIDE_INTS = 8;
	public static final int STRIDE_BYTES = STRIDE_INTS * 4;

	/** Anything at or behind this clip-space w is behind the eye and cannot be projected. */
	private static final float NEAR_EPSILON = 1.0e-5F;

	private int[] out = new int[STRIDE_INTS * 64];
	private int pos;
	private int vertexCount;

	/** Quads, four vertices each -- the shape the rest of the renderer already knows how to index. */
	public int vertexCount() {
		return vertexCount;
	}

	public ByteBuffer data() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(pos * 4).order(ByteOrder.nativeOrder());
		buffer.asIntBuffer().put(out, 0, pos);
		buffer.position(0);
		buffer.limit(pos * 4);
		return buffer;
	}

	/**
	 * Expands one line batch.
	 *
	 * @param source     beta's packed vertices, from the buffer's current position
	 * @param vertices   how many of them
	 * @param glMode     {@link Primitives#GL_LINES}, {@code GL_LINE_STRIP} or {@code GL_LINE_LOOP}
	 * @param modelView  column-major, as GL stores it
	 * @param projection column-major; kept, so the GPU still does the perspective divide
	 * @param viewportW  framebuffer width in PHYSICAL pixels
	 * @param viewportH  framebuffer height in physical pixels
	 * @param widthPx    the width GL was asked for, in logical pixels
	 * @param pixelScale physical pixels per logical pixel; 1 on a normal display, 2 on Retina
	 * @return false if nothing survived projection, in which case the caller should draw nothing
	 */
	public boolean expand(ByteBuffer source, int vertices, int glMode, float[] modelView,
			float[] projection, int viewportW, int viewportH, float widthPx, float pixelScale) {

		pos = 0;
		vertexCount = 0;
		if (vertices < 2 || viewportW <= 0 || viewportH <= 0) {
			return false;
		}
		int base = source.position();
		int segments = switch (glMode) {
			case Primitives.GL_LINES -> vertices / 2;
			case Primitives.GL_LINE_LOOP -> vertices;
			default -> vertices - 1;
		};

		// Logical-pixel viewport: everything below is computed here, then scaled back up.
		float scale = pixelScale <= 0.0F ? 1.0F : pixelScale;
		float logicalW = viewportW / scale;
		float logicalH = viewportH / scale;
		float lineWidth = Math.max(widthPx, 1.0F);
		float half = lineWidth * 0.5F;
		// Where the centre line has to sit for the EDGES to land on logical pixel boundaries depends
		// on the width's parity, exactly as GL's own rasterisation does: an even width straddles a
		// boundary, an odd one straddles a pixel centre. Getting this backwards puts every edge on a
		// half-pixel, which is the soft two-tone edge HiDPI was supposed to stop producing.
		boolean evenWidth = Math.round(lineWidth) % 2 == 0;

		for (int s = 0; s < segments; s++) {
			int ia;
			int ib;
			switch (glMode) {
				case Primitives.GL_LINES -> {
					ia = s * 2;
					ib = ia + 1;
				}
				case Primitives.GL_LINE_LOOP -> {
					ia = s;
					// A loop closes back onto its first vertex. GL has the primitive, WebGPU does not,
					// and expanding to quads is the one place the closing segment can be put back.
					ib = (s + 1) % vertices;
				}
				default -> {
					ia = s;
					ib = s + 1;
				}
			}

			float[] eyeA = pullTowardsCamera(transform(source, base, ia, modelView), projection);
			float[] eyeB = pullTowardsCamera(transform(source, base, ib, modelView), projection);
			float[] clipA = apply(projection, eyeA);
			float[] clipB = apply(projection, eyeB);
			if (clipA[3] <= NEAR_EPSILON || clipB[3] <= NEAR_EPSILON) {
				// One or both endpoints are at or behind the eye and cannot be projected. Dropping the
				// segment is honest: clipping it properly needs a near-plane intersection, and beta
				// only draws these around a block the camera is looking AT.
				continue;
			}

			// Where each endpoint lands, in LOGICAL pixels.
			float ax = (clipA[0] / clipA[3] * 0.5F + 0.5F) * logicalW;
			float ay = (0.5F - clipA[1] / clipA[3] * 0.5F) * logicalH;
			float bx = (clipB[0] / clipB[3] * 0.5F + 0.5F) * logicalW;
			float by = (0.5F - clipB[1] / clipB[3] * 0.5F) * logicalH;

			// Snap the CENTRE LINE to the logical pixel grid before widening. This is what makes the
			// result identical at 1x and 2x: the quad's edges then fall on integer logical
			// boundaries, so scaling up by the DPI factor covers whole physical pixels instead of
			// smearing each edge across two.
			float snapAx = snap(ax, evenWidth) - ax;
			float snapAy = snap(ay, evenWidth) - ay;
			float snapBx = snap(bx, evenWidth) - bx;
			float snapBy = snap(by, evenWidth) - by;

			float dx = bx + snapBx - ax - snapAx;
			float dy = by + snapBy - ay - snapAy;
			float length = (float) Math.sqrt(dx * dx + dy * dy);
			if (length < 1.0e-6F) {
				continue;
			}
			// Perpendicular, in logical pixels.
			float nx = -dy / length * half;
			float ny = dx / length * half;

			// Pixels -> eye space, per endpoint, scaled by that endpoint's own w. This is what keeps
			// the width constant on screen while the vertices stay in a space the projection can
			// still divide.
			float[] pa = pixelToEye(projection, clipA[3], logicalW, logicalH);
			float[] pb = pixelToEye(projection, clipB[3], logicalW, logicalH);
			if (pa == null || pb == null) {
				continue;
			}

			// Copied verbatim. Whether it is USED is the batch's hasColor flag, which the caller
			// passes to the uniform block -- beta draws the outline with glColor4f and no per-vertex
			// colour at all, and the shader falls back to the current colour exactly as GL does.
			int colour = source.getInt(base + ia * STRIDE_BYTES + 20);
			emit(eyeA, snapAx + nx, snapAy + ny, pa, colour);
			emit(eyeB, snapBx + nx, snapBy + ny, pb, colour);
			emit(eyeB, snapBx - nx, snapBy - ny, pb, colour);
			emit(eyeA, snapAx - nx, snapAy - ny, pa, colour);
		}
		return vertexCount > 0;
	}

	/**
	 * How far the line is pulled towards the camera, as a fraction of its distance.
	 *
	 * <p>The outline traces edges a block SHARES with its neighbours, so a good part of it is a
	 * hair inside solid geometry rather than exactly coplanar with it. A depth-buffer bias measured
	 * in the smallest resolvable depth difference is the wrong tool for that: how much world space a
	 * unit buys changes across the depth range, so a value that fixes the near case does nothing far
	 * away.
	 *
	 * <p>A fraction of the distance is the right shape -- it says "prefer the line over anything
	 * within this much of it" and means the same thing at three blocks and at thirty.
	 *
	 * <p>Tunable with {@code -Dretroperf.outlinePull}, in thousandths.
	 */
	private static final float PULL = Integer.getInteger("retroperf.outlinePull", 6) / 1000.0F;

	/**
	 * Moves a vertex towards the camera WITHOUT moving it on screen.
	 *
	 * <p>Scaling an eye-space position by a scalar slides it along the ray from the eye through
	 * itself. Under a perspective projection that leaves the screen position untouched -- both
	 * {@code eye.x} and the {@code w} it is divided by scale together, so {@code ndc.x} is
	 * unchanged -- while {@code ndc.z}, which is not a linear function of {@code eye.z}, moves. So
	 * the outline wins the depth test slightly more often and does not shift by even a subpixel,
	 * which a plain z offset would.
	 *
	 * <p>An orthographic projection has no such ray -- every vertex is projected along the same
	 * axis -- so there the pull is a plain nudge along z, which is exactly as correct there.
	 */
	private static float[] pullTowardsCamera(float[] eye, float[] projection) {
		if (PULL <= 0.0F) {
			return eye;
		}
		if (Math.abs(projection[11]) > 1.0e-9F) {
			float s = 1.0F - PULL;
			return new float[] { eye[0] * s, eye[1] * s, eye[2] * s, eye[3] };
		}
		// Ortho: the camera looks down -Z, so towards it is +Z. Scaled by the depth range the
		// projection describes, so the nudge means the same thing whatever that range is.
		float range = Math.abs(projection[10]) > 1.0e-9F ? 2.0F / Math.abs(projection[10]) : 1.0F;
		return new float[] { eye[0], eye[1], eye[2] + PULL * range, eye[3] };
	}

	/** Integer boundary for an even width, pixel centre for an odd one. */
	private static float snap(float v, boolean evenWidth) {
		return evenWidth ? Math.round(v) : (float) Math.floor(v) + 0.5F;
	}

	/** One object-space vertex through the modelview, giving eye space. */
	private static float[] transform(ByteBuffer source, int base, int index, float[] m) {
		int o = base + index * STRIDE_BYTES;
		return apply(m, new float[] {
			source.getFloat(o), source.getFloat(o + 4), source.getFloat(o + 8), 1.0F });
	}

	/** Column-major matrix times a vec4. */
	private static float[] apply(float[] m, float[] v) {
		float w = v.length > 3 ? v[3] : 1.0F;
		return new float[] {
			m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12] * w,
			m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13] * w,
			m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14] * w,
			m[3] * v[0] + m[7] * v[1] + m[11] * v[2] + m[15] * w };
	}

	/**
	 * Eye-space units per LOGICAL pixel at a given clip {@code w}, in x and y.
	 *
	 * <p>Inverting the projection's own scale: a projection maps {@code eye.x} to
	 * {@code ndc.x = P[0] * eye.x / w}, and one pixel is {@code 2 / viewport} of NDC, so one pixel
	 * is {@code 2 * w / (viewport * P[0])} of eye space. Scaling by {@code w} is exactly what makes
	 * a distant line as thick as a near one; under an orthographic projection {@code w} is 1 and the
	 * term quietly disappears.
	 *
	 * @return {x, y} scale factors, or null for a degenerate projection
	 */
	private static float[] pixelToEye(float[] projection, float w, float logicalW, float logicalH) {
		float px = projection[0];
		float py = projection[5];
		if (Math.abs(px) < 1.0e-9F || Math.abs(py) < 1.0e-9F) {
			return null;
		}
		return new float[] { 2.0F * w / (logicalW * px), 2.0F * w / (logicalH * py) };
	}

	/** @param dx,dy offset from the endpoint in logical pixels; converted to eye space here */
	private void emit(float[] eye, float dx, float dy, float[] perPixel, int colour) {
		grow();
		// Screen y grows downward, eye y upward -- hence the sign on dy.
		out[pos] = Float.floatToRawIntBits(eye[0] + dx * perPixel[0]);
		out[pos + 1] = Float.floatToRawIntBits(eye[1] - dy * perPixel[1]);
		out[pos + 2] = Float.floatToRawIntBits(eye[2]);
		out[pos + 3] = 0;
		out[pos + 4] = 0;
		out[pos + 5] = colour;
		out[pos + 6] = 0;
		out[pos + 7] = 0;
		pos += STRIDE_INTS;
		vertexCount++;
	}

	private void grow() {
		if (pos + STRIDE_INTS <= out.length) {
			return;
		}
		int[] next = new int[out.length * 2];
		System.arraycopy(out, 0, next, 0, pos);
		out = next;
	}

	// --- self-check ----------------------------------------------------------------------------

	/** {@code java com.periut.retrodragon.render.LineExpander} */
	public static void main(String[] args) {
		int failures = 0;
		float[] identity = new float[16];
		identity[0] = identity[5] = identity[10] = identity[15] = 1.0F;
		// With both matrices identity, eye space, clip space and NDC coincide, so the emitted
		// coordinates can be read as NDC directly and the pixel arithmetic below is exact.
		float[] projection = identity;

		// A horizontal segment across the middle of a 100x100 target, already in NDC.
		ByteBuffer line = ByteBuffer.allocateDirect(2 * STRIDE_BYTES).order(ByteOrder.nativeOrder());
		putVertex(line, -0.5F, 0.0F, 0.0F, 0xFFFFFFFF);
		putVertex(line, 0.5F, 0.0F, 0.0F, 0xFFFFFFFF);
		line.position(0);

		LineExpander e = new LineExpander();
		failures += check(e.expand(line, 2, Primitives.GL_LINES, identity, projection, 100, 100, 2.0F, 1.0F),
			"a segment in front of the eye must expand");
		failures += check(e.vertexCount() == 4, "one segment is one quad, got " + e.vertexCount());

		// Two logical pixels tall, at 1x. Measured in NDC and converted back.
		float[] ys = new float[4];
		ByteBuffer q = e.data();
		for (int i = 0; i < 4; i++) {
			ys[i] = q.getFloat(i * STRIDE_BYTES + 4);
		}
		float heightPixels = Math.abs(ys[0] - ys[3]) * 0.5F * 100.0F;
		failures += check(Math.abs(heightPixels - 2.0F) < 0.001F,
			"a width-2 line must be 2 logical pixels tall, got " + heightPixels);

		// THE HiDPI PROPERTY: the same call at 2x must produce the SAME NDC quad. The line then
		// covers twice as many physical pixels, which is exactly "looks identical, drawn bigger".
		LineExpander hidpi = new LineExpander();
		line.position(0);
		hidpi.expand(line, 2, Primitives.GL_LINES, identity, projection, 200, 200, 2.0F, 2.0F);
		ByteBuffer hq = hidpi.data();
		for (int i = 0; i < 4 * STRIDE_INTS; i++) {
			float lo = q.getFloat(i * 4);
			float hi = hq.getFloat(i * 4);
			if (Float.isNaN(lo) ? !Float.isNaN(hi) : Math.abs(lo - hi) > 1.0e-6F) {
				System.out.println("FAIL: HiDPI changed the geometry at float " + i
					+ ": " + lo + " vs " + hi);
				failures++;
				break;
			}
		}

		// Edges land on integer logical boundaries, which is what makes it blocky rather than soft.
		float topPixels = (1.0F - ys[0]) * 0.5F * 100.0F;
		failures += check(Math.abs(topPixels - Math.round(topPixels)) < 0.001F,
			"the quad's edge must sit on a logical pixel boundary, got " + topPixels);

		// Behind the eye: w <= 0 cannot be projected and must not emit a wild quad.
		float[] flip = identity.clone();
		flip[15] = -1.0F;
		LineExpander behind = new LineExpander();
		failures += check(!behind.expand(line, 2, Primitives.GL_LINES, identity, flip,
			100, 100, 2.0F, 1.0F), "a segment behind the eye must be dropped, not projected");

		// A strip of N points is N-1 segments; a loop of N is N, because it closes.
		ByteBuffer three = ByteBuffer.allocateDirect(3 * STRIDE_BYTES).order(ByteOrder.nativeOrder());
		putVertex(three, -0.5F, -0.5F, 0.0F, 0xFFFFFFFF);
		putVertex(three, 0.5F, -0.5F, 0.0F, 0xFFFFFFFF);
		putVertex(three, 0.5F, 0.5F, 0.0F, 0xFFFFFFFF);
		three.position(0);
		LineExpander strip = new LineExpander();
		strip.expand(three, 3, Primitives.GL_LINE_STRIP, identity, projection, 100, 100, 2.0F, 1.0F);
		failures += check(strip.vertexCount() == 8, "a 3-point strip is 2 quads, got "
			+ strip.vertexCount() / 4);
		three.position(0);
		LineExpander loop = new LineExpander();
		loop.expand(three, 3, Primitives.GL_LINE_LOOP, identity, projection, 100, 100, 2.0F, 1.0F);
		failures += check(loop.vertexCount() == 12, "a 3-point loop is 3 quads, got "
			+ loop.vertexCount() / 4);

		// Under a PERSPECTIVE projection the same line must be the same number of pixels wide however
		// far away it is. This is the property eye-space emission exists for: widening in object
		// space would make a distant outline hairline-thin, and widening after the divide would throw
		// away the depth the rasteriser needs.
		float[] perspective = new float[16];
		perspective[0] = 1.0F;   // 90 degree fov, square aspect
		perspective[5] = 1.0F;
		perspective[10] = -1.002F;
		perspective[11] = -1.0F;
		perspective[14] = -0.2F;
		float near = measureWidth(perspective, -5.0F);
		float far = measureWidth(perspective, -50.0F);
		failures += check(Math.abs(near - far) < 0.01F,
			"width must not change with distance: " + near + " px near vs " + far + " px far");
		failures += check(Math.abs(near - 2.0F) < 0.01F,
			"a width-2 line should measure 2 px under perspective, got " + near);

		// The depth pull must buy depth and nothing else. A vertex off-centre and off-axis, so a
		// mistake shows: pulling along z instead of along the view ray would slide it on screen.
		float[] eye = { 3.0F, -2.0F, -20.0F, 1.0F };
		float[] pulled = pullTowardsCamera(eye, perspective);
		float[] beforeClip = apply(perspective, eye);
		float[] afterClip = apply(perspective, pulled);
		float beforeX = beforeClip[0] / beforeClip[3];
		float beforeY = beforeClip[1] / beforeClip[3];
		float afterX = afterClip[0] / afterClip[3];
		float afterY = afterClip[1] / afterClip[3];
		failures += check(Math.abs(beforeX - afterX) < 1.0e-5F
			&& Math.abs(beforeY - afterY) < 1.0e-5F,
			"the depth pull must not move the vertex on screen: " + beforeX + "," + beforeY
				+ " -> " + afterX + "," + afterY);
		failures += check(afterClip[2] / afterClip[3] < beforeClip[2] / beforeClip[3],
			"the depth pull must move the vertex TOWARDS the camera");

		// ...and it must be a fraction of the distance, so it means the same thing at any range.
		float[] farEye = { 3.0F, -2.0F, -200.0F, 1.0F };
		float nearShift = eye[2] - pullTowardsCamera(eye, perspective)[2];
		float farShift = farEye[2] - pullTowardsCamera(farEye, perspective)[2];
		failures += check(Math.abs(farShift / nearShift - 10.0F) < 0.01F,
			"the pull should scale with distance, got " + nearShift + " vs " + farShift);

		if (failures > 0) {
			System.out.println("LineExpander self-check FAILED (" + failures + ")");
			System.exit(1);
		}
		System.out.println("LineExpander self-check OK: width in logical pixels, identical at 1x and 2x");
	}

	/** Projected width in logical pixels of a width-2 segment sitting at eye depth {@code z}. */
	private static float measureWidth(float[] projection, float z) {
		float[] identity = new float[16];
		identity[0] = identity[5] = identity[10] = identity[15] = 1.0F;
		ByteBuffer seg = ByteBuffer.allocateDirect(2 * STRIDE_BYTES).order(ByteOrder.nativeOrder());
		putVertex(seg, -1.0F, 0.0F, z, 0xFFFFFFFF);
		putVertex(seg, 1.0F, 0.0F, z, 0xFFFFFFFF);
		seg.position(0);
		LineExpander e = new LineExpander();
		if (!e.expand(seg, 2, Primitives.GL_LINES, identity, projection, 100, 100, 2.0F, 1.0F)) {
			return Float.NaN;
		}
		ByteBuffer q = e.data();
		// Vertices 0 and 3 are the two sides at the first endpoint.
		float[] top = apply(projection, new float[] {
			q.getFloat(0), q.getFloat(4), q.getFloat(8), 1.0F });
		float[] bottom = apply(projection, new float[] {
			q.getFloat(3 * STRIDE_BYTES), q.getFloat(3 * STRIDE_BYTES + 4),
			q.getFloat(3 * STRIDE_BYTES + 8), 1.0F });
		float topY = top[1] / top[3];
		float bottomY = bottom[1] / bottom[3];
		return Math.abs(topY - bottomY) * 0.5F * 100.0F;
	}

	private static void putVertex(ByteBuffer b, float x, float y, float z, int colour) {
		int o = b.position();
		b.putFloat(o, x);
		b.putFloat(o + 4, y);
		b.putFloat(o + 8, z);
		b.putFloat(o + 12, 0.0F);
		b.putFloat(o + 16, 0.0F);
		b.putInt(o + 20, colour);
		b.putInt(o + 24, 0);
		b.putInt(o + 28, 0);
		b.position(o + STRIDE_BYTES);
	}

	private static int check(boolean ok, String message) {
		if (!ok) {
			System.out.println("FAIL: " + message);
			return 1;
		}
		return 0;
	}
}
