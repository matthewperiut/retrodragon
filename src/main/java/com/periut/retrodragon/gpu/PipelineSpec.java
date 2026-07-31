package com.periut.retrodragon.gpu;

import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Everything a render pipeline bakes in, in one mutable holder.
 *
 * <p>Mutable and field-based rather than a record with a twenty-argument constructor: the caller
 * fills in the handful of fields that differ from fixed-function defaults and leaves the rest, which
 * is how the state actually arrives -- beta changes one thing between draws, not all of it.
 *
 * <p>Defaults are GL's own defaults, so a spec straight out of the constructor draws the way an
 * untouched GL context would: no blending, depth test off, no culling, triangles.
 */
public final class PipelineSpec {
	public String label = "retrodragon";
	public MemorySegment shader = MemorySegment.NULL;
	public String vertexEntry = "vs_main";
	public String fragmentEntry = "fs_main";

	/** Bytes between vertices; 0 means the shader generates its own positions. */
	public int vertexStride;
	/** {@code {shaderLocation, offset, WGPUVertexFormat_*}} triples. */
	public int[][] attributes = new int[0][];

	public int colorFormat = WGPUTextureFormat_BGRA8Unorm();

	/**
	 * Additional colour targets, for a pass that writes more than one attachment.
	 *
	 * <p>Empty for the ordinary single-target case, which is everything the engine itself draws.
	 * A shader extension uses it for a G-buffer: the world pass writes its shaded colour to target 0
	 * and material data (emissive, normal, whatever the pack defines) to targets 1..n in the same
	 * draw, which is the only way to get it without rendering the world twice.
	 *
	 * <p>Only target 0 ever blends. A blend equation on an auxiliary attachment would mix material
	 * data with whatever the previous draw left, which is meaningless -- the aux targets carry
	 * per-fragment facts, not light being accumulated.
	 */
	public int[] auxColorFormats = new int[0];

	/**
	 * True for a pass with no colour attachment at all -- a shadow map, which writes only depth.
	 *
	 * <p>The fragment stage is still built, with zero targets. Dropping it entirely would be faster
	 * and is what a shadow map made of solid blocks wants, but this game's leaves, grass and glass
	 * are opaque quads whose shape lives in the alpha channel: with nothing to discard them, every
	 * tree casts the shadow of a box. A shader extension that needs a colour channel in its shadow
	 * map -- a translucent-shadow pack -- leaves this false and declares a target instead.
	 */
	public boolean depthOnly;

	/** 0 for a colour-only pipeline. Must match the pass's depth attachment when set. */
	public int depthFormat;
	public boolean depthTest;
	public boolean depthWrite = true;
	public int depthCompare = WGPUCompareFunction_LessEqual();

	/**
	 * GL's {@code glPolygonOffset}, which WebGPU spells as pipeline state.
	 *
	 * <p>Both APIs compute the same thing -- {@code slopeScale * dz + bias * r}, where {@code r} is
	 * the smallest resolvable depth difference -- so {@code depthBias} is GL's units term and
	 * {@code depthBiasSlopeScale} its factor. Used to lift a decal off the surface it is drawn on;
	 * in b1.7.3 that is the block-breaking overlay, which is exactly coplanar with the block face
	 * and tears apart without it.
	 */
	public int depthBias;
	public float depthBiasSlopeScale;
	public float depthBiasClamp;

	public boolean blend;
	public int blendSrcColor = WGPUBlendFactor_SrcAlpha();
	public int blendDstColor = WGPUBlendFactor_OneMinusSrcAlpha();
	public int blendSrcAlpha = WGPUBlendFactor_One();
	public int blendDstAlpha = WGPUBlendFactor_OneMinusSrcAlpha();

	public int cullMode = WGPUCullMode_None();
	/**
	 * CCW, matching GL's default, which beta never changes with {@code glFrontFace}.
	 *
	 * <p>It is tempting to argue this should be CW: facing comes from the triangle's signed area in
	 * framebuffer coordinates, GL's window origin is bottom-left with y up while WebGPU's is
	 * top-left with y down, and that reflection reverses orientation. The argument is wrong in
	 * practice -- Dawn accounts for the flip on Metal, so geometry wound for GL's CCW front face is
	 * still front-facing here under CCW.
	 *
	 * <p><b>Measured, not reasoned.</b> {@code WebGpuDrawTest.winding} draws the same quad twice with
	 * opposite vertex order under back-face culling and asserts which survives; under CCW it is
	 * beta's winding, and the reversed one is culled. Asserting on both halves is what makes that a
	 * measurement instead of a restatement of the assumption -- a test that only checks the expected
	 * quad renders passes under either setting.
	 *
	 * <p>Getting it wrong would cull exactly the faces that should survive, which does not look like
	 * a winding bug: the world appears inside out, with the terrain surface gone and the caves below
	 * it visible. The GUI would look fine throughout, because it draws with culling disabled.
	 */
	public int frontFace = WGPUFrontFace_CCW();
	public int topology = WGPUPrimitiveTopology_TriangleList();

	public int sampleCount = 1;
	public boolean alphaToCoverage;

	/** Explicit layout so one bind group serves every pipeline; NULL asks Dawn to infer one. */
	public MemorySegment pipelineLayout = MemorySegment.NULL;

	public PipelineSpec label(String label) {
		this.label = label;
		return this;
	}

	public PipelineSpec shader(MemorySegment shader) {
		this.shader = shader;
		return this;
	}

	public PipelineSpec vertexLayout(int stride, int[][] attributes) {
		this.vertexStride = stride;
		this.attributes = attributes;
		return this;
	}

	public PipelineSpec color(int format) {
		this.colorFormat = format;
		return this;
	}

	/** Colour target 0 plus {@code aux} further attachments; see {@link #auxColorFormats}. */
	public PipelineSpec color(int format, int[] aux) {
		this.colorFormat = format;
		this.auxColorFormats = aux == null ? new int[0] : aux;
		return this;
	}

	/** No colour attachment; see {@link #depthOnly}. */
	public PipelineSpec depthOnly() {
		this.depthOnly = true;
		return this;
	}

	public PipelineSpec depth(int format, boolean test, boolean write, int compare) {
		this.depthFormat = format;
		this.depthTest = test;
		this.depthWrite = write;
		this.depthCompare = compare;
		return this;
	}

	/** See {@link #depthBias}. Units and slope are GL's glPolygonOffset arguments, in that order. */
	public PipelineSpec polygonOffset(int units, float slopeScale) {
		this.depthBias = units;
		this.depthBiasSlopeScale = slopeScale;
		return this;
	}

	public PipelineSpec layout(MemorySegment pipelineLayout) {
		this.pipelineLayout = pipelineLayout;
		return this;
	}
}
