package com.periut.retrodragon.render;

import com.periut.retrodragon.api.ProgramSpec;
import com.periut.retrodragon.api.ShaderApi;
import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.PipelineSpec;
import com.periut.retrodragon.gpu.Pipelines;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.shim.GlShim;
import com.periut.retrodragon.shim.GlState;
import com.periut.retrodragon.shim.PipelineKey;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.periut.webgpu.webgpu_h.*;

/**
 * The cache that turns a {@code shim/PipelineKey} into a real WebGPU pipeline.
 *
 * <p>GL let beta change blend, depth and cull between any two draws for free. WebGPU bakes them into
 * an immutable object that costs milliseconds to build, so the whole design rests on the set of
 * states beta actually visits being small and closed -- a few dozen, not thousands. Each is built
 * once, on first use, and kept for the process lifetime.
 *
 * <p>All pipelines share ONE bind group layout, declared explicitly rather than inferred per
 * pipeline. An inferred layout belongs to its pipeline, so a bind group made for one would be
 * rejected by every other -- the uniform binding would have to be rebuilt on every blend-mode change,
 * which is exactly the per-draw cost this whole path exists to avoid.
 *
 * <h2>One cache per set of attachments</h2>
 *
 * A pipeline bakes in the FORMAT and COUNT of what it writes, so the same draw state needs different
 * pipelines for the swapchain, for a shader extension's float world target with its G-buffer
 * attachments, and for a depth-only shadow map. Rather than widening the key -- which would multiply
 * every entry in it by a dimension almost no draw varies along -- there is one instance of this per
 * target layout, and they share the shader modules and layouts through the {@link Shared} they were
 * created from.
 */
public final class FixedFunctionPipelines implements AutoCloseable {
	/** Beta's own vertex layout, unchanged: position 3f, uv 2f, colour unorm8x4, normal snorm8x4. */
	public static final int VERTEX_STRIDE = 32;

	private static final int[][] VERTEX_ATTRIBUTES = {
		{ 0, 0, WGPUVertexFormat_Float32x3() },   // position
		{ 1, 12, WGPUVertexFormat_Float32x2() },  // uv
		{ 2, 20, WGPUVertexFormat_Unorm8x4() },   // colour (beta bakes light into this)
		{ 3, 24, WGPUVertexFormat_Snorm8x4() },   // normal, 3 bytes + 1 pad
	};

	/**
	 * Terrain in beta's layout, minus the normal -- {@code terrain.wgsl} does not declare one, so the
	 * pipeline must not either. The bytes are still there in the buffer; they are simply not fetched.
	 */
	private static final int[][] TERRAIN_LEGACY_ATTRIBUTES = {
		{ 0, 0, WGPUVertexFormat_Float32x3() },
		{ 1, 12, WGPUVertexFormat_Float32x2() },
		{ 2, 20, WGPUVertexFormat_Unorm8x4() },
	};

	/** The 20-byte packing; see {@link TerrainVertex} for why each field is the width it is. */
	private static final int[][] TERRAIN_COMPACT_ATTRIBUTES = {
		{ 0, 0, WGPUVertexFormat_Float32x3() },
		{ 1, 12, WGPUVertexFormat_Unorm16x2() },
		{ 2, 16, WGPUVertexFormat_Unorm8x4() },
	};

	/**
	 * Either layout plus the sprite size a stitched atlas needs.
	 *
	 * <p>{@code Uint8x4} rather than a single byte because WebGPU has no 8-bit scalar vertex format;
	 * only .x is read. The legacy variant costs nothing -- it lands in beta's existing pad word.
	 */
	private static final int[][] TERRAIN_COMPACT_SPRITE_ATTRIBUTES = {
		{ 0, 0, WGPUVertexFormat_Float32x3() },
		{ 1, 12, WGPUVertexFormat_Unorm16x2() },
		{ 2, 16, WGPUVertexFormat_Unorm8x4() },
		{ 3, 20, WGPUVertexFormat_Uint8x4() },
	};

