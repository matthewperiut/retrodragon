package com.periut.retrodragon.render;

/** Duck interface mixed into vanilla's ChunkBuilder so a section can carry its VBOs and mesh state. */
public interface RetroSection {
	SectionMesh retroperf$mesh(int layer);

	void retroperf$freeMeshes();

	/** Render thread: upload geometry and finish vanilla's per-section bookkeeping. */
	void retroperf$applyMesh(MeshResult result);

	/** Render thread: build a job for this section, or null if it cannot be meshed right now. */
	MeshJob retroperf$snapshot();

	boolean retroperf$isMeshing();

	/** Face connectivity mask; ALL_CONNECTED until the section has been meshed. */
	long retroperf$visibility();
}
