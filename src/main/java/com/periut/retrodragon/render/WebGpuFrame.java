package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GlShim;
import com.periut.retrodragon.shim.ShimTracker;
import com.periut.retrodragon.window.sdl.Sdl3Window;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * The WebGPU frame, as the game drives it.
 *
 * <p>Beta has no concept of a frame object: it clears, draws, and eventually calls
 * {@code Display.update()}. So the boundaries are inferred -- a frame accumulates from the first
 * captured draw or clear and is submitted at the buffer swap. Everything in between is recorded
 * into a {@link DrawList} rather than executed, because WebGPU commands cannot be issued while the
 * game still holds the state they depend on.
 *
 * <p>Static because the callers are mixins on GL entry points, which have no place to hold an
 * instance and are called from every corner of the game.
 */
public final class WebGpuFrame {
	private static final DrawList LIST = new DrawList();

	private static WebGpuRenderer renderer;

	/**
	 * {@code glReadPixels}, which under WebGPU can only mean "the last frame that was presented".
	 *
	 * <p>Silently true-with-nothing-written is wrong here: beta would save whatever the buffer
	 * already held, which is a black PNG and looks like a renderer bug rather than a missing entry
	 * point. Returning false lets the caller leave the buffer alone.
	 */
	public static boolean readPixels(int x, int y, int width, int height, int format,
			java.nio.ByteBuffer pixels) {
		return renderer != null && renderer.readPixels(x, y, width, height, format, pixels);
	}

	private static FixedFunctionPipelines.Shared sharedPipelines;
	private static FixedFunctionPipelines pipelines;
	/** Pipelines for the shader extension's own colour target; null when nobody asked for one. */
	private static FixedFunctionPipelines worldPipelines;
	/** Depth-only pipelines for the shadow pass; null until an extension requests one. */
	private static FixedFunctionPipelines shadowPipelines;
	private static ImmediateRenderer immediate;
	private static TextureStore textures;
	private static ShaderTargets shaderTargets;

	// --- render scale -----------------------------------------------------------------------------
	//
	// The world's own colour and depth when it is not being rendered at the swapchain's size, plus the
	// pass that resamples it back. Allocated on first use and reallocated whenever the scale or the
	// window changes, which is why they are not part of the renderer's resize: the scale can change
	// without the window doing so.

	private static com.periut.retrodragon.gpu.RenderTarget scaleTarget;
	private static com.periut.retrodragon.gpu.DepthBuffer scaleDepth;
	private static ScaleBlit scaleBlit;
	/** Its own arena, because these are freed and rebuilt on a scale change and the renderer's is not. */
	private static Arena scaleArena;
	private static int scaleWidth;
	private static int scaleHeight;
	private static boolean scaleBroken;

	/**
	 * Makes the scaled world target match the current scale and window. Fails soft.
	 *
	 * @return true when the caller may use {@link #scaleTarget}; false disables render scale for the
	 *     session and the frame falls through to the ordinary full-resolution path, which is a
	 *     resolution the player did not ask for but is emphatically better than a black screen
	 */
	private static void syncScaleTargets() {
		if (scaleBroken || renderer == null) {
			return;
		}
		if (!RenderScale.active()) {
			// Scale turned off, or the window caught up with it. Release rather than keep a
			// full-resolution target alive for a path that will not use it again.
			if (scaleTarget != null) {
				GpuBackend.context().drain(2000);
				freeScaleTargets();
			}
			return;
		}
		ensureScaleTargets();
	}

	/**
	 * Whether the scaled target exists AND matches what this frame is about to draw.
	 *
	 * <p>Read-only, and called from inside the frame. It allocates nothing: {@link #syncScaleTargets}
	 * has already run before the drawable was acquired, so by here the answer is either yes or this
	 * frame renders at full resolution.
	 */
	private static boolean scaleTargetsReady() {
		return !scaleBroken && scaleTarget != null && scaleBlit != null
			&& scaleWidth == RenderScale.worldWidth() && scaleHeight == RenderScale.worldHeight()
			&& scaleBlit.matches(renderer.surfaceFormat());
	}

	private static boolean ensureScaleTargets() {
		if (scaleBroken) {
			return false;
		}
		int width = RenderScale.worldWidth();
		int height = RenderScale.worldHeight();
		int format = renderer.surfaceFormat();
		if (scaleTarget != null && width == scaleWidth && height == scaleHeight
				&& scaleBlit != null && scaleBlit.matches(format)) {
			return true;
		}
		try {
			// The GPU may still be reading last frame's target. Everything below releases textures the
			// in-flight frames reference, and releasing those underneath live work is the hazard the
			// whole teardown ordering in this renderer exists to avoid.
			GpuBackend.context().drain(2000);
			freeScaleTargets();
			scaleArena = Arena.ofShared();
			scaleWidth = width;
			scaleHeight = height;
			// The swapchain's format, so the resolve pipeline's single colour target matches and no
			// conversion is needed on the way out.
			scaleTarget = com.periut.retrodragon.gpu.RenderTarget.create(
				GpuBackend.context(), scaleArena, width, height, format);
			scaleDepth = com.periut.retrodragon.gpu.DepthBuffer.create(
				GpuBackend.context(), scaleArena, width, height);
			scaleBlit = ScaleBlit.create(GpuBackend.context(), scaleArena, format);
			RetroDragon.LOGGER.info("render scale: world at {}x{} ({}x window), filter {}",
				width, height, RenderScale.scale(), RenderScale.effectiveFilter());
			return true;
		} catch (RuntimeException e) {
			RetroDragon.LOGGER.error("render scale could not be set up; rendering at window size", e);
			freeScaleTargets();
			scaleBroken = true;
			return false;
		}
	}

	private static void freeScaleTargets() {
		if (scaleBlit != null) {
			scaleBlit.close();
			scaleBlit = null;
		}
		if (scaleDepth != null) {
			scaleDepth.close();
			scaleDepth = null;
		}
		if (scaleTarget != null) {
			scaleTarget.close();
			scaleTarget = null;
		}
		if (scaleArena != null) {
			scaleArena.close();
			scaleArena = null;
		}
		scaleWidth = 0;
		scaleHeight = 0;
	}
	private static ShadowMap shadowMap;
	private static com.periut.retrodragon.api.ShaderResources shaderResources;
	private static volatile boolean initialized;
	private static volatile boolean failed;