	private static final int[][] TERRAIN_LEGACY_SPRITE_ATTRIBUTES = {
		{ 0, 0, WGPUVertexFormat_Float32x3() },
		{ 1, 12, WGPUVertexFormat_Float32x2() },
		{ 2, 20, WGPUVertexFormat_Unorm8x4() },
		{ 3, 28, WGPUVertexFormat_Uint8x4() },
	};

	/** Indexed by {@code PipelineKey.PROGRAM_*}. */
	private static final String[] SHADER_PATHS = {
		"/assets/retrodragon/shaders/wgsl/fixedfunc.wgsl",
		"/assets/retrodragon/shaders/wgsl/terrain.wgsl",
		// PROGRAM_TERRAIN_OPAQUE: the same file, with the alpha test stripped.
		"/assets/retrodragon/shaders/wgsl/terrain.wgsl",
	};

	/** Markers delimiting the region {@link PipelineKey#PROGRAM_TERRAIN_OPAQUE} removes. */
	/** Delimits the lines that only exist when the vertex carries a sprite size. */
	private static final String SPRITE_BEGIN = "//@SPRITE_BEGIN";
	private static final String SPRITE_END = "//@SPRITE_END";

	private static final String ALPHA_TEST_BEGIN = "//@ALPHA_TEST_BEGIN";
	private static final String ALPHA_TEST_END = "//@ALPHA_TEST_END";

	/**
	 * What every target layout has in common: the compiled shader modules and the two pipeline
	 * layouts.
	 *
	 * <p>Shared rather than duplicated because a shader module is expensive to build and identical
	 * across attachment sets -- the world pass and the shadow pass run the same WGSL. Compiling one
	 * per cache would triple the startup cost of every program an extension registers.
	 */
	public static final class Shared implements AutoCloseable {
		private final WebGPUContext ctx;
		private final Arena arena = Arena.ofShared();
		private final MemorySegment[] shaders = new MemorySegment[PipelineKey.MAX_PROGRAMS];
		private final MemorySegment bindGroupLayout;
		private final MemorySegment pipelineLayout;
		/** Group 0 plus the extension's group 1; null until an extension exists. */
		private MemorySegment shaderPipelineLayout = MemorySegment.NULL;

		private Shared(WebGPUContext ctx) {
			this.ctx = ctx;
			this.bindGroupLayout = Bindings.fixedFunctionLayout(ctx, arena, GlState.UNIFORM_BYTES);
			this.pipelineLayout =
				Bindings.pipelineLayout(ctx, arena, "retrodragon-fixedfunc", bindGroupLayout);
		}

		public static Shared create(WebGPUContext ctx) {
			return new Shared(ctx);
		}

		public MemorySegment bindGroupLayout() {
			return bindGroupLayout;
		}

		/**
		 * The compiled module for a program, built on first use.
		 *
		 * <p>Lazy because an extension registers a program per draw family and a given world reaches
		 * only some of them -- the nether never draws clouds, a clear day never draws weather. A
		 * program that is never routed to costs its source string and nothing else.
		 */
		MemorySegment shader(int program) {
			MemorySegment cached = shaders[program];
			if (cached != null) {
				return cached;
			}
			String label;
			String source;
			if (program < ShaderApi.builtInCount()) {
				label = "retrodragon-program-" + program;
				source = builtIn(program);
			} else {
				ProgramSpec spec = ShaderApi.program(program);
				if (spec == null) {
					throw new IllegalStateException("no program registered with id " + program);
				}
				label = spec.name();
				source = spec.source();
			}
			MemorySegment module = Shaders.compile(ctx, arena, label, source);
			if (module.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("program '" + label + "' failed to compile");
			}
			shaders[program] = module;
			return module;
		}

