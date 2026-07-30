package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;

/**
 * Whether a quad costs four vertices or six.
 *
 * <h2>The waste</h2>
 *
 * Beta's Tessellator sets {@code TRIANGLE_MODE = true} in its static initialiser and nothing ever
 * clears it, so {@code vertex()} expands every quad into six vertices as it records -- v0,v1,v2 then
 * copies of v0 and v2, then v3 -- and {@code draw()} submits a triangle list. That is not a rendering
 * choice this game makes for a reason; it is what a 2011 engine did to avoid {@code GL_QUADS} on
 * drivers that were slow at it.
 *
 * <p>Beta draws EVERYTHING through that path: terrain, entities, particles, items, the block-break
 * overlay, text, every GUI element. So half again as many vertices as the geometry contains are
 * built on the CPU, stored, uploaded, fetched and transformed, for every quad in the game.
 *
 * <h2>Why it can simply be turned off here</h2>
 *
 * The WebGPU backend already draws {@code GL_QUADS} as an indexed triangle list -- {@code 0,1,2,
 * 0,2,3} out of one static buffer bound once, with a per-draw base vertex ({@link QuadIndices},
 * {@link ImmediateRenderer}). The expansion the Tessellator performs on the CPU is exactly the
 * expansion the index buffer performs for free. Doing both means paying for the geometry twice and
 * throwing away the index buffer's reuse.
 *
 * <p>With the split off, a quad is four vertices in memory and six indices at draw time, and the
 * vertex shader runs four times instead of six. Measured on the offscreen bench (800 sections, a
 * terrain-shaped frame, identical pixels either way): <b>1.4x to 1.55x faster</b> across every
 * workload size tried, and a third less resident geometry. In game at the standard bench vantage:
 * <b>2.55 ms to 1.77 ms median, 1.44x</b>, which is the offscreen figure holding up on a real frame.
 *
 * <h2>WebGPU only</h2>
 *
 * The GL backend keeps the split. Not because {@code GL_QUADS} is unavailable -- it is core in the
 * 2.1 compatibility profile this project targets -- but because GL is the fallback path whose whole
 * value is being the thing that already works, and this buys it nothing that would justify changing
 * how its geometry is shaped.
 *
 * <p>The decision is made once, when {@link RenderBackend} settles, and read from a plain static
 * field. That matters: the redirect is on beta's per-vertex path, so this is consulted a few million
 * times a second and must not be a volatile read or a method call that can fail to inline.
 */
public final class QuadVertices {
	private static boolean indexed;

	private QuadVertices() {
	}

	/** Called once from {@link RenderBackend}, before any geometry is built. */
	public static void select(boolean webgpu) {
		indexed = webgpu && Config.QUAD_VERTICES;
	}

	/** Whether quads are stored as four vertices and drawn through the shared index buffer. */
	public static boolean indexed() {
		return indexed;
	}

	/**
	 * What beta's {@code Tessellator.vertex} should see in place of {@code TRIANGLE_MODE}.
	 *
	 * <p>Written as a function of the real field rather than a bare {@code !indexed} so that a build
	 * where beta somehow does not set it keeps beta's behaviour: this can only ever turn the split
	 * OFF, never on.
	 */
	public static boolean splitting(boolean triangleMode) {
		return triangleMode && !indexed;
	}

	/** Vertices a quad occupies in a buffer built under the current setting. */
	public static int perQuad() {
		return indexed ? 4 : 6;
	}

	/** The primitive mode a buffer built under the current setting must be submitted as. */
	public static int glMode() {
		return indexed ? Primitives.GL_QUADS : Primitives.GL_TRIANGLES;
	}
}
