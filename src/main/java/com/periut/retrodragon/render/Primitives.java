package com.periut.retrodragon.render;

import com.periut.retrodragon.shim.PipelineKey;

/**
 * Translates GL primitive modes into what WebGPU can actually draw.
 *
 * <p>Three of the modes beta uses do not exist in any modern graphics API: {@code GL_QUADS},
 * {@code GL_TRIANGLE_FAN} and {@code GL_POLYGON}. They are drawn as indexed triangle lists, which
 * costs nothing at runtime -- the index pattern is the same for every batch, so one static buffer is
 * bound once and reused with a per-draw base vertex.
 *
 * <p>{@code GL_QUADS} matters most: beta's Tessellator defaults to it and terrain, entities,
 * particles, items and every GUI element go through it.
 */
public final class Primitives {
	public static final int GL_POINTS = 0;
	public static final int GL_LINES = 1;
	public static final int GL_LINE_LOOP = 2;
	public static final int GL_LINE_STRIP = 3;
	public static final int GL_TRIANGLES = 4;
	public static final int GL_TRIANGLE_STRIP = 5;
	public static final int GL_TRIANGLE_FAN = 6;
	public static final int GL_QUADS = 7;
	public static final int GL_QUAD_STRIP = 8;
	public static final int GL_POLYGON = 9;

	/** How a batch's vertices become draw calls. */
	public enum Indexing {
		/** Draw the vertices as they are. */
		DIRECT,
		/** {@code 0,1,2, 0,2,3} per four vertices -- quads and quad strips. */
		QUADS,
		/** {@code 0,i+1,i+2} -- triangle fans and polygons. */
		FAN
	}

	private Primitives() {
	}

	/** The {@code PipelineKey.TOPOLOGY_*} a GL mode reduces to. */
	public static int topology(int glMode) {
		return switch (glMode) {
			case GL_POINTS -> PipelineKey.TOPOLOGY_POINTS;
			case GL_LINES -> PipelineKey.TOPOLOGY_LINES;
			// A line LOOP is a strip plus a closing segment. WebGPU has no loop; the closing segment
			// is lost, which for beta means the block-selection outline would open at one corner.
			// Drawing it as a strip is still closer than dropping the batch.
			case GL_LINE_STRIP, GL_LINE_LOOP -> PipelineKey.TOPOLOGY_LINE_STRIP;
			case GL_TRIANGLE_STRIP, GL_QUAD_STRIP -> PipelineKey.TOPOLOGY_TRIANGLE_STRIP;
			default -> PipelineKey.TOPOLOGY_TRIANGLES;
		};
	}

	public static Indexing indexing(int glMode) {
		return switch (glMode) {
			case GL_QUADS -> Indexing.QUADS;
			case GL_TRIANGLE_FAN, GL_POLYGON -> Indexing.FAN;
			default -> Indexing.DIRECT;
		};
	}

	/**
	 * Indices needed to draw {@code vertices} under {@code glMode}.
	 *
	 * <p>A quad batch with a vertex count that is not a multiple of four is a truncated batch -- beta
	 * does not produce one, but a mod that miscounts would otherwise index past the buffer, so the
	 * remainder is dropped rather than read.
	 */
	public static int indexCount(int glMode, int vertices) {
		return switch (indexing(glMode)) {
			case QUADS -> vertices / QuadIndices.VERTICES_PER_QUAD * QuadIndices.INDICES_PER_QUAD;
			case FAN -> Math.max(0, vertices - 2) * 3;
			case DIRECT -> vertices;
		};
	}

	/**
	 * A {@code GL_QUAD_STRIP} draws quads from consecutive PAIRS of vertices, which is a triangle
	 * strip with the same vertex order -- no conversion needed, unlike {@code GL_QUADS}.
	 */
	public static boolean isStrip(int glMode) {
		return glMode == GL_TRIANGLE_STRIP || glMode == GL_QUAD_STRIP;
	}
}