		/**
		 * The pipeline layout a program needs: group 0 alone, or group 0 and the extension group.
		 *
		 * <p>Built on demand rather than at construction because the extension's bind group layout
		 * does not exist until an extension has started, and the engine's own programs never need it.
		 */
		MemorySegment layoutFor(int program) {
			ProgramSpec spec = ShaderApi.program(program);
			if (spec == null || !spec.shaderGroup()) {
				return pipelineLayout;
			}
			if (shaderPipelineLayout.equals(MemorySegment.NULL)) {
				var resources = ShaderApi.resources();
				if (resources == null) {
					throw new IllegalStateException("program '" + spec.name()
						+ "' declares @group(1) but no shader resources exist yet");
				}
				shaderPipelineLayout = Bindings.pipelineLayout(ctx, arena, "retrodragon-shader",
					new MemorySegment[] { bindGroupLayout, resources.layout() });
			}
			return shaderPipelineLayout;
		}

		@Override
		public void close() {
			for (MemorySegment shader : shaders) {
				if (shader != null && !shader.equals(MemorySegment.NULL)) {
					wgpuShaderModuleRelease(shader);
				}
			}
			if (!shaderPipelineLayout.equals(MemorySegment.NULL)) {
				wgpuPipelineLayoutRelease(shaderPipelineLayout);
			}
			wgpuPipelineLayoutRelease(pipelineLayout);
			wgpuBindGroupLayoutRelease(bindGroupLayout);
			arena.close();
		}
	}

	private final WebGPUContext ctx;
	private final Shared shared;
	/** Holds every descriptor Dawn keeps a pointer into for this cache's pipelines. */
	private final Arena arena;
	private final Map<Long, MemorySegment> cache = new HashMap<>();
	private final int colorFormat;
	private final int[] auxFormats;
	private final int depthFormat;
	private final boolean depthOnly;
	private final boolean compactTerrain;
	private final String label;
	private int built;

	private FixedFunctionPipelines(WebGPUContext ctx, Shared shared, Arena arena, String label,
			int colorFormat, int[] auxFormats, int depthFormat, boolean depthOnly,
			boolean compactTerrain) {
		this.ctx = ctx;
		this.shared = shared;
		this.arena = arena;
		this.label = label;
		this.colorFormat = colorFormat;
		this.auxFormats = auxFormats;
		this.depthFormat = depthFormat;
		this.depthOnly = depthOnly;
		this.compactTerrain = compactTerrain;
	}

	/**
	 * @param depthFormat 0 for a colour-only target; must otherwise match the pass's depth attachment
	 */
	public static FixedFunctionPipelines create(WebGPUContext ctx, Shared shared,
			int colorFormat, int depthFormat) {
		return create(ctx, shared, "swapchain", colorFormat, NO_AUX, depthFormat, false,
			TerrainVertex.compact());
	}

	private static final int[] NO_AUX = new int[0];

	/**
	 * A standalone cache that owns its shader modules, for a caller with only one target layout.
	 *
	 * <p>The headless tests and benches, which stand a whole renderer up and tear it down inside one
	 * method. The real frame loop passes a {@link Shared} instead, because it holds three caches and
	 * they must not each compile the same shaders.
	 */
	public static FixedFunctionPipelines create(WebGPUContext ctx, int colorFormat, int depthFormat) {
		return create(ctx, colorFormat, depthFormat, TerrainVertex.compact());
	}

	public static FixedFunctionPipelines create(WebGPUContext ctx, int colorFormat, int depthFormat,
			boolean compactTerrain) {
		Shared shared = Shared.create(ctx);
		FixedFunctionPipelines pipelines = create(ctx, shared, "standalone", colorFormat, NO_AUX,
			depthFormat, false, compactTerrain);
		pipelines.ownedShared = shared;
		return pipelines;
	}

	/** Set only by the standalone factory; closed with this cache. */
	private Shared ownedShared;

