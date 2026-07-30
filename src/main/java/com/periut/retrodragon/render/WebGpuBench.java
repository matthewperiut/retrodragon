package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.DepthBuffer;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.RenderTarget;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GlShim;
import com.periut.retrodragon.shim.PipelineKey;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Offscreen throughput for a terrain-shaped workload. {@code ./gradlew webgpuBench}.
 *
 * <h2>Why offscreen</h2>
 *
 * No window, no swapchain, no compositor -- so it cannot wedge the system the way a windowed run
 * can, and it can be left to run unattended. It also removes vsync, which is what made the one
 * in-game figure recorded so far useless: 117 fps on a 120 Hz panel is the display's number, not the
 * renderer's.
 *
 * <p>What it does NOT measure is the game: no chunk meshing, no entity or GUI draws, no CPU-side
 * frame logic. It measures the submission and rasterisation cost of a frame shaped like beta's
 * terrain pass, which is precisely what the arena and the vertex layout change -- and therefore the
 * questions it can answer honestly.
 *
 * <h2>What it compares</h2>
 *
 * Two axes, measured together because they interact.
 *
 * <ul>
 * <li><b>How the geometry is stored.</b> Three layouts drawing IDENTICAL pixels: beta's own
 *     six-vertices-per-quad triangle list at 32 bytes, the same quads indexed at four vertices, and
 *     those quads in the 20-byte packing. 192, 128 and 80 bytes per quad respectively.
 * <li><b>How it is drawn.</b> A buffer per section, one draw each, versus the shared arena where
 *     adjacent sections merge into a single draw.
 * </ul>
 *
 * All six combinations render the same image. Reported side by side, because a draw-call reduction
 * that does not move the frame time is not a win, and this is the cheapest way to find that out.
 */
public final class WebGpuBench {
	private static final int WIDTH = Integer.getInteger("retroperf.bench.width", 1708);
	private static final int HEIGHT = Integer.getInteger("retroperf.bench.height", 960);
	private static final int SECTIONS = Integer.getInteger("retroperf.bench.sections", 800);
	private static final int QUADS_PER_SECTION = Integer.getInteger("retroperf.bench.quads", 400);
	private static final int FRAMES = Integer.getInteger("retroperf.bench.frames", 200);
	private static final int WARMUP = Integer.getInteger("retroperf.bench.warmup", 30);

	/**
	 * One way of storing a quad, as it reaches the GPU.
	 *
	 * @param verticesPerQuad six for beta's pre-expanded triangle list, four when the shared index
	 *     buffer does the expansion instead
	 * @param glMode          what the batch is submitted as, which is what picks the index path
	 * @param compact         whether the pipeline's terrain layout is the 20-byte one
	 */
	private record Layout(String name, int verticesPerQuad, int stride, int glMode, boolean compact) {
		int bytesPerQuad() {
			return verticesPerQuad * stride;
		}
	}

	private static final Layout TRIS_32 =
		new Layout("tris/32B", 6, TerrainVertex.LEGACY_STRIDE, Primitives.GL_TRIANGLES, false);
	private static final Layout QUADS_32 =
		new Layout("quads/32B", 4, TerrainVertex.LEGACY_STRIDE, Primitives.GL_QUADS, false);
	private static final Layout QUADS_20 =
		new Layout("quads/20B", 4, TerrainVertex.COMPACT_STRIDE, Primitives.GL_QUADS, true);

	private WebGpuBench() {
	}

