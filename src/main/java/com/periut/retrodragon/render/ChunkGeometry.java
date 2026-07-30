package com.periut.retrodragon.render;

/** Constants shared by the mesher and the ChunkBuilder mixin. */
public final class ChunkGeometry {
	/**
	 * Vanilla scales each section's geometry by this about its centre to hide seams between
	 * sections. It was a {@code glScalef} inside the display list; we bake it into the vertices.
	 */
	public static final float SEAM_SCALE = 1.000001F;

	public static final int[] NO_VERTICES = new int[0];

	private ChunkGeometry() {
	}
}
