package com.periut.retrodragon.api;

import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.PipelineSpec;
import com.periut.retrodragon.gpu.Pipelines;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * A full-screen fragment pass: tonemap, bloom, god rays, whatever the extension's chain needs.
 *
 * <p>Everything it reads comes from {@link ShaderResources} -- the uniform block and the texture
 * slots -- so running one is: point a texture slot at the input, then {@link #run}. There is no
 * vertex buffer and no per-draw state, which is why the pass can be this small.
 *
 * <h2>Three vertices, not four</h2>
 *
 * The geometry is a single oversized triangle covering the viewport, generated from
 * {@code vertex_index} with no buffer at all. A quad would be two triangles meeting along the
 * diagonal, and every pixel on that seam is rasterised by both -- the classic cause of a faint line
 * down the middle of a post-processed image. One triangle has no interior edge.
 */
public final class PostPass implements AutoCloseable {
	/**
	 * The vertex stage, prepended to the extension's fragment source.
	 *
	 * <p>Supplied rather than required so a chain of ten passes writes ten fragment functions and no
	 * vertex ones. {@code uv} runs 0..1 with the origin at the TOP LEFT, matching WebGPU's framebuffer
	 * convention -- so a pass that samples its input at {@code in.uv} gets the pixel it is writing.
	 */
	public static final String WGSL_VERTEX = """
		struct PostVertex {
		    @builtin(position) position : vec4<f32>,
		    @location(0) uv : vec2<f32>,
		};

		@vertex
		fn vs_main(@builtin(vertex_index) index : u32) -> PostVertex {
		    // (-1,-1), (3,-1), (-1,3): one triangle that covers the viewport with no interior edge.
		    let x = f32(i32(index) / 2) * 4.0 - 1.0;
		    let y = f32(i32(index) & 1) * 4.0 - 1.0;
		    var out : PostVertex;
		    out.position = vec4<f32>(x, y, 0.0, 1.0);
		    // y flipped: clip space is +y up, the framebuffer is +y down.
		    out.uv = vec2<f32>(x * 0.5 + 0.5, 1.0 - (y * 0.5 + 0.5));
		    return out;
		}
		""";

	private final WebGPUContext ctx;
	private final Arena arena = Arena.ofShared();
	private final ShaderResources resources;
	private final MemorySegment module;
	private final MemorySegment pipeline;
	private final String name;

	/**
	 * @param fragment the WGSL fragment stage. {@link #WGSL_VERTEX} and
	 *                 {@link ShaderResources#WGSL_PRELUDE} are prepended, so the source needs only
	 *                 its own uniform struct at {@code @group(1) @binding(0)} and an {@code fs_main}
	 *                 taking a {@code PostVertex}.
	 * @param format   the format of the texture this pass writes; it must match the view passed to
	 *                 {@link #run}, because a pipeline bakes its target format in
	 */
	public PostPass(ShaderResources resources, String name, String uniformBlock, String fragment,
			int format) {
		this.ctx = resources.context();
		this.resources = resources;
		this.name = name;
		String source = uniformBlock + "\n" + ShaderResources.WGSL_PRELUDE + "\n"
			+ WGSL_VERTEX + "\n" + fragment;
		this.module = Shaders.compile(ctx, arena, name, source);
		if (module.equals(MemorySegment.NULL)) {
			arena.close();
			throw new IllegalStateException("post pass '" + name + "' failed to compile");
		}
		MemorySegment layout = Bindings.pipelineLayout(ctx, arena, name,
			new MemorySegment[] { resources.emptyLayout(), resources.layout() });
		PipelineSpec spec = new PipelineSpec()
			.label(name)
			.shader(module)
			.color(format)
			.layout(layout);
		// No depth: a full-screen pass writes every pixel and reads depth as a texture if it wants
		// it. Attaching one would force the pass to declare a depth attachment it has no use for.
		this.pipeline = Pipelines.create(ctx, arena, spec);
		if (pipeline.equals(MemorySegment.NULL)) {
			arena.close();
			throw new IllegalStateException("post pass '" + name + "' pipeline creation failed");
		}
	}

	/**
	 * Records the pass into the frame, writing {@code output}.
	 *
	 * <p>Does not clear: a full-screen triangle covers every pixel, so a clear would be one extra
	 * write of the whole attachment for a value about to be overwritten. A pass that blends must
	 * therefore be given a target that already holds what it means to blend with.
	 */
	public void run(ShaderFrame frame, MemorySegment output) {
		record(frame, output, resources.group());
	}

	/**
	 * As {@link #run}, with the rendered world bound at
	 * {@link ShaderResources#WORLD_TARGET_SLOT}.
	 *
	 * <p>For a pass that reads the finished world -- a tonemap, a bloom, anything at the end of the
	 * frame. It is a different bind group and not a different texture set, because the world target
	 * is the world pass's own attachment: binding it in the group the world draws with would have it
	 * readable and writable at once, which costs the entire frame.
	 */
	public void runComposite(ShaderFrame frame, MemorySegment output) {
		record(frame, output, resources.compositeGroup());
	}

	private void record(ShaderFrame frame, MemorySegment output, MemorySegment group) {
		MemorySegment pass = frame.frame().beginPass(frame.arena(), output,
			NO_AUX, false, 0.0F, 0.0F, 0.0F, 1.0F, MemorySegment.NULL, false);
		wgpuRenderPassEncoderSetPipeline(pass, pipeline);
		wgpuRenderPassEncoderSetBindGroup(pass, 0, resources.emptyGroup(), 0, MemorySegment.NULL);
		wgpuRenderPassEncoderSetBindGroup(pass, 1, group, 0, MemorySegment.NULL);
		wgpuRenderPassEncoderDraw(pass, 3, 1, 0, 0);
		frame.frame().endPass();
	}

	private static final MemorySegment[] NO_AUX = new MemorySegment[0];

	public String name() {
		return name;
	}

	@Override
	public void close() {
		if (!pipeline.equals(MemorySegment.NULL)) {
			wgpuRenderPipelineRelease(pipeline);
		}
		if (!module.equals(MemorySegment.NULL)) {
			wgpuShaderModuleRelease(module);
		}
		arena.close();
	}
}