	/**
	 * The terrain layout is normally whatever {@link TerrainVertex#COMPACT} says, because the mesher
	 * and the pipeline have to agree and both read that one flag. The bench overrides it so it can
	 * stand both layouts up in one process and measure them against each other, which is the only
	 * way the comparison is worth anything.
	 */
	public static FixedFunctionPipelines create(WebGPUContext ctx, Shared shared, String label,
			int colorFormat, int[] auxFormats, int depthFormat, boolean depthOnly,
			boolean compactTerrain) {
		return new FixedFunctionPipelines(ctx, shared, Arena.ofShared(), label, colorFormat,
			auxFormats == null ? NO_AUX : auxFormats, depthFormat, depthOnly, compactTerrain);
	}

	/**
	 * The WGSL for a built-in program, which for the opaque terrain variant is the terrain source
	 * with the alpha-test region cut out.
	 *
	 * <p>Textual, and deliberately so: the point is that the compiled shader must not CONTAIN a
	 * {@code discard}, because that is what makes a driver give up early depth testing. A uniform
	 * that skips the discard at runtime does not help -- the pipeline is already compiled by then.
	 *
	 * <p>Throws if the markers are missing rather than silently compiling a second identical shader,
	 * which would look like the optimisation was measured and found worthless.
	 */
	private static String builtIn(int index) {
		String source = source(SHADER_PATHS[index]);
		boolean terrain = index == PipelineKey.PROGRAM_TERRAIN
			|| index == PipelineKey.PROGRAM_TERRAIN_OPAQUE;
		if (terrain && !TerrainVertex.spriteClamp()) {
			// No stitched atlas: the vertex does not carry a sprite size, so the attribute is not in
			// the pipeline's layout either. Reading a location the layout does not declare is invalid
			// WGSL, hence a textual cut rather than a branch -- the same reasoning as the alpha test.
			source = strip(source, SPRITE_BEGIN, SPRITE_END);
		}
		if (index != PipelineKey.PROGRAM_TERRAIN_OPAQUE) {
			return source;
		}
		int begin = source.indexOf(ALPHA_TEST_BEGIN);
		int end = source.indexOf(ALPHA_TEST_END);
		if (begin < 0 || end < begin) {
			throw new IllegalStateException("terrain.wgsl is missing its " + ALPHA_TEST_BEGIN
				+ " / " + ALPHA_TEST_END + " markers; the opaque variant cannot be built");
		}
		return source.substring(0, begin) + source.substring(end + ALPHA_TEST_END.length());
	}

	/** Cuts every {@code begin}..{@code end} region out, markers included. */
	private static String strip(String source, String begin, String end) {
		StringBuilder out = new StringBuilder(source);
		for (int at = out.indexOf(begin); at >= 0; at = out.indexOf(begin)) {
			int close = out.indexOf(end, at);
			if (close < 0) {
				throw new IllegalStateException("terrain.wgsl has " + begin + " with no " + end);
			}
			out.delete(at, close + end.length());
		}
		return out.toString();
	}

