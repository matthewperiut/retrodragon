package com.periut.retrodragon.api;

/**
 * A shader mod, from the engine's point of view.
 *
 * <p>The whole surface is optional past {@link #id()} and {@link #onStart}: an extension that only
 * wants to recolour the sky implements one routing method and nothing else, and one that wants a
 * deferred pipeline implements the rest as it needs them. Nothing here has to be understood in order
 * to use any other part of it.
 *
 * <h2>Several at once</h2>
 *
 * More than one extension may be installed. Programs are per-extension, so two mods registering a
 * program called "sky" get two distinct programs and neither can clobber the other. The per-draw
 * decisions -- {@link #routeProgram} and {@link #isVisible} -- are polled in {@link #priority()}
 * order, and the first extension to answer wins; an extension that returns the "no opinion" value
 * defers to the next. That is a deliberate first-wins rather than last-wins: last-wins means the
 * load order decides which mod is in charge, which is exactly the failure the old engine's
 * single-slot setters had.
 *
 * <p>The frame-wide decisions are not shareable and are not pretended to be. The world target, the
 * shadow pass and the composite chain belong to the highest-priority extension that asks for them;
 * the others are told so through {@link #onSuperseded}, so a mod can go dormant cleanly instead of
 * fighting for the frame.
 */
public interface ShaderExtension {
	/** Returned by {@link #routeProgram} to mean "use the engine's default program for this draw". */
	int NO_PROGRAM = -1;

	/** A stable id, used in logs and in pipeline labels. */
	String id();

	/**
	 * Higher runs first, and wins any frame-wide resource two extensions both want. Default 0.
	 */
	default int priority() {
		return 0;
	}

	/**
	 * Called once, on the render thread, as soon as the device exists and before any draw is routed.
	 *
	 * <p>Register programs and create textures here. Throwing disables this extension for the
	 * session and leaves the rest of the frame -- and every other extension -- untouched.
	 */
	void onStart();

	/**
	 * Called once per world frame while the game is still drawing, before the frame is replayed.
	 *
	 * <p>The point at which beta's own matrices, fog and world state are live, so this is where an
	 * extension computes its uniform block. It runs on the render thread inside world rendering, not
	 * at replay time -- by replay the game has moved on and its state is gone.
	 */
	default void onWorldFrame(float tickDelta) {
	}

	/**
	 * Which program a captured draw should use.
	 *
	 * @param phase   see {@link DrawPhase}
	 * @param texture the bound texture name, or a negative value when texturing is off
	 * @param lit     whether {@code GL_LIGHTING} was enabled -- beta's own separation of world
	 *                geometry that takes directional shading from geometry that does not
	 * @return a program id from {@link ShaderApi#registerProgram}, or {@link #NO_PROGRAM}
	 */
	default int routeProgram(int phase, int texture, boolean lit) {
		return NO_PROGRAM;
	}

	/**
	 * Whether a captured draw should be drawn at all.
	 *
	 * <p>The reason this exists is the vanilla blob shadow: an extension with a real shadow pass has
	 * to suppress it, and there is no state on the draw that distinguishes it from any other
	 * translucent quad. Returning false drops the batch before it reaches the GPU, so the vertices
	 * are never uploaded either.
	 */
	default boolean isVisible(int phase, int texture) {
		return true;
	}

	/**
	 * What the engine should render into a shadow map this frame, or null for no shadow pass.
	 *
	 * <p>Answered once per frame, before replay. The engine renders the frame's own opaque world
	 * batches from the requested viewpoint -- SAME frame, not the previous one, because the draws
	 * are all recorded before any of them execute. That removes the frame of staleness every
	 * immediate-mode shadow implementation on this game has had to live with, along with the
	 * retention machinery that made entities cast at all.
	 */
	default ShadowRequest shadowRequest() {
		return null;
	}

	/**
	 * Before any world geometry, with the frame's encoder open and no pass on it.
	 *
	 * <p>Where a SKYBOX goes. Beta has no sky geometry worth the name -- its "sky" is a flat plate a
	 * few metres above the camera that covers a narrow band near the horizon, and everything else you
	 * see is the colour the framebuffer was cleared to. A pack that paints beta's plate can only ever
	 * colour that band; the rest of the sky is not geometry and cannot be shaded.
	 *
	 * <p>So a pack that wants a real sky draws its own, full-screen, here -- and tells the engine it
	 * owns the colour clear through {@link ShaderApi#claimWorldColorClear}, since a full-screen pass
	 * writes every pixel and a clear before it would be one wasted write of the whole framebuffer.
	 */
	default void onWorldBegin(ShaderFrame frame) {
	}

	/** After the opaque world, before translucent terrain: the point a deferred pass belongs. */
	default void onDeferred(ShaderFrame frame) {
	}

	/**
	 * After the world and before the GUI.
	 *
	 * <p>When the extension asked for a world target, its composite chain MUST end by writing to
	 * {@link ShaderFrame#outputView()} -- the swapchain holds nothing until it does, and the GUI is
	 * about to be drawn on top of whatever it finds.
	 */
	default void onComposite(ShaderFrame frame) {
	}

	/** After the GUI, with the frame about to be presented. */
	default void onFrameEnd(ShaderFrame frame) {
	}

	/**
	 * Another extension outranked this one for a frame-wide resource.
	 *
	 * @param what a short description -- "world target", "shadow pass" -- for the extension to log
	 */
	default void onSuperseded(String what, ShaderExtension winner) {
	}

	/** The renderer is shutting down. GPU resources the extension created must be released here. */
	default void onStop() {
	}

	/**
	 * A shadow map the engine should render for the extension.
	 *
	 * <p>The engine is told WHAT to draw and HOW BIG; the light's matrices are not passed here at
	 * all. They belong in the extension's own uniform block, where the caster program reads them --
	 * along with the inverse camera view it needs to get there, and where the receiving programs
	 * read the same matrices back to look the result up. Splitting them across two mechanisms is how
	 * a shadow map ends up rendered with one matrix and sampled with another.
	 *
	 * <p>TWO programs, because the engine has two vertex layouts and a pipeline bakes one in. Beta's
	 * 32-byte Tessellator vertex carries a normal; the engine's packed terrain stream does not, so a
	 * program written for either cannot read the other. Every batch is routed to whichever matches
	 * the layout it was captured in.
	 *
	 * @param resolution     square edge in texels
	 * @param program        the caster for batches on beta's own vertex layout
	 * @param terrainProgram the caster for batches on the engine's terrain layout
	 */
	record ShadowRequest(int resolution, int program, int terrainProgram) {
	}
}