	private static long frames;
	private static int lastDraws;

	/** Terrain handed to the frame this frame -- the number that says whether it was ever captured. */
	private static int terrainBatches;
	private static int terrainVertices;

	/**
	 * {@code -Dretroperf.shotFrame=N} writes frame N to a PNG and keeps going.
	 *
	 * <p>The only trustworthy way to see what this renderer draws: an external screenshot tool
	 * without screen-recording permission returns the desktop with every window missing, which is
	 * indistinguishable from a renderer that produces nothing.
	 */
	private static final long SHOT_FRAME = Long.getLong("retroperf.shotFrame", -1L);


	private WebGpuFrame() {
	}

	/**
	 * Stands the renderer up over the already-created GL-free window.
	 *
	 * <p>Late rather than at mod init on purpose: the surface needs a window, and the window is
	 * created by {@code Display.create()} well after the entrypoint runs.
	 */
	public static synchronized boolean init() {
		if (initialized || failed) {
			return initialized;
		}
		if (!RenderBackend.isWebGpu() || !GpuBackend.available() || !Sdl3Window.isCreated()) {
			return false;
		}
		try {
			WebGPUContext ctx = GpuBackend.context();
			renderer = WebGpuRenderer.create(ctx, Sdl3Window.width(), Sdl3Window.height());
			sharedPipelines = FixedFunctionPipelines.Shared.create(ctx);
			pipelines = FixedFunctionPipelines.create(ctx, sharedPipelines, renderer.surfaceFormat(),
				renderer.depthFormat());
			immediate = new ImmediateRenderer(ctx);
			textures = new TextureStore(ctx);
			initialized = true;
			RetroDragon.LOGGER.info("WebGPU renderer up: {}x{}, surface format {}",
				renderer.width(), renderer.height(), renderer.surfaceFormat());
			startExtensions(ctx);
		} catch (Throwable t) {
			// A failure here is not recoverable -- the window has no GL context to fall back to -- but
			// crashing inside a GL entry point produces a stack trace that blames the game. Say what
			// actually happened, once.
			failed = true;
			RetroDragon.LOGGER.error("WebGPU renderer failed to start; the window has no GL context to"
				+ " fall back to. Relaunch with -Dretroperf.backend=gl.", t);
		}
		return initialized;
	}

	/**
	 * How large the shader-extension uniform block is, in bytes.
	 *
	 * <p>Generous on purpose. A pack's per-frame block is a dozen matrices and a few dozen scalars --
	 * a kilobyte in the shape everyone who has written one settles on -- and it is allocated ONCE,
	 * not per draw, so oversizing it costs a kilobyte of VRAM rather than anything per frame.
	 * Sizing it to the pack instead would mean the block's layout was part of the engine's ABI.
	 */
	private static final int SHADER_UNIFORM_BYTES =
		Integer.getInteger("retroperf.shaderUniformBytes", 4096);

	/**
	 * Brings up the shader extensions, and with them anything they ask the engine to allocate.
	 *
	 * <p>Here rather than at mod init because everything an extension registers needs a device, and
	 * the device does not exist until the window does. A failure disables the extensions and leaves
	 * the plain renderer running -- a shader mod that cannot start must not take the game with it.
	 */
	private static boolean extensionsStarted;

	private static void startExtensions(WebGPUContext ctx) {
		if (extensionsStarted || !com.periut.retrodragon.api.ShaderApi.hasExtensions()) {
			return;
		}
		extensionsStarted = true;
		try {
			shaderResources = new com.periut.retrodragon.api.ShaderResources(ctx, SHADER_UNIFORM_BYTES);
			com.periut.retrodragon.api.ShaderApi.startAll(shaderResources,
				renderer.surfaceFormat());
			if (com.periut.retrodragon.api.ShaderApi.worldTargetOwner() != null) {
				shaderTargets = new ShaderTargets(ctx,
					com.periut.retrodragon.api.ShaderApi.worldTargetFormat(),
					com.periut.retrodragon.api.ShaderApi.worldTargetAux());
				shaderTargets.resize(renderer.width(), renderer.height());
				shaderResources.setWorldTarget(shaderTargets.view());
				worldPipelines = FixedFunctionPipelines.create(ctx, sharedPipelines, "world",
					shaderTargets.format(), shaderTargets.auxFormats(), renderer.depthFormat(),
					false, TerrainVertex.compact());
				RetroDragon.LOGGER.info("world redirected to a shader target owned by '{}'"
						+ " ({} aux attachment(s))",
					com.periut.retrodragon.api.ShaderApi.worldTargetOwner().id(),
					shaderTargets.auxCount());
			}
		} catch (Throwable t) {
			RetroDragon.LOGGER.error("shader extensions failed to start; rendering without them", t);
			releaseExtensionResources();
		}
	}

	/**
	 * Whether an extension installed itself AFTER the renderer came up, and needs starting.
	 *
	 * <p>Which is the normal case, not an edge one. The renderer is brought up lazily by the first
	 * intercepted GL call, and beta's first GL call is a texture upload during startup -- which
	 * happens before mod initialisers have finished running. So a shader mod that installs itself
	 * from its own initialiser, which is the only place it can, reliably arrives second.
	 *
	 * <p>Checked once a frame rather than pushed from install(), because install() is called from a
	 * mod's initialiser thread and everything it would have to do -- create a bind group, allocate
	 * a colour target, compile nothing yet -- belongs on the render thread.
	 */
	private static boolean extensionsPending() {
		return !extensionsStarted && com.periut.retrodragon.api.ShaderApi.hasExtensions();
	}