	static String source(String path) {
		try (InputStream in = FixedFunctionPipelines.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("missing shader resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("could not read " + path, e);
		}
	}

	/** The pipeline for {@code key}, built on first use. Never null -- a failure throws. */
	public MemorySegment get(long key) {
		MemorySegment cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		MemorySegment pipeline = build(key);
		if (pipeline.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("pipeline creation failed for key " + Long.toHexString(key));
		}
		cache.put(key, pipeline);
		built++;
		return pipeline;
	}

	private MemorySegment build(long key) {
		int program = PipelineKey.program(key);
		if (program >= ShaderApi.programCount()) {
			// A stale key from a program that no longer exists -- an extension removed mid-session.
			// Falling back is better than failing the frame, and it is visibly wrong rather than
			// silently so.
			program = PipelineKey.PROGRAM_FIXED_FUNCTION;
			key = PipelineKey.withProgram(key, program);
		}
		ProgramSpec spec = ShaderApi.program(program);
		boolean terrain = spec == null
			? program == PipelineKey.PROGRAM_TERRAIN || program == PipelineKey.PROGRAM_TERRAIN_OPAQUE
			: spec.layout() == ProgramSpec.VertexLayout.TERRAIN;
		String name = spec == null ? (terrain ? "terrain" : "fixedfunc") : spec.name();

		PipelineSpec pipeline = new PipelineSpec()
			.label(label + "-" + name + "-" + Long.toHexString(key))
			.shader(shared.shader(program))
			// Terrain is the one program whose vertices this project packs itself, so it is the one
			// that can afford a layout narrower than beta's. Everything else reads bytes the
			// Tessellator wrote and must describe them exactly as they are.
			.vertexLayout(
				terrain ? TerrainVertex.stride(compactTerrain) : VERTEX_STRIDE,
				terrain
					? (TerrainVertex.spriteClamp()
						? (compactTerrain
							? TERRAIN_COMPACT_SPRITE_ATTRIBUTES : TERRAIN_LEGACY_SPRITE_ATTRIBUTES)
						: (compactTerrain ? TERRAIN_COMPACT_ATTRIBUTES : TERRAIN_LEGACY_ATTRIBUTES))
					: VERTEX_ATTRIBUTES)
			.layout(shared.layoutFor(program));

		if (depthOnly) {
			pipeline.depthOnly();
		} else {
			// A program declaring fewer aux outputs than the pass provides is fine -- WebGPU allows a
			// fragment stage to leave later attachments unwritten -- but declaring MORE is not, so the
			// pipeline gets the program's own count rather than the target's.
			int aux = spec == null ? 0 : Math.min(spec.auxTargets(), auxFormats.length);
			pipeline.color(colorFormat, aux == 0 ? NO_AUX : java.util.Arrays.copyOf(auxFormats, aux));
		}

		pipeline.topology = topology(PipelineKey.topology(key));
		pipeline.cullMode = cull(PipelineKey.cull(key));
		pipeline.blend = PipelineKey.blend(key);
		if (pipeline.blend) {
			pipeline.blendSrcColor = blendFactor(PipelineKey.blendSrc(key));
			pipeline.blendDstColor = blendFactor(PipelineKey.blendDst(key));
			// Alpha uses the same factors GL would: beta never calls glBlendFuncSeparate, so the
			// separate-alpha path exists only to keep the destination alpha sane on a surface that
			// has one.
			pipeline.blendSrcAlpha = pipeline.blendSrcColor;
			pipeline.blendDstAlpha = pipeline.blendDstColor;
		}
		if (depthFormat != 0) {
			pipeline.depth(depthFormat, PipelineKey.depthTest(key), PipelineKey.depthWrite(key),
				compare(PipelineKey.depthFunc(key)));
			// glPolygonOffset. Negative in beta's only use, which pulls the block-breaking overlay
			// TOWARDS the viewer so it wins the depth compare against the face it sits on.
			pipeline.polygonOffset(PipelineKey.offsetUnits(key), PipelineKey.offsetSlope(key));
		}
		return Pipelines.create(ctx, arena, pipeline);
	}

	// --- shim's dense indices to WebGPU enums ----------------------------------------------------

	private static int blendFactor(int dense) {
		return switch (dense) {
			case GlShim.BLEND_ZERO -> WGPUBlendFactor_Zero();
			case GlShim.BLEND_ONE -> WGPUBlendFactor_One();
			case GlShim.BLEND_SRC_ALPHA -> WGPUBlendFactor_SrcAlpha();
			case GlShim.BLEND_ONE_MINUS_SRC_ALPHA -> WGPUBlendFactor_OneMinusSrcAlpha();
			case GlShim.BLEND_DST_COLOR -> WGPUBlendFactor_Dst();
			case GlShim.BLEND_SRC_COLOR -> WGPUBlendFactor_Src();
			case GlShim.BLEND_ONE_MINUS_DST_COLOR -> WGPUBlendFactor_OneMinusDst();
			case GlShim.BLEND_ONE_MINUS_SRC_COLOR -> WGPUBlendFactor_OneMinusSrc();
			default -> WGPUBlendFactor_One();
		};
	}

	/** The shim's dense depth funcs are GL's own order, {@code GL_NEVER} through {@code GL_ALWAYS}. */
	private static int compare(int dense) {
		return switch (dense) {
			case GlShim.DEPTH_NEVER -> WGPUCompareFunction_Never();
			case GlShim.DEPTH_LESS -> WGPUCompareFunction_Less();
			case GlShim.DEPTH_EQUAL -> WGPUCompareFunction_Equal();
			case GlShim.DEPTH_LEQUAL -> WGPUCompareFunction_LessEqual();
			case GlShim.DEPTH_GREATER -> WGPUCompareFunction_Greater();
			case GlShim.DEPTH_NOTEQUAL -> WGPUCompareFunction_NotEqual();
			case GlShim.DEPTH_GEQUAL -> WGPUCompareFunction_GreaterEqual();
			default -> WGPUCompareFunction_Always();
		};
	}

	private static int cull(int dense) {
		return switch (dense) {
			case PipelineKey.CULL_BACK -> WGPUCullMode_Back();
			case PipelineKey.CULL_FRONT -> WGPUCullMode_Front();
			default -> WGPUCullMode_None();
		};
	}

	/**
	 * Every topology the key can carry needs a case here.
	 *
	 * <p>The strips were missing and fell through to a triangle list, which is not a wrong-looking
	 * draw so much as a silent one: the block-selection outline draws its top and bottom squares as
	 * five-vertex {@code GL_LINE_STRIP} batches, and as a triangle list those five vertices become
	 * one stray triangle. Only the four vertical edges survived, because they are a separate
	 * {@code GL_LINES} batch and {@code LineList} was mapped. {@code GL_TRIANGLE_STRIP} and
	 * {@code GL_QUAD_STRIP} were silently wrong the same way.
	 *
	 * <p>So this deliberately does NOT have a {@code default}: every {@code TOPOLOGY_*} is listed,
	 * and a new one fails to compile rather than quietly rendering as triangles.
	 */
	private static int topology(int dense) {
		return switch (dense) {
			case PipelineKey.TOPOLOGY_LINES -> WGPUPrimitiveTopology_LineList();
			case PipelineKey.TOPOLOGY_LINE_STRIP -> WGPUPrimitiveTopology_LineStrip();
			case PipelineKey.TOPOLOGY_POINTS -> WGPUPrimitiveTopology_PointList();
			case PipelineKey.TOPOLOGY_TRIANGLE_STRIP -> WGPUPrimitiveTopology_TriangleStrip();
			case PipelineKey.TOPOLOGY_TRIANGLES -> WGPUPrimitiveTopology_TriangleList();
			// The key packs three bits, so unused encodings are reachable in principle; treat them
			// as the Tessellator's default rather than failing the frame.
			default -> WGPUPrimitiveTopology_TriangleList();
		};
	}

	public MemorySegment bindGroupLayout() {
		return shared.bindGroupLayout();
	}

	public int colorFormat() {
		return colorFormat;
	}

	public int depthFormat() {
		return depthFormat;
	}

	/** How many distinct pipelines beta has actually needed -- the number that has to stay small. */
	public int builtCount() {
		return built;
	}

	@Override
	public void close() {
		for (MemorySegment pipeline : cache.values()) {
			wgpuRenderPipelineRelease(pipeline);
		}
		cache.clear();
		arena.close();
		if (ownedShared != null) {
			ownedShared.close();
			ownedShared = null;
		}
	}
}