	public static void main(String[] args) {
		System.out.println("workload   = " + SECTIONS + " sections x " + QUADS_PER_SECTION
			+ " quads at " + WIDTH + "x" + HEIGHT + ", " + FRAMES + " frames");

		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("device     = " + ctx.backendName());
			try (Arena arena = Arena.ofShared();
					RenderTarget target = RenderTarget.create(ctx, arena, WIDTH, HEIGHT);
					DepthBuffer depth = DepthBuffer.create(ctx, arena, WIDTH, HEIGHT);
					// Both terrain layouts stood up in the one process, so the comparison is between
					// two pipelines on the same device in the same run rather than between two runs.
					FixedFunctionPipelines legacy =
						FixedFunctionPipelines.create(ctx, target.format(), depth.format(), false);
					FixedFunctionPipelines packed =
						FixedFunctionPipelines.create(ctx, target.format(), depth.format(), true)) {

				System.out.println();
				System.out.printf("%-10s %9s %12s   %-22s %-22s%n",
					"layout", "bytes/qd", "MB/frame", "per-section", "arena");

				Result base = null;
				for (Layout layout : new Layout[] { TRIS_32, QUADS_32, QUADS_20 }) {
					FixedFunctionPipelines pipelines = layout.compact() ? packed : legacy;
					Result perSection = run(ctx, target, depth, pipelines, layout, false);
					Result shared = run(ctx, target, depth, pipelines, layout, true);
					if (base == null) {
						base = perSection;
					}

					double megabytes = (double) SECTIONS * QUADS_PER_SECTION * layout.bytesPerQuad()
						/ (1024 * 1024);
					System.out.printf("%-10s %9d %12.1f   %6.2f ms %5d draws   %6.2f ms %5d draws%n",
						layout.name(), layout.bytesPerQuad(), megabytes,
						perSection.millis, perSection.draws, shared.millis, shared.draws);
				}

				System.out.println();
				// Attributed, not lumped. The arena and the vertex layout are separate changes made
				// at separate times, and a single before/after against the worst configuration
				// credits each of them with the other's win -- which is how an honest 1.5x gets
				// reported as a 1.6x and then quoted as though the layout did all of it.
				Result neither = run(ctx, target, depth, legacy, TRIS_32, false);
				Result arenaOnly = run(ctx, target, depth, legacy, TRIS_32, true);
				Result both = run(ctx, target, depth, packed, QUADS_20, true);
				System.out.printf("neither         : %6.2f ms/frame   beta's layout, a draw per section%n",
					neither.millis);
				System.out.printf("arena only      : %6.2f ms/frame   %.2fx%n",
					arenaOnly.millis, ratio(neither.millis, arenaOnly.millis));
				System.out.printf("arena + layout  : %6.2f ms/frame   %.2fx on top of the arena,"
					+ " %.2fx from the start%n",
					both.millis, ratio(arenaOnly.millis, both.millis), ratio(neither.millis, both.millis));

				overdraw(ctx, target, depth, packed);
				shading(ctx, target, depth, packed);
			}
		}
	}

	private record Result(double millis, int draws) {
	}

	private static double ratio(double before, double after) {
		return after == 0 ? 0 : before / after;
	}

	// --- overdraw ---------------------------------------------------------------------------------

	private static final int LAYERS = Integer.getInteger("retroperf.bench.layers", 8);

	/**
	 * What the depth buffer is actually saving, and what the alpha test is costing.
	 *
	 * <p>The layout table above is a vertex-cost measurement: its bands do not overlap, so it
	 * rasterises about one screen and says nothing about depth. A real world is not like that.
	 * Terrain is layers of blocks in front of other blocks, and the fill cost is set by how much of
	 * it the GPU manages to reject before shading it.
	 *
	 * <p>Two things decide that, and they interact, so they are measured as a 2x2:
	 *
	 * <ul>
	 * <li><b>Draw order.</b> Front to back lets each layer's depth reject everything behind it.
	 *     Back to front shades every layer in full. The gap is what early depth testing is worth.
	 * <li><b>Whether the shader can discard.</b> A fragment shader containing a {@code discard}
	 *     cannot have its depth resolved before it runs, so drivers disable early depth testing for
	 *     the whole pipeline. Beta keeps the alpha test on for the entire terrain pass -- for leaves,
	 *     grass and glass -- which would mean the opaque bulk of the world pays for it too.
	 * </ul>
	 *
	 * <p>If the discard is what costs the early-Z, the front-to-back row will only pull ahead once
	 * the alpha test is compiled out. That is the whole question, and it is not answerable by
	 * reasoning about it -- Metal's behaviour here is the driver's business.
	 */
	private static void overdraw(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines) {
		System.out.println();
		System.out.printf("overdraw   = %d full-screen layers, %d quads each%n",
			LAYERS, QUADS_PER_SECTION);
		System.out.printf("%-14s %14s %14s%n", "", "alpha test", "no alpha test");

		// Every cell measured twice, the second pass visiting them in the reverse order. A first
		// attempt at this ran each cell once and produced a clean, confident, WRONG answer: the
		// combination that happened to run first was the fastest, and the reading was that removing
		// the alpha test hurt. Warm-up and pipeline compilation do not distribute themselves evenly
		// over a matrix, so a matrix measured in one order measures the order as much as the matrix.
		double[][] results = new double[2][2];
		int[] programs = { PipelineKey.PROGRAM_TERRAIN, PipelineKey.PROGRAM_TERRAIN_OPAQUE };
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < 4; i++) {
				int cell = pass == 0 ? i : 3 - i;
				int order = cell >> 1;
				int variant = cell & 1;
				double millis = runOverdraw(ctx, target, depth, pipelines, programs[variant],
					order == 0).millis;
				// The minimum of the two passes: both measure the same GPU work, so a higher
				// reading is contamination, never signal.
				results[order][variant] = pass == 0 ? millis : Math.min(results[order][variant], millis);
			}
		}

		for (int order = 0; order < 2; order++) {
			System.out.printf("%-14s %11.2f ms %11.2f ms%n",
				order == 0 ? "near -> far" : "far -> near",
				results[order][0], results[order][1]);
		}

		System.out.printf("early-Z is worth %.2fx with the alpha test, %.2fx without%n",
			results[1][0] / results[0][0], results[1][1] / results[0][1]);
		System.out.printf("compiling the alpha test out is worth %.2fx front-to-back%n",
			results[0][0] / results[0][1]);
	}

	/**
	 * {@code LAYERS} full-screen sheets at distinct depths, submitted near-first or far-first.
	 *
	 * <p>Each sheet is its own draw and its own depth, so nothing merges and the only variable is
	 * the order the GPU sees them in. Depth test LEQUAL with depth write on, which is beta's opaque
	 * terrain state.
	 */
	private static Result runOverdraw(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, int program, boolean nearFirst) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		// Depth spread over the clip range so each layer strictly occludes the one behind it.
		gl.glOrtho(0.0, WIDTH, HEIGHT, 0.0, 0.0, LAYERS + 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0B71);   // GL_DEPTH_TEST
		gl.glDepthFunc(0x0203); // GL_LEQUAL
		gl.glDepthMask(true);
		gl.glEnable(0x0DE1);
		gl.setProgram(program);
		gl.setTopology(Primitives.topology(Primitives.GL_QUADS));
		// The alpha test ON, as beta runs the terrain pass. The opaque variant simply has no
		// discard to reach, which is the point: same uniform, different compiled shader.
		gl.glEnable(0x0BC0);   // GL_ALPHA_TEST
		gl.glAlphaFunc(0x0204, 0.1F); // GL_GREATER, a reference every texel clears

		int verticesPerLayer = QUADS_PER_SECTION * 4;
		int stride = TerrainVertex.COMPACT_STRIDE;
		ByteBuffer staging = ByteBuffer.allocateDirect(verticesPerLayer * stride)
			.order(ByteOrder.nativeOrder());

		GpuBuffer[] layers = new GpuBuffer[LAYERS];
		try {
			for (int layer = 0; layer < LAYERS; layer++) {
				fillSheet(staging, layer);
				layers[layer] = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "bench-layer");
				layers[layer].write(staging, verticesPerLayer * stride);
			}

			DrawList list = new DrawList();
			int draws = 0;
			double millis = 0;
			try (ImmediateRenderer immediate = new ImmediateRenderer(ctx);
					TextureStore textures = new TextureStore(ctx)) {
				for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
					list.reset();
					list.clear(true, true, 0.05F, 0.05F, 0.1F, 1.0F);
					ByteBuffer uniforms = gl.state().writeUniforms(true, false);
					for (int i = 0; i < LAYERS; i++) {
						int layer = nearFirst ? i : LAYERS - 1 - i;
						list.addExternal(layers[layer], 0, verticesPerLayer, Primitives.GL_QUADS,
							gl.pipelineKey(), 0, uniforms);
					}

					long started = System.nanoTime();
					try (Frame f = Frame.begin(ctx)) {
						draws = immediate.render(f, target.view(), depth.view(), list, pipelines,
							textures);
					}
					ctx.markFrameSubmitted();
					ctx.awaitFramesInFlight(0);
					long elapsed = System.nanoTime() - started;
					if (frame >= WARMUP) {
						millis += elapsed / 1_000_000.0;
					}
				}
			}
			return new Result(millis / FRAMES, draws);
		} finally {
			for (GpuBuffer buffer : layers) {
				if (buffer != null) {
					buffer.close();
				}
			}
		}
	}

	/** One full-screen sheet of quads at depth {@code layer + 1}. */
	private static void fillSheet(ByteBuffer staging, int layer) {
		staging.clear();
		// A grid rather than a strip, so the sheet genuinely covers the target.
		int columns = (int) Math.ceil(Math.sqrt(QUADS_PER_SECTION));
		int rows = (QUADS_PER_SECTION + columns - 1) / columns;
		float cellWidth = (float) WIDTH / columns;
		float cellHeight = (float) HEIGHT / rows;
		float z = layer + 1.0F;
		int rgba = 0xFF000000 | (layer * 31 % 256) << 16 | (layer * 67 % 256) << 8 | 200;

		for (int quad = 0; quad < QUADS_PER_SECTION; quad++) {
			float x0 = quad % columns * cellWidth;
			float y0 = (float) (quad / columns) * cellHeight;
			// Deliberately overlapping by a cell, so coverage is complete and every layer really
			// does hide the one behind it.
			float x1 = x0 + cellWidth;
			float y1 = y0 + cellHeight;
			sheetVertex(staging, x0, y0, z, 0.0F, 0.0F, rgba);
			sheetVertex(staging, x0, y1, z, 0.0F, 1.0F, rgba);
			sheetVertex(staging, x1, y1, z, 1.0F, 1.0F, rgba);
			sheetVertex(staging, x1, y0, z, 1.0F, 0.0F, rgba);
		}
		staging.position(0).limit(QUADS_PER_SECTION * 4 * TerrainVertex.COMPACT_STRIDE);
	}

	private static void sheetVertex(ByteBuffer buffer, float x, float y, float z, float u, float v,
			int rgba) {
		int base = buffer.position();
		buffer.putFloat(base, x);
		buffer.putFloat(base + 4, y);
		buffer.putFloat(base + 8, z);
		buffer.putShort(base + 12, (short) TerrainVertex.packUv(u));
		buffer.putShort(base + 14, (short) TerrainVertex.packUv(v));
		buffer.putInt(base + 16, rgba);
		buffer.position(base + TerrainVertex.COMPACT_STRIDE);
	}

	/**
	 * The terrain shader's optional appearance work, as {@code GlState} carries it.
	 *
	 * @param tileTexels the atlas grid pitch; 0 means a stitched atlas, which switches off the tile
	 *     clamp, the mip walk and the supersampling -- one nearest tap at level 0
	 * @param rgss       1 to average four rotated-grid taps instead of one
	 */
	private record Shading(String name, float atlasTexels, float tileTexels, float maxLod, float rgss) {
	}

	/** What the layout table runs under: the cheapest path the shader has. */
	private static final Shading PLAIN = new Shading("1 tap, no mip", 256.0F, 0.0F, 0.0F, 0.0F);

	/**
	 * What the terrain shader's appearance settings cost, in the same units as everything above.
	 *
	 * <p>These are ON by default and they are not free: {@code Config.RGSS} makes the fragment stage
	 * take FOUR texture taps instead of one, and mipmapping adds the tile-clamp arithmetic and the
	 * per-mip inset around each of them. The layout table deliberately runs with both off -- a
	 * stitched atlas, one tap at level 0 -- so that it measures geometry rather than shading. This
	 * measures the other half.
	 *
	 * <p>Worth knowing precisely, because both are quality settings with a documented appearance
	 * justification (mipmaps halve shimmer; RGSS buys back the sharpness they cost). The question is
	 * not whether to have them, it is what they cost, and until now nothing said.
	 */
	private static final Shading[] SHADING = {
		PLAIN,
		new Shading("mip, 1 tap", 256.0F, 16.0F, 4.0F, 0.0F),
		new Shading("mip, 4 taps (rgss)", 256.0F, 16.0F, 4.0F, 1.0F),
	};

	private static void shading(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines) {
		System.out.println();
		System.out.println("shading    = the same geometry, the terrain shader's options on and off");

		// A real 256x256 atlas with a full mip chain, not the 1x1 default: the mip walk and the
		// four taps are only representative against a texture big enough to miss a cache.
		try (TextureStore textures = new TextureStore(ctx)) {
			int name = textures.gen();
			textures.define(name, 256, 256, checkerboard(256));

			double plain = 0;
			for (Shading shading : SHADING) {
				double millis = run(ctx, target, depth, pipelines, QUADS_20, true, shading, name)
					.millis;
				if (plain == 0) {
					plain = millis;
				}
				System.out.printf("%-20s %6.2f ms/frame   %+5.2f ms vs one tap%n",
					shading.name(), millis, millis - plain);
			}
		}
	}

	/** A texture with detail at every scale, so mip selection and filtering both have work to do. */
	private static ByteBuffer checkerboard(int size) {
		ByteBuffer texels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean on = ((x >> 1) + (y >> 1) & 1) == 0;
				texels.put((byte) (on ? 220 : 40));
				texels.put((byte) (on ? 180 : 60));
				texels.put((byte) (on ? 90 : 30));
				texels.put((byte) 255);
			}
		}
		texels.flip();
		return texels;
	}

	private static Result run(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, Layout layout, boolean shared) {
		return run(ctx, target, depth, pipelines, layout, shared, PLAIN, 0);
	}

	private static Result run(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, Layout layout, boolean shared, Shading shading,
			int texture) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, WIDTH, HEIGHT, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0DE1);
		gl.glEnable(0x0B71);
		gl.glDepthFunc(0x0203);
		// Terrain is what this measures, and it is the program whose vertex layout is under test.
		gl.setProgram(PipelineKey.PROGRAM_TERRAIN);
		gl.state().setTerrainParams(shading.atlasTexels(), shading.tileTexels(), shading.maxLod(),
			shading.rgss());

		int verticesPerSection = QUADS_PER_SECTION * layout.verticesPerQuad();
		ByteBuffer staging = ByteBuffer
			.allocateDirect(verticesPerSection * layout.stride())
			.order(ByteOrder.nativeOrder());

		GpuBuffer[] buffers = shared ? null : new GpuBuffer[SECTIONS];
		GpuBuffer arenaBuffer = null;
		ArenaAllocator allocator = null;
		int[] offsets = new int[SECTIONS];
		if (shared) {
			long bytes = (long) SECTIONS * verticesPerSection * layout.stride();
			arenaBuffer = GpuBuffer.sized(ctx, Flags.BUFFER_USAGE_VERTEX, "bench-arena", bytes);
			allocator = new ArenaAllocator((int) (bytes / layout.stride()));
		}

		try {
			for (int section = 0; section < SECTIONS; section++) {
				fill(staging, section, layout);
				if (shared) {
					offsets[section] = allocator.allocate(verticesPerSection);
					arenaBuffer.writeAt((long) offsets[section] * layout.stride(), staging,
						verticesPerSection * layout.stride());
				} else {
					buffers[section] = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "bench-section");
					buffers[section].write(staging, verticesPerSection * layout.stride());
				}
			}

			DrawList list = new DrawList();
			int draws = 0;
			double millis = 0;
			try (ImmediateRenderer immediate = new ImmediateRenderer(ctx);
					TextureStore textures = new TextureStore(ctx)) {
				for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
					list.reset();
					list.clear(true, true, 0.05F, 0.05F, 0.1F, 1.0F);
					ByteBuffer uniforms = gl.state().writeUniforms(true, false);
					for (int section = 0; section < SECTIONS; section++) {
						if (shared) {
							list.addExternal(arenaBuffer, offsets[section], verticesPerSection,
								layout.glMode(), gl.pipelineKey(), texture, uniforms);
						} else {
							list.addExternal(buffers[section], 0, verticesPerSection,
								layout.glMode(), gl.pipelineKey(), texture, uniforms);
						}
					}

					long started = System.nanoTime();
					try (Frame f = Frame.begin(ctx)) {
						draws = immediate.render(f, target.view(), depth.view(), list, pipelines,
							textures);
					}
					// Wait for the GPU before timing the next frame, or this measures how fast the
					// CPU can queue work rather than how fast the work completes.
					ctx.markFrameSubmitted();
					ctx.awaitFramesInFlight(0);
					long elapsed = System.nanoTime() - started;
					if (frame >= WARMUP) {
						millis += elapsed / 1_000_000.0;
					}
				}
			}
			return new Result(millis / FRAMES, draws);
		} finally {
			if (buffers != null) {
				for (GpuBuffer buffer : buffers) {
					if (buffer != null) {
						buffer.close();
					}
				}
			}
			if (arenaBuffer != null) {
				arenaBuffer.close();
			}
		}
	}

	/**
	 * A band of quads per section, spread down the target so every section rasterises something.
	 *
	 * <p>Every layout produces the SAME quads in the same order -- the six-vertex form re-emits v0
	 * and v2 exactly where the shared index buffer would have referenced them. That is what makes
	 * the three timings comparable: the rasteriser does identical work in all of them, and the only
	 * difference is how many bytes had to be fetched to describe it.
	 */
	private static void fill(ByteBuffer staging, int section, Layout layout) {
		staging.clear();
		float bandHeight = (float) HEIGHT / SECTIONS;
		float y0 = section * bandHeight;
		float y1 = y0 + bandHeight;
		float quadWidth = (float) WIDTH / QUADS_PER_SECTION;
		int rgba = 0xFF000000 | section * 37 % 256 << 16 | section * 91 % 256 << 8 | section * 53 % 256;

		for (int quad = 0; quad < QUADS_PER_SECTION; quad++) {
			float x0 = quad * quadWidth;
			float x1 = x0 + quadWidth;
			// Real texture coordinates rather than zeroes, so the packed layout's unorm16 conversion
			// is actually exercised instead of quantising nothing.
			float u0 = (float) quad / QUADS_PER_SECTION;
			float u1 = (float) (quad + 1) / QUADS_PER_SECTION;
			if (layout.verticesPerQuad() == 6) {
				vertex(staging, layout, x0, y0, u0, 0.0F, rgba);
				vertex(staging, layout, x0, y1, u0, 1.0F, rgba);
				vertex(staging, layout, x1, y1, u1, 1.0F, rgba);
				vertex(staging, layout, x0, y0, u0, 0.0F, rgba);
				vertex(staging, layout, x1, y1, u1, 1.0F, rgba);
				vertex(staging, layout, x1, y0, u1, 0.0F, rgba);
			} else {
				vertex(staging, layout, x0, y0, u0, 0.0F, rgba);
				vertex(staging, layout, x0, y1, u0, 1.0F, rgba);
				vertex(staging, layout, x1, y1, u1, 1.0F, rgba);
				vertex(staging, layout, x1, y0, u1, 0.0F, rgba);
			}
		}
		staging.position(0).limit(QUADS_PER_SECTION * layout.bytesPerQuad());
	}

	private static void vertex(ByteBuffer buffer, Layout layout, float x, float y, float u, float v,
			int rgba) {
		int base = buffer.position();
		buffer.putFloat(base, x);
		buffer.putFloat(base + 4, y);
		buffer.putFloat(base + 8, 0.0F);
		if (layout.compact()) {
			buffer.putShort(base + 12, (short) TerrainVertex.packUv(u));
			buffer.putShort(base + 14, (short) TerrainVertex.packUv(v));
			buffer.putInt(base + 16, rgba);
		} else {
			buffer.putFloat(base + 12, u);
			buffer.putFloat(base + 16, v);
			buffer.putInt(base + 20, rgba);
			buffer.putInt(base + 24, 0);
			buffer.putInt(base + 28, 0);
		}
		buffer.position(base + layout.stride());
	}
}
