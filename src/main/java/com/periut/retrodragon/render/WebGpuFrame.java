package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GlShim;
import com.periut.retrodragon.shim.ShimTracker;
import com.periut.retrodragon.window.sdl.Sdl3Window;

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

	private static FixedFunctionPipelines pipelines;
	private static ImmediateRenderer immediate;
	private static TextureStore textures;
	private static volatile boolean initialized;
	private static volatile boolean failed;

	private static long frames;
	private static int lastDraws;

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
			pipelines = FixedFunctionPipelines.create(ctx, renderer.surfaceFormat(),
				renderer.depthFormat());
			immediate = new ImmediateRenderer(ctx);
			textures = new TextureStore(ctx);
			initialized = true;
			RetroDragon.LOGGER.info("WebGPU renderer up: {}x{}, surface format {}",
				renderer.width(), renderer.height(), renderer.surfaceFormat());
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
		if (captureWideLine(gl, source, vertexCount, glMode, hasColor, hasNormals, hasTexture)) {
			return;
		}
		gl.setTopology(Primitives.topology(glMode));
		LIST.add(source, vertexCount, glMode, gl.pipelineKey(), gl.boundTexture(),
			gl.state().writeUniforms(hasColor, hasNormals, hasTexture));
	}

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
			int glMode, boolean hasColor, boolean hasNormals, boolean hasTexture) {

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
		LIST.add(LINE_EXPANDER.data(), LINE_EXPANDER.vertexCount(), Primitives.GL_QUADS,
			gl.pipelineKeyWithDepthBias(OUTLINE_DEPTH_BIAS, OUTLINE_DEPTH_SLOPE),
			gl.boundTexture(),
			// hasColor passed through, NOT assumed: beta draws the outline with glColor4f and no
			// per-vertex colour at all, so claiming otherwise makes the shader read an empty slot
			// and the outline comes out the wrong colour entirely.
			gl.state().writeUniformsEye(hasColor, hasNormals, hasTexture));
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
			gl.state().writeUniforms(true, false));
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
		LIST.addExternal(buffer, firstVertex, vertexCount, glMode,
			gl.pipelineKey(), gl.boundTexture(), gl.state().writeUniforms(true, false));
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
		renderer.syncPresentMode();
		if (!renderer.beginFrame()) {
			// No backbuffer this frame (minimised, or mid-resize). The recorded draws are dropped
			// rather than held: they describe a frame at the old size, and beta will redraw.
			LIST.reset();
			return;
		}
		lastDraws = immediate.render(renderer.frame(), renderer.colorView(), renderer.depthView(),
			LIST, pipelines, textures);
		int vertices = LIST.vertexCount();
		int segments = LIST.segmentCount();
		// Read before reset(), which zeroes them.
		int batches = LIST.batchCount();
		int merged = LIST.mergedCount();
		if (frames + 1 == SHOT_FRAME) {
			renderer.screenshot(java.nio.file.Path.of("webgpu-frame-" + SHOT_FRAME + ".png"));
		}
		renderer.endFrame();
		LIST.reset();
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
		}
	}

	/** Called on a window resize, before the next frame is recorded. */
	public static synchronized void resize(int width, int height) {
		if (initialized) {
			renderer.resize(width, height);
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

	public static synchronized void shutdown() {
		// Stop accepting work first. The game keeps issuing GL calls while it shuts down -- saving a
		// world draws a progress screen -- and a capture into a half-released renderer is a crash
		// during the one operation that must not crash.
		initialized = false;
		failed = true;
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
		RetroDragon.LOGGER.info("WebGPU renderer shut down after {} frames", frames);
	}
}