	/**
	 * Tells the extensions the world frame is starting, while beta's own state is still live.
	 *
	 * <p>Called from the phase mixin at {@code renderWorld} HEAD -- the camera transform is loaded,
	 * the fog is set, and the tick delta is in hand. An extension computes its per-frame uniform
	 * block here; there is nowhere later it could, because the frame is not submitted until the
	 * buffer swap, by which point none of this state exists.
	 */
	/**
	 * Whether this world frame's state has been handed to the extensions yet.
	 *
	 * <p>Reset at the top of world rendering and set by the first call that arrives with beta's
	 * camera actually loaded; see {@link #beginWorldFrame}.
	 */
	private static boolean worldFrameNotified;

	/**
	 * Called at the top of world rendering, BEFORE beta has set up its camera.
	 *
	 * <p>Only arms the notification. The state an extension needs -- the camera modelview, the
	 * projection, the fog -- does not exist yet at this point: beta loads the projection and applies
	 * the view bobbing and camera rotation further down the same method. Reading them here gets the
	 * GUI's orthographic matrix and an identity modelview, which is not obviously wrong in a log and
	 * is thoroughly wrong on screen -- a skybox built from that projection paints one flat colour,
	 * and every shadow lookup lands somewhere else entirely.
	 */
	public static void beginWorldFrame() {
		worldFrameNotified = false;
	}

	/**
	 * Re-arms the notification at the frame boundary.
	 *
	 * <p>Belt and braces alongside {@link #beginWorldFrame}, and not redundant: that one runs from a
	 * mixin on the game's world-render entry point, and if it does not fire -- a mapping change, an
	 * injection that silently does not apply, another mod cancelling the method -- the flag latches
	 * true after the first frame and every extension's per-frame state freezes at frame one. Nothing
	 * about that looks like a missing callback: the sky keeps rendering, the shadows keep drawing,
	 * they are just all computed from the first frame's camera, which reads as "shadows follow the
	 * view" and "lights never update".
	 *
	 * <p>This runs from the present path, which by definition happens once a frame or the game is not
	 * running at all.
	 */
	private static void rearmWorldFrame() {
		worldFrameNotified = false;
	}

	public static void notifyWorldFrame(float tickDelta) {
		if (worldFrameNotified || !active()) {
			return;
		}
		worldFrameNotified = true;
		for (com.periut.retrodragon.api.ShaderExtension extension
				: com.periut.retrodragon.api.ShaderApi.extensions()) {
			try {
				extension.onWorldFrame(tickDelta);
			} catch (Throwable t) {
				RetroDragon.LOGGER.error("shader extension '{}' threw preparing a world frame;"
					+ " removing it", extension.id(), t);
				com.periut.retrodragon.api.ShaderApi.remove(extension);
			}
		}
	}

	private static void releaseExtensionResources() {
		if (worldPipelines != null) {
			worldPipelines.close();
			worldPipelines = null;
		}
		if (shadowPipelines != null) {
			shadowPipelines.close();
			shadowPipelines = null;
		}
		if (shaderTargets != null) {
			shaderTargets.close();
			shaderTargets = null;
		}
		if (shadowMap != null) {
			shadowMap.close();
			shadowMap = null;
		}
		if (shaderResources != null) {
			shaderResources.close();
			shaderResources = null;
		}
	}

	/**
	 * Whether GL calls should be routed here -- and, the first time it is asked, the thing that makes
	 * that true.
	 *
	 * <p>Initialisation is lazy because of an ordering problem with no good fixed point: the surface
	 * needs a window, the window is created inside {@code Display.create()}, and the very first GL
	 * calls after that are beta uploading textures. Anything that has to run "after the window but
	 * before the first GL call" is more fragile than simply standing the renderer up on first use.
	 *
	 * <p>The steady-state cost is two static reads, which is what makes it acceptable on a path
	 * every intercepted GL call goes through.
	 */
	public static boolean active() {
		if (initialized) {
			return true;
		}
		if (failed || !RenderBackend.isWebGpu()) {
			return false;
		}
		return init();
	}

	/**
	 * Both accessors force initialisation first.
	 *
	 * <p>The first GL call the game makes under WebGPU is {@code glGenTextures}, not a clear or a
	 * draw -- beta uploads its textures during startup. So anything that reaches for the renderer has
	 * to be able to bring it up, not just the frame loop.
	 */
	public static TextureStore textures() {
		active();
		return textures;
	}

	public static ImmediateRenderer immediate() {
		active();
		return immediate;
	}

	/**
	 * Records a {@code glClear}.
	 *
	 * @param mask beta's GL bitmask; only colour (0x4000) and depth (0x100) are meaningful, since
	 *             nothing in the game touches the stencil buffer
	 */
	public static void clear(int mask, float r, float g, float b, float a) {
		if (!active()) {
			return;
		}
		LIST.clear((mask & 0x4000) != 0, (mask & 0x100) != 0, r, g, b, a);
	}

	/**
	 * Records one Tessellator batch.
	 *
	 * @param source      beta's packed vertex bytes, not consumed
	 * @param vertexCount vertices in {@code source}
	 * @param glMode      the primitive mode the batch was started with
	 */
	public static void capture(ByteBuffer source, int vertexCount, int glMode,
			boolean hasColor, boolean hasNormals) {
		capture(source, vertexCount, glMode, hasColor, hasNormals, true);
	}

	public static void capture(ByteBuffer source, int vertexCount, int glMode,
			boolean hasColor, boolean hasNormals, boolean hasTexture) {
		if (!active() || vertexCount <= 0) {
			return;
		}
		if (com.periut.retrodragon.shim.DisplayLists.isRecording()) {
			// GL_COMPILE: the geometry belongs to the list being built, and nothing draws until it
			// is called. beta never uses GL_COMPILE_AND_EXECUTE.
			com.periut.retrodragon.shim.DisplayLists.record(source, vertexCount, glMode,
				hasColor, hasNormals, hasTexture);
			return;
		}
		GlShim gl = ShimTracker.shim();
		if (frames + 1 == DUMP_FRAME) {
			dump(source, vertexCount, glMode, hasColor, hasNormals);
		}
		int phase = com.periut.retrodragon.api.ShaderApi.phase();
		int texture = gl.boundTexture();
		// Dropped BEFORE the vertices are copied, not filtered at draw time: a suppressed blob shadow
		// should cost nothing, and the copy is the expensive half of a capture.
		if (!com.periut.retrodragon.api.ShaderApi.isVisible(routeTexture(gl, texture))) {
			return;
		}
		if (captureWideLine(gl, source, vertexCount, glMode, hasColor, hasNormals, hasTexture,
				phase)) {
			return;
		}
		gl.setTopology(Primitives.topology(glMode));
		LIST.add(source, vertexCount, glMode,
			routedKey(gl, texture, com.periut.retrodragon.api.ProgramSpec.VertexLayout.FIXED_FUNCTION),
			texture, gl.state().writeUniforms(hasColor, hasNormals, hasTexture), phase);
	}

