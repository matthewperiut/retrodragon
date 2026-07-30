package com.periut.retrodragon.render;

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

	/** Indexed by {@code PipelineKey.PROGRAM_*}. */
	private static final String[] SHADER_PATHS = {
		"/assets/retrodragon/shaders/wgsl/fixedfunc.wgsl",
		"/assets/retrodragon/shaders/wgsl/terrain.wgsl",
		// PROGRAM_TERRAIN_OPAQUE: the same file, with the alpha test stripped.
		"/assets/retrodragon/shaders/wgsl/terrain.wgsl",
	};

	/** Markers delimiting the region {@link PipelineKey#PROGRAM_TERRAIN_OPAQUE} removes. */
	private static final String ALPHA_TEST_BEGIN = "//@ALPHA_TEST_BEGIN";
	private static final String ALPHA_TEST_END = "//@ALPHA_TEST_END";

	private final WebGPUContext ctx;
	/** Holds every descriptor Dawn keeps a pointer into: shader source, layouts, pipeline specs. */
	private final Arena arena;
	private final Map<Long, MemorySegment> cache = new HashMap<>();
	private final MemorySegment[] shaders;
	private final MemorySegment bindGroupLayout;
	private final MemorySegment pipelineLayout;
	private final int colorFormat;
	private final int depthFormat;
	private final boolean compactTerrain;
	private int built;

	private FixedFunctionPipelines(WebGPUContext ctx, Arena arena, MemorySegment[] shaders,
			MemorySegment bindGroupLayout, MemorySegment pipelineLayout,
			int colorFormat, int depthFormat, boolean compactTerrain) {
		this.ctx = ctx;
		this.arena = arena;
		this.shaders = shaders;
		this.bindGroupLayout = bindGroupLayout;
		this.pipelineLayout = pipelineLayout;
		this.colorFormat = colorFormat;
		this.depthFormat = depthFormat;
		this.compactTerrain = compactTerrain;
	}

	/**
	 * @param depthFormat 0 for a colour-only target; must otherwise match the pass's depth attachment
	 */
	public static FixedFunctionPipelines create(WebGPUContext ctx, int colorFormat, int depthFormat) {
		return create(ctx, colorFormat, depthFormat, TerrainVertex.compact());
	}

	/**
	 * The terrain layout is normally whatever {@link TerrainVertex#COMPACT} says, because the mesher
	 * and the pipeline have to agree and both read that one flag. The bench overrides it so it can
	 * stand both layouts up in one process and measure them against each other, which is the only
	 * way the comparison is worth anything.
	 */
	public static FixedFunctionPipelines create(WebGPUContext ctx, int colorFormat, int depthFormat,
			boolean compactTerrain) {
		Arena arena = Arena.ofShared();
		try {
			MemorySegment[] shaders = new MemorySegment[SHADER_PATHS.length];
			for (int program = 0; program < SHADER_PATHS.length; program++) {
				shaders[program] = Shaders.compile(ctx, arena,
					SHADER_PATHS[program], program(program));
				if (shaders[program].equals(MemorySegment.NULL)) {
					throw new IllegalStateException(SHADER_PATHS[program] + " failed to compile");
				}
			}
			MemorySegment bindGroupLayout =
				Bindings.fixedFunctionLayout(ctx, arena, GlState.UNIFORM_BYTES);
			MemorySegment pipelineLayout =
				Bindings.pipelineLayout(ctx, arena, "retrodragon-fixedfunc", bindGroupLayout);
			return new FixedFunctionPipelines(ctx, arena, shaders, bindGroupLayout, pipelineLayout,
				colorFormat, depthFormat, compactTerrain);
		} catch (RuntimeException e) {
			arena.close();
			throw e;
		}
	}

	/**
	 * The WGSL for one program, which for the opaque terrain variant is the terrain source with the
	 * alpha-test region cut out.
	 *
	 * <p>Textual, and deliberately so: the point is that the compiled shader must not CONTAIN a
	 * {@code discard}, because that is what makes a driver give up early depth testing. A uniform
	 * that skips the discard at runtime does not help -- the pipeline is already compiled by then.
	 *
	 * <p>Throws if the markers are missing rather than silently compiling a second identical shader,
	 * which would look like the optimisation was measured and found worthless.
	 */
	private static String program(int index) {
		String source = source(SHADER_PATHS[index]);
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
		int program = Math.min(PipelineKey.program(key), shaders.length - 1);
		// Both terrain programs read the same buffers, so both take the terrain vertex layout.
		boolean terrain = program == PipelineKey.PROGRAM_TERRAIN
			|| program == PipelineKey.PROGRAM_TERRAIN_OPAQUE;
		PipelineSpec spec = new PipelineSpec()
			.label((terrain ? "terrain-" : "fixedfunc-") + Long.toHexString(key))
			.shader(shaders[program])
			// Terrain is the one program whose vertices this project packs itself, so it is the one
			// that can afford a layout narrower than beta's. Everything else reads bytes the
			// Tessellator wrote and must describe them exactly as they are.
			.vertexLayout(
				terrain ? TerrainVertex.stride(compactTerrain) : VERTEX_STRIDE,
				terrain
					? (compactTerrain ? TERRAIN_COMPACT_ATTRIBUTES : TERRAIN_LEGACY_ATTRIBUTES)
					: VERTEX_ATTRIBUTES)
			.color(colorFormat)
			.layout(pipelineLayout);

		spec.topology = topology(PipelineKey.topology(key));
		spec.cullMode = cull(PipelineKey.cull(key));
		spec.blend = PipelineKey.blend(key);
		if (spec.blend) {
			spec.blendSrcColor = blendFactor(PipelineKey.blendSrc(key));
			spec.blendDstColor = blendFactor(PipelineKey.blendDst(key));
			// Alpha uses the same factors GL would: beta never calls glBlendFuncSeparate, so the
			// separate-alpha path exists only to keep the destination alpha sane on a surface that
			// has one.
			spec.blendSrcAlpha = spec.blendSrcColor;
			spec.blendDstAlpha = spec.blendDstColor;
		}
		if (depthFormat != 0) {
			spec.depth(depthFormat, PipelineKey.depthTest(key), PipelineKey.depthWrite(key),
				compare(PipelineKey.depthFunc(key)));
			// glPolygonOffset. Negative in beta's only use, which pulls the block-breaking overlay
			// TOWARDS the viewer so it wins the depth compare against the face it sits on.
			spec.polygonOffset(PipelineKey.offsetUnits(key), PipelineKey.offsetSlope(key));
		}
		return Pipelines.create(ctx, arena, spec);
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
		return bindGroupLayout;
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
		if (!pipelineLayout.equals(MemorySegment.NULL)) wgpuPipelineLayoutRelease(pipelineLayout);
		if (!bindGroupLayout.equals(MemorySegment.NULL)) wgpuBindGroupLayoutRelease(bindGroupLayout);
		for (MemorySegment shader : shaders) {
			if (shader != null && !shader.equals(MemorySegment.NULL)) {
				wgpuShaderModuleRelease(shader);
			}
		}
		arena.close();
	}
}
