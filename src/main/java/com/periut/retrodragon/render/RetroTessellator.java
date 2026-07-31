package com.periut.retrodragon.render;

/**
 * Duck interface on beta's Tessellator for the one thing {@link Capture} cannot intercept: geometry
 * written straight into the Tessellator's {@code int[]} instead of through
 * {@code vertex}/{@code texture}/{@code color}/{@code normal}.
 *
 * <p>StationAPI's Arsenic renderer does exactly that -- its {@code Tessellator.quad(BakedQuad, ...)}
 * builds a quad's four vertices in beta's packed layout and {@code System.arraycopy}s them into the
 * batch buffer. Nothing the capture hooks is ever called, so a baked model contributes no vertices
 * to a section and its quads instead pile up in the shared buffer until some unrelated {@code draw}
 * flushes them somewhere they do not belong.
 */
public interface RetroTessellator {
	/**
	 * Moves whatever the Tessellator is holding into {@code sink} and leaves the batch as
	 * {@code reset()} would, returning true if anything moved.
	 *
	 * <p>Everything in the buffer during a capture came from a direct writer: the capture cancels
	 * {@code vertex()} at HEAD, so an API-emitted vertex never reaches it.
	 */
	boolean retroperf$drainInto(VertexSink sink);

	/**
	 * Rewrites the six vertices a direct writer just appended as the four corners they came from,
	 * for the indexed layout. Returns false if there is no whole quad to rewrite.
	 *
	 * <p>The layout is beta's own split -- v0,v1,v2 then copies of v0 and v2, then v3 -- so the real
	 * fourth corner is the last of the six and the two copies are the two before it.
	 */
	boolean retroperf$collapseLastQuad();
}