	/**
	 * The batch's pipeline key with a shader extension's chosen program substituted, if any claimed
	 * it.
	 *
	 * <p>Asked once per captured batch rather than once per draw call, and skipped entirely when no
	 * extension is installed -- {@code hasExtensions} is a single static read, which is what keeps
	 * this off the cost of a plain frame.
	 */
	/**
	 * The texture an extension is told about: the bound name, or -1 when texturing is OFF.
	 *
	 * <p>Beta leaves a texture bound while drawing untextured geometry, so the bound name alone
	 * cannot distinguish the sky plate from the block atlas -- and an extension that suppresses
	 * beta's sky in favour of its own would fail to, silently, with beta's plate painted over the
	 * top of it.
	 */
	private static int routeTexture(GlShim gl, int texture) {
		return gl.state().textured() ? texture : -1;
	}

	private static long routedKey(GlShim gl, int texture,
			com.periut.retrodragon.api.ProgramSpec.VertexLayout layout) {
		return routedKey(gl, texture, layout, gl.pipelineKey());
	}

	/** As above, over a key the caller already built -- the outline's carries a depth bias. */
	private static long routedKey(GlShim gl, int texture,
			com.periut.retrodragon.api.ProgramSpec.VertexLayout layout, long baseKey) {
		if (!com.periut.retrodragon.api.ShaderApi.hasExtensions()) {
			return baseKey;
		}
		int program = com.periut.retrodragon.api.ShaderApi.routeProgram(routeTexture(gl, texture),
			gl.state().lit());
		long key = baseKey;
		if (program == com.periut.retrodragon.api.ShaderExtension.NO_PROGRAM) {
			return key;
		}
		// A program may only be substituted when it reads the SAME vertex stream the batch was
		// captured in. A pipeline bakes its stride in, so pointing a program built for beta's
		// 32-byte Tessellator vertex at the engine's packed terrain buffer does not fail -- it reads
		// every vertex from the wrong offset and the section erupts across the screen in enormous
		// stretched triangles. That costs the frame, and enough of them costs the compositor.
		//
		// Checked here rather than trusted to the extension because the extension does not
		// necessarily know: routing is by draw PHASE, and a phase can be wrong for reasons that have
		// nothing to do with the pack -- two mixins at one injection point with no defined order was
		// how this one happened.
		com.periut.retrodragon.api.ProgramSpec spec =
			com.periut.retrodragon.api.ShaderApi.program(program);
		if (spec != null && spec.layout() != layout) {
			if (mismatchWarnings.add(program)) {
				RetroDragon.LOGGER.warn("program '{}' reads the {} vertex stream but was routed to a"
					+ " {} draw; keeping the engine's program for it", spec.name(), spec.layout(),
					layout);
			}
			return key;
		}
		return com.periut.retrodragon.shim.PipelineKey.withProgram(key, program);
	}

	/** One warning per offending program, not one per draw. */
	private static final java.util.Set<Integer> mismatchWarnings = new java.util.HashSet<>();

	/** Reused across frames; the outline is the only caller and it is a handful of vertices. */
	private static final LineExpander LINE_EXPANDER = new LineExpander();

	/**
	 * Draws a wide line batch as quads, since WebGPU lines are always one pixel.
	 *
	 * <p>The block selection outline is the reason this exists: vanilla asks for
	 * {@code glLineWidth(2)}, which no modern API can honour, so the width has to become geometry.
	 * See {@link LineExpander} for why that has to happen after the projection divide, and how the
	 * result is kept identical at 1x and 2x.
	 *
	 * @return true if the batch was handled here and the caller should stop
	 */
	private static boolean captureWideLine(GlShim gl, ByteBuffer source, int vertexCount,
			int glMode, boolean hasColor, boolean hasNormals, boolean hasTexture, int phase) {

		boolean isLine = glMode == Primitives.GL_LINES || glMode == Primitives.GL_LINE_STRIP
			|| glMode == Primitives.GL_LINE_LOOP;
		if (!isLine || gl.lineWidth() <= 1.0F || renderer == null) {
			return false;
		}
		if (!LINE_EXPANDER.expand(source, vertexCount, glMode, gl.state().modelView(),
				gl.state().projection(), renderer.width(), renderer.height(), gl.lineWidth(),
				Sdl3Window.pixelsPerPoint())) {
			// Entirely behind the eye, or degenerate. Drawing the unexpanded batch instead would put
			// a one-pixel outline on screen, which is worse than the frame it belongs to being clean.
			return true;
		}
		// Quads now rather than lines, and the vertices are in eye space -- so the modelview goes
		// identity while the projection stays, leaving the GPU to do the divide.
		gl.setTopology(Primitives.topology(Primitives.GL_QUADS));
		int outlineTexture = gl.boundTexture();
		// ROUTED and PHASE-STAMPED, exactly as an ordinary capture is.
		//
		// Both were missing, and the phase was the expensive one: this path used the add() overload
		// that defaults the phase to NONE, which isWorld() reports as false. So the frame's
		// world/GUI split landed ON the block outline, and everything beta drew after it -- the
		// CLOUDS above all -- was replayed in the GUI range: straight to the swapchain, with the
		// engine's own program, no routing and no tonemap. The symptom was the clouds changing
		// colour whenever the crosshair found a block, which points nowhere near the line expander.
		//
		// Routing has to come with it. Once the outline is stamped as world geometry it is drawn
		// into the extension's LINEAR target, and the engine's program writes display-space colour
		// there, which the composite then re-gammas into a washed-out outline.
		long outlineKey = routedKey(gl, outlineTexture,
			com.periut.retrodragon.api.ProgramSpec.VertexLayout.FIXED_FUNCTION,
			gl.pipelineKeyWithDepthBias(OUTLINE_DEPTH_BIAS, OUTLINE_DEPTH_SLOPE));
		LIST.add(LINE_EXPANDER.data(), LINE_EXPANDER.vertexCount(), Primitives.GL_QUADS,
			outlineKey,
			outlineTexture,
			// hasColor passed through, NOT assumed: beta draws the outline with glColor4f and no
			// per-vertex colour at all, so claiming otherwise makes the shader read an empty slot
			// and the outline comes out the wrong colour entirely.
			gl.state().writeUniformsEye(hasColor, hasNormals, hasTexture), phase);
		return true;
	}

	/**
	 * How hard the expanded outline is pulled towards the viewer, in {@code glPolygonOffset} units.
	 *
	 * <p>Enough to win against the block faces its edges are shared with, and no more: an edge that
	 * is genuinely behind other geometry must still be hidden. Tunable with
	 * {@code -Dretroperf.outlineBias}.
	 */
	private static final int OUTLINE_DEPTH_BIAS = Integer.getInteger("retroperf.outlineBias", -2);
	private static final int OUTLINE_DEPTH_SLOPE = Integer.getInteger("retroperf.outlineSlope", -1);

	/**
	 * Records a batch whose texture and colour are supplied rather than read from the shim.
	 *
	 * <p>Used by {@link TextBatcher}, which bypasses the game's {@code glBindTexture} and
	 * {@code glColor4f} entirely so that consecutive strings produce identical uniform blocks and
	 * merge into one draw.
	 */
	public static void captureText(ByteBuffer source, int vertexCount, int texture) {
		if (!active() || vertexCount <= 0) {
			return;
		}
		GlShim gl = ShimTracker.shim();
		gl.setTopology(Primitives.topology(Primitives.GL_QUADS));
		LIST.add(source, vertexCount, Primitives.GL_QUADS, gl.pipelineKey(), texture,
			gl.state().writeUniforms(true, false), com.periut.retrodragon.api.ShaderApi.phase());
	}

	/**
	 * Records one terrain section layer, drawn straight from the buffer the mesher filled.
	 *
	 * <p>Nothing is copied: a section's geometry changes only when its blocks do, so re-uploading it
	 * every frame would move tens of megabytes a second to reproduce data the GPU already holds.
	 * The batch still takes its true place in the frame's draw order, which is what keeps the world
	 * behind the GUI and depth-tested against itself.
	 */
	public static void captureTerrain(com.periut.retrodragon.gpu.GpuBuffer buffer, int firstVertex,
			int vertexCount) {
		// Released buffers are rejected here as well as at bind time: a batch recorded against one is
		// dead weight for the rest of the frame, and dropping it at the point the caller can still be
		// identified is what makes "which section" answerable.
		if (!active() || buffer == null || !buffer.valid() || vertexCount <= 0) {
			return;
		}
		GlShim gl = ShimTracker.shim();
		int glMode = QuadVertices.glMode();
		gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN);
		gl.setTopology(Primitives.topology(glMode));
		gl.state().setTerrainParams(TerrainAppearance.atlasTexels(), TerrainAppearance.tileTexels(),
			TerrainAppearance.maxLod(), TerrainAppearance.rgss());
		int texture = gl.boundTexture();
		terrainBatches++;
		terrainVertices += vertexCount;
		LIST.addExternal(buffer, firstVertex, vertexCount, glMode,
			routedKey(gl, texture, com.periut.retrodragon.api.ProgramSpec.VertexLayout.TERRAIN),
			texture, gl.state().writeUniforms(true, false),
			com.periut.retrodragon.api.ShaderApi.phase());
		// Back to the default program: the very next thing beta draws is an entity or the GUI.
		gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_FIXED_FUNCTION);
	}

	/** Logs a batch as beta handed it over -- the ground truth for any "why is this wrong" question. */
	private static void dump(ByteBuffer source, int vertexCount, int glMode,
			boolean hasColor, boolean hasNormals) {
		StringBuilder sb = new StringBuilder();
		sb.append("batch mode=").append(glMode).append(" vertices=").append(vertexCount)
			.append(" color=").append(hasColor).append(" normals=").append(hasNormals)
			.append(" texture=").append(ShimTracker.shim().boundTexture());
		int base = source.position();
		for (int v = 0; v < Math.min(vertexCount, 6); v++) {
			int at = base + v * com.periut.retrodragon.shim.DrawList.STRIDE;
			sb.append(String.format("%n  v%d pos=(%.1f, %.1f, %.1f) uv=(%.3f, %.3f) rgba=%08X",
				v, source.getFloat(at), source.getFloat(at + 4), source.getFloat(at + 8),
				source.getFloat(at + 12), source.getFloat(at + 16), source.getInt(at + 20)));
		}
		RetroDragon.LOGGER.info(sb.toString());
	}

	/** {@code -Dretroperf.dumpFrame=N} logs every batch of frame N in beta's own vertex format. */
	private static final long DUMP_FRAME = Long.getLong("retroperf.dumpFrame", -1L);

	/**
	 * {@code -Dretroperf.statsFrames=N} -- how far into the run the per-frame stats line keeps
	 * reporting. Only consulted under {@code -Dretroperf.verbose=true}; 0 turns it off entirely.
	 */
	private static final long STATS_FRAMES = Long.getLong("retroperf.statsFrames", 600L);

	/** Submits and presents everything recorded since the last call. */
	public static synchronized void present() {
		if (!initialized) {
			return;
		}
		if (extensionsPending()) {
			startExtensions(GpuBackend.context());
		}
		renderer.syncPresentMode();
		// BEFORE beginFrame, and that ordering is the whole point.
		//
		// This can drain the GPU and free and reallocate textures. Doing either after beginFrame means
		// doing it while a swapchain drawable is acquired and unpresented, and blocking for up to two
		// seconds in that state starves the compositor of drawables -- which on macOS does not stall
		// the game, it takes the window server down with it. A fullscreen transition is exactly when
		// it fires: the size changes, so beginFrame already drained and reconfigured the surface once,
		// and this drained again on top of it holding the drawable that reconfigure had just handed
		// out. Up here nothing has been acquired, which is the same reason resize() is safe.
		syncScaleTargets();
		if (!renderer.beginFrame()) {
			// No backbuffer this frame (minimised, or mid-resize). The recorded draws are dropped
			// rather than held: they describe a frame at the old size, and beta will redraw.
			LIST.reset();
			return;
		}
		lastDraws = replay();
		int vertices = LIST.vertexCount();
		int segments = LIST.segmentCount();
		// Read before reset(), which zeroes them.
		int batches = LIST.batchCount();
		int merged = LIST.mergedCount();
		if (frames + 1 == SHOT_FRAME) {
			renderer.screenshot(java.nio.file.Path.of("webgpu-frame-" + SHOT_FRAME + ".png"));
		}
		int terrainThisFrame = terrainBatches;
		int terrainVertsThisFrame = terrainVertices;
		terrainBatches = 0;
		terrainVertices = 0;
		renderer.endFrame();
		LIST.reset();
		rearmWorldFrame();
		frames++;

		// Clean exit lives in FrameTimer, which ticks on both backends -- a comparison run has to end
		// the same way whichever one is live.

		// Every 60 frames, and only for the first STATS_FRAMES of them: enough to see the shape of a
		// frame settle and notice it coming out empty, after which it is the same line forever. A
		// session used to end with thousands of copies of it, which is how a log stops being read.
		if (RetroDragon.VERBOSE && frames <= STATS_FRAMES && frames % 60 == 1) {
			RetroDragon.LOGGER.info("frame {}: {} draws ({} binds), {} vertices, {} passes,"
					+ " {} textures, {} pipelines, {}, world AA {}",
				frames, lastDraws, immediate.bindsLastFrame(), vertices, segments,
				textures.count(), pipelines.builtCount(), FramePacing.describe(),
				TerrainAppearance.enabled() ? "on" : "off");
			// Captures and merges alongside the draw count, because the three answer different
			// questions and only together say where the capture path's time goes. Draws is what
			// reaches the GPU; captures is how many times the shim built a 256-byte uniform block and
			// compared it against its neighbour, which happens whether or not the batch survives as
			// its own draw. A frame with few draws and many captures is paying per-batch CPU cost for
			// GPU work it already collapsed.
			RetroDragon.LOGGER.info("frame {}: {} captures -> {} batches ({} merged away)",
				frames, batches + merged, batches, merged);
			// Terrain separately, because it is the one stream that reaches the frame WITHOUT going
			// through the shared vertex buffer -- so a frame with no terrain looks identical in every
			// other counter to a frame with plenty.
			RetroDragon.LOGGER.info("frame {}: terrain {} batches, {} vertices",
				frames, terrainThisFrame, terrainVertsThisFrame);
		}
	}

	/**
	 * Records the frame, with a shader extension's hooks in the gaps.
	 *
	 * <p>With no extension this is exactly the old single call: one upload, one range, the swapchain.
	 * The split below is skipped entirely rather than degenerating into three ranges over the same
	 * attachment, because each range is at least one more pass, and a pass that changes nothing still
	 * loads and stores the whole framebuffer.
	 *
	 * <p>With one, the order is: shadow map, opaque world, {@code onDeferred}, the rest of the world,
	 * {@code onComposite}, the GUI, {@code onFrameEnd}. Each hook runs with the frame's encoder open
	 * and no pass on it, which is the only state a full-screen pass or a copy can be recorded from.
	 */
	private static int replay() {
		immediate.upload(LIST);

		com.periut.retrodragon.api.ShaderExtension[] extensions =
			com.periut.retrodragon.api.ShaderApi.extensions();
		boolean redirected = shaderTargets != null && worldPipelines != null;

		int worldEnd = LIST.firstNonWorldBatch(0);
		int deferredAt = Math.min(LIST.firstTranslucentBatch(0), worldEnd);

		// Render scale, on the ordinary path: the world range goes into a target of its own size and
		// is resampled onto the swapchain before the GUI range is drawn over it at full resolution.
		//
		// No viewport work is needed here, unlike the GL backend. This renderer never calls
		// SetViewport at all -- a pass covers its whole attachment, so NDC lands on whatever size the
		// target is, and a uniform scale leaves the projection's aspect untouched.
		//
		// Deliberately NOT combined with a shader extension's world redirect (handled below). There
		// the extension owns the world target and its composite writes the swapchain, so scaling would
		// mean resampling either its input or its output, and which of those is right is the
		// extension's business rather than something to guess at here.
		if (RenderScale.active() && worldEnd > 0 && !redirected && scaleTargetsReady()) {
			String filter = RenderScale.effectiveFilter();
			int sw = scaleTarget.width();
			int sh = scaleTarget.height();

			immediate.render(renderer.frame(), scaleTarget.view(), NO_AUX, scaleDepth.view(),
				LIST, 0, worldEnd, pipelines, textures, MemorySegment.NULL);
			scaleBlit.draw(renderer.frame(), renderer.colorView(), scaleTarget.view(),
				sw, sh, renderer.width(), renderer.height(), filter);
			return immediate.render(renderer.frame(), renderer.colorView(), NO_AUX,
				renderer.depthView(), LIST, worldEnd, LIST.batchCount(), pipelines, textures,
				MemorySegment.NULL);
		}

		// No extension, or NO WORLD THIS FRAME: one upload, one range, straight to the swapchain --
		// byte for byte the path the engine takes with no shader mod installed at all.
		//
		// The second half of that condition is what makes a menu safe. A title screen, a pause screen
		// over no world, a loading screen: none of them have world geometry, and every piece of the
		// extension machinery is inert there anyway -- a skybox with nothing to paint, a composite
		// with nothing to tonemap, a shadow map with nothing to cast. Running the split path for them
		// meant opening passes that drew nothing, suppressing a clear that then went unpainted, and
		// rendering a 3072-square shadow map of an empty world every frame. Taking the ordinary path
		// removes all of it, and removes any chance of a menu frame reaching the screen half-written.
		if (extensions.length == 0 || worldEnd == 0) {
			return immediate.render(renderer.frame(), renderer.colorView(), NO_AUX,
				renderer.depthView(), LIST, 0, LIST.batchCount(), pipelines, textures,
				MemorySegment.NULL);
		}

		MemorySegment shaderGroup = shaderResources == null
			? MemorySegment.NULL : shaderResources.group();
		MemorySegment worldView = redirected ? shaderTargets.view() : renderer.colorView();
		MemorySegment[] worldAux = redirected ? shaderTargets.auxViews() : NO_AUX;
		FixedFunctionPipelines worldCache = redirected ? worldPipelines : pipelines;
		// Where the frame was cut, and by what. A world that never ends means the GUI is being drawn
		// into the shader extension's linear target and re-gamma'd by its composite, which looks like
		// a gamma bug everywhere at once -- washed-out text, a too-bright menu -- rather than like a
		// phase that was never set.
		if (RetroDragon.VERBOSE && frames % 60 == 1) {
			RetroDragon.LOGGER.info("frame {}: batches {}, deferred at {}, world ends at {}",
				frames, LIST.batchCount(), deferredAt, worldEnd);
		}

		renderShadow(extensions, worldEnd, shaderGroup);

		// A frame with no world batches at all is a menu. Told to the hooks so a skybox and a
		// composite can stand down rather than grading the title screen.
		ShaderFrameImpl context =
			new ShaderFrameImpl(worldView, shaderGroup, redirected, worldEnd > 0);

		// Before any world geometry: where a skybox is painted.
		hook(extensions, context, HOOK_WORLD_BEGIN);
		// Only honoured when there IS a world this frame. A claim is a promise to paint every pixel,
		// and an extension keeps that promise in onWorldBegin -- which stands down on a menu. Taking
		// the clear away on a frame where nothing paints leaves the target holding whatever was in it,
		// which on a title screen is a black flicker between frames that had a world and frames that
		// did not.
		boolean ownClear = worldEnd > 0
			&& com.periut.retrodragon.api.ShaderApi.worldColorClearClaimed();

		int draws = immediate.render(renderer.frame(), worldView, worldAux, renderer.depthView(),
			LIST, 0, deferredAt, worldCache, textures, shaderGroup, ownClear);
		hook(extensions, context, HOOK_DEFERRED);
		draws = immediate.render(renderer.frame(), worldView, worldAux, renderer.depthView(),
			LIST, deferredAt, worldEnd, worldCache, textures, shaderGroup, ownClear);
		hook(extensions, context, HOOK_COMPOSITE);
		// The GUI always goes to the swapchain, whether or not the world was redirected: it is drawn
		// in display space over an image the composite chain has already tonemapped.
		draws = immediate.render(renderer.frame(), renderer.colorView(), NO_AUX,
			renderer.depthView(), LIST, worldEnd, LIST.batchCount(), pipelines, textures,
			shaderGroup);
		hook(extensions, context, HOOK_FRAME_END);
		// Frees bind groups replaced several frames ago; see ShaderResources.endFrame.
		if (shaderResources != null) {
			shaderResources.endFrame();
		}
		return draws;
	}

	private static final MemorySegment[] NO_AUX = new MemorySegment[0];

	private static final int HOOK_WORLD_BEGIN = 3;
	private static final int HOOK_DEFERRED = 0;
	private static final int HOOK_COMPOSITE = 1;
	private static final int HOOK_FRAME_END = 2;

	/**
	 * Runs one hook on every extension, in priority order.
	 *
	 * <p>A throwing extension is removed rather than retried. There is no useful recovery from a hook
	 * that failed mid-frame -- it may have left a pass open, in which case every later command in the
	 * frame is invalid -- and an extension that throws once throws every frame.
	 */
	private static void hook(com.periut.retrodragon.api.ShaderExtension[] extensions,
			ShaderFrameImpl context, int which) {
		for (com.periut.retrodragon.api.ShaderExtension extension : extensions) {
			try {
				switch (which) {
					case HOOK_WORLD_BEGIN -> extension.onWorldBegin(context);
					case HOOK_DEFERRED -> extension.onDeferred(context);
					case HOOK_COMPOSITE -> extension.onComposite(context);
					default -> extension.onFrameEnd(context);
				}
			} catch (Throwable t) {
				RetroDragon.LOGGER.error("shader extension '{}' threw in a render hook; removing it",
					extension.id(), t);
				com.periut.retrodragon.api.ShaderApi.remove(extension);
				// The hook may have left a pass open; closing it keeps the rest of the frame valid.
				renderer.frame().endPass();
			}
		}
	}

	/** Renders the shadow map for the highest-priority extension that asked for one. */
	private static void renderShadow(com.periut.retrodragon.api.ShaderExtension[] extensions,
			int worldEnd, MemorySegment shaderGroup) {
		for (com.periut.retrodragon.api.ShaderExtension extension : extensions) {
			com.periut.retrodragon.api.ShaderExtension.ShadowRequest request;
			try {
				request = extension.shadowRequest();
			} catch (Throwable t) {
				RetroDragon.LOGGER.error("shader extension '{}' threw asking for a shadow pass;"
					+ " removing it", extension.id(), t);
				com.periut.retrodragon.api.ShaderApi.remove(extension);
				return;
			}
			if (request == null) {
				continue;
			}
			try {
				WebGPUContext ctx = GpuBackend.context();
				if (shadowMap == null) {
					shadowMap = new ShadowMap(ctx);
				}
				shadowMap.ensure(request.resolution());
				if (shadowPipelines == null) {
					shadowPipelines = FixedFunctionPipelines.create(ctx, sharedPipelines, "shadow",
						0, null, ShadowMap.FORMAT, true, TerrainVertex.compact());
				}
				if (shaderResources != null) {
					shaderResources.setShadowMap(shadowMap.view());
				}
				// casterGroup, NOT the frame's group: the shadow map is the pass's own attachment,
				// and a texture cannot be sampled and rendered into at once.
				immediate.renderShadow(renderer.frame(), shadowMap.view(), LIST, worldEnd,
					shadowPipelines, textures,
					shaderResources == null ? MemorySegment.NULL : shaderResources.casterGroup(),
					request.program(), request.terrainProgram());
			} catch (Throwable t) {
				RetroDragon.LOGGER.error("shadow pass for '{}' failed; removing the extension",
					extension.id(), t);
				com.periut.retrodragon.api.ShaderApi.remove(extension);
			}
			// One shadow map per frame. A second extension's request would render over the first, and
			// the resources group holds exactly one shadow texture.
			return;
		}
	}

	/** The {@link com.periut.retrodragon.api.ShaderFrame} handed to the hooks. */
	private record ShaderFrameImpl(MemorySegment world, MemorySegment group, boolean redirected,
			boolean hasWorld) implements com.periut.retrodragon.api.ShaderFrame {
		@Override
		public WebGPUContext context() {
			return GpuBackend.context();
		}

		@Override
		public com.periut.retrodragon.gpu.Frame frame() {
			return renderer.frame();
		}

		@Override
		public java.lang.foreign.Arena arena() {
			return renderer.frameArena();
		}

		@Override
		public int width() {
			return renderer.width();
		}

		@Override
		public int height() {
			return renderer.height();
		}

		@Override
		public MemorySegment worldView() {
			return world;
		}

		@Override
		public MemorySegment depthView() {
			return renderer.depthView();
		}

		@Override
		public MemorySegment outputView() {
			return renderer.colorView();
		}

		@Override
		public int outputFormat() {
			return renderer.surfaceFormat();
		}

		@Override
		public com.periut.retrodragon.api.ShaderResources resources() {
			return shaderResources;
		}

		@Override
		public MemorySegment targetView(int index) {
			return shaderTargets == null ? MemorySegment.NULL : shaderTargets.view(index);
		}

		@Override
		public int targetFormat(int index) {
			return shaderTargets == null ? 0 : shaderTargets.format();
		}

		@Override
		public long frameCounter() {
			return frames;
		}
	}

	/** Called on a window resize, before the next frame is recorded. */
	public static synchronized void resize(int width, int height) {
		if (initialized) {
			renderer.resize(width, height);
			if (shaderTargets != null) {
				shaderTargets.resize(renderer.width(), renderer.height());
				if (shaderResources != null) {
					// A resize replaces the texture, so the composite's group has to be rebuilt around
					// the new one. Safe here: the renderer drained the GPU before reconfiguring.
					shaderResources.setWorldTarget(shaderTargets.view());
				}
			}
		}
	}

	public static long frames() {
		return frames;
	}

	public static int lastDraws() {
		return lastDraws;
	}

	public static int batches() {
		return immediate == null ? 0 : immediate.batchesLastFrame();
	}

	public static int pipelineCount() {
		return pipelines == null ? 0 : pipelines.builtCount();
	}

	/**
	 * How long teardown waits for the GPU to retire everything before it starts releasing.
	 *
	 * <p>Longer than the mid-game reconfigure budget, because the two are answering different
	 * questions. A resize that waits too long drops a frame; this one runs once, while the player is
	 * already looking at a closing window, and what it is buying is the difference between a clean
	 * exit and a compositor that has to be rebooted out of.
	 */
	private static final long TEARDOWN_DRAIN_MILLIS =
		Long.getLong("retroperf.teardownDrainMillis", 5000L);

	public static synchronized void shutdown() {
		// Stop accepting work first. The game keeps issuing GL calls while it shuts down -- saving a
		// world draws a progress screen -- and a capture into a half-released renderer is a crash
		// during the one operation that must not crash.
		initialized = false;
		failed = true;
		// THE GPU GOES QUIET BEFORE ANYTHING IS RELEASED, and if it will not, nothing is released.
		//
		// This is one decision for the whole teardown rather than a check inside each step, because
		// every step below frees something that in-flight work may still be reading: the extensions'
		// textures and buffers, the renderer's depth and offscreen targets, the surface itself, then
		// the immediate-mode buffers, the texture store and the pipeline caches. Draining inside
		// renderer.close() alone left the other seven releasing regardless.
		//
		// The bail-out leaks all of it on purpose. This runs on the way out of the process, so the
		// leak lives for milliseconds and then the OS takes it; releasing a texture out from under a
		// command buffer that is still executing leaves the macOS render server holding a reference
		// to a destroyed object, and that outlives the process by however long it takes to reboot.
		com.periut.retrodragon.gpu.WebGPUContext ctx = GpuBackend.context();
		if (ctx != null && !ctx.drain(TEARDOWN_DRAIN_MILLIS)) {
			RetroDragon.LOGGER.error("GPU did not drain within {} ms at shutdown ({} frame(s) still"
				+ " in flight); skipping GPU teardown entirely and letting the process exit take it."
				+ " Nothing is released under live work.", TEARDOWN_DRAIN_MILLIS, ctx.framesInFlight());
			return;
		}
		// Extensions first, and before the renderer: they hold GPU objects created from the same
		// device, and releasing the surface out from under work that still references them is the
		// hazard the whole teardown order exists to avoid.
		com.periut.retrodragon.api.ShaderApi.stopAll();
		// Before the renderer: these hold textures created from the same device, and the drain that
		// guards this whole sequence has already run.
		freeScaleTargets();
		releaseExtensionResources();
		extensionsStarted = false;
		if (renderer != null) {
			// Releases the surface, and with it any drawable still held, while the window's layer is
			// still alive. This is the ordering the whole shutdown path exists to guarantee.
			renderer.close();
			renderer = null;
		}
		TerrainArena.shutdown();
		if (immediate != null) {
			immediate.close();
			immediate = null;
		}
		if (textures != null) {
			textures.close();
			textures = null;
		}
		if (pipelines != null) {
			pipelines.close();
			pipelines = null;
		}
		// After every cache that borrows its shader modules and layouts.
		if (sharedPipelines != null) {
			sharedPipelines.close();
			sharedPipelines = null;
		}
		RetroDragon.LOGGER.info("WebGPU renderer shut down after {} frames", frames);
	}
}
