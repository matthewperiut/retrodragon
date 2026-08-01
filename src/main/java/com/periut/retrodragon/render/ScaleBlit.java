package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.PipelineSpec;
import com.periut.retrodragon.gpu.Pipelines;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUSamplerDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Draws the scaled world target onto the swapchain with the filter {@link RenderScale} names. The
 * WebGPU counterpart of {@link ScaleResolve}.
 *
 * <p>A full-screen draw rather than {@code Blit}'s texture copy, because the two textures are
 * deliberately NOT the same size any more and a copy cannot resample. Three vertices, no vertex
 * buffer: the triangle is generated from {@code vertex_index} in the shader.
 *
 * <h2>One pipeline, one shader</h2>
 *
 * The filter is a uniform, not a specialisation. A full-screen pass is bandwidth-bound and the branch
 * is uniform across every invocation in the draw, so four pipelines would buy nothing and cost four
 * of everything plus a cache to choose between them.
 *
 * <h2>Two samplers, though</h2>
 *
 * The point filter genuinely needs {@code Nearest} and the rest genuinely need {@code Linear}. The
 * shader snaps to texel centres for the point path so it is correct either way, but a linear sampler
 * on a point resample still lets a tap that lands a hair off centre blend two texels, and on 16x16
 * pixel art that is exactly the artefact this renderer exists to avoid.
 */
public final class ScaleBlit implements AutoCloseable {

	/** vec4 srcSize + vec4 control + vec4 rect. */
	private static final long UNIFORM_BYTES = 48L;

	private static final float FSR_SHARPNESS =
		Float.parseFloat(System.getProperty("retrodragon.fsrSharpness", "0.25"));

	private final WebGPUContext ctx;
	private final Arena arena;
	private final MemorySegment pipeline;
	private final MemorySegment groupLayout;
	private final MemorySegment nearestSampler;
	private final MemorySegment linearSampler;
	private final GpuBuffer uniforms;
	private final int format;

	private ScaleBlit(WebGPUContext ctx, Arena arena, MemorySegment pipeline,
			MemorySegment groupLayout, MemorySegment nearestSampler, MemorySegment linearSampler,
			GpuBuffer uniforms, int format) {
		this.ctx = ctx;
		this.arena = arena;
		this.pipeline = pipeline;
		this.groupLayout = groupLayout;
		this.nearestSampler = nearestSampler;
		this.linearSampler = linearSampler;
		this.uniforms = uniforms;
		this.format = format;
	}

	/**
	 * @param targetFormat the swapchain's format; the pipeline is built for it and a change means a
	 *     new instance, which {@link #matches} lets the caller detect
	 */
	public static ScaleBlit create(WebGPUContext ctx, Arena arena, int targetFormat) {
		MemorySegment module = Shaders.compile(ctx, arena, "retrodragon-scale", readWgsl());

		MemorySegment groupLayout = Bindings.fixedFunctionLayout(ctx, arena, UNIFORM_BYTES);
		MemorySegment layout = Bindings.pipelineLayout(ctx, arena, "retrodragon-scale", groupLayout);

		PipelineSpec spec = new PipelineSpec();
		spec.label = "retrodragon-scale";
		spec.shader = module;
		spec.colorFormat = targetFormat;
		spec.pipelineLayout = layout;
		// No vertex buffer at all: the fullscreen triangle comes from vertex_index.
		spec.vertexStride = 0;
		spec.attributes = new int[0][];
		// No depth. The resolve writes every pixel it covers and nothing is drawn behind it.
		spec.depthTest = false;
		spec.depthWrite = false;
		spec.depthFormat = 0;
		spec.blend = false;
		spec.cullMode = WGPUCullMode_None();

		MemorySegment pipeline = Pipelines.create(ctx, arena, spec);

		GpuBuffer uniforms = GpuBuffer.sized(ctx,
			Flags.BUFFER_USAGE_UNIFORM | Flags.BUFFER_USAGE_COPY_DST,
			"retrodragon-scale-uniforms", UNIFORM_BYTES);

		return new ScaleBlit(ctx, arena, pipeline, groupLayout,
			sampler(ctx, false), sampler(ctx, true), uniforms, targetFormat);
	}

	public boolean matches(int targetFormat) {
		return format == targetFormat;
	}

	private static final String WGSL_PATH = "/assets/retrodragon/shaders/wgsl/scale.wgsl";

	private static String readWgsl() {
		try (java.io.InputStream in = ScaleBlit.class.getResourceAsStream(WGSL_PATH)) {
			if (in == null) {
				throw new IllegalStateException("missing shader resource " + WGSL_PATH);
			}
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("could not read " + WGSL_PATH, e);
		}
	}

	private static MemorySegment sampler(WebGPUContext ctx, boolean linear) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUSamplerDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUSamplerDescriptor.label(desc),
				linear ? "retrodragon-scale-linear" : "retrodragon-scale-nearest");
			// Clamp, not repeat. Every filter here taps outside the source at the frame's border, and
			// repeating wraps the opposite edge of the world into it -- a bright sky texel smeared
			// along the bottom of the screen.
			int clamp = WGPUAddressMode_ClampToEdge();
			WGPUSamplerDescriptor.addressModeU(desc, clamp);
			WGPUSamplerDescriptor.addressModeV(desc, clamp);
			WGPUSamplerDescriptor.addressModeW(desc, clamp);
			int mode = linear ? WGPUFilterMode_Linear() : WGPUFilterMode_Nearest();
			WGPUSamplerDescriptor.magFilter(desc, mode);
			WGPUSamplerDescriptor.minFilter(desc, mode);
			WGPUSamplerDescriptor.mipmapFilter(desc, WGPUMipmapFilterMode_Nearest());
			WGPUSamplerDescriptor.lodMinClamp(desc, 0.0F);
			WGPUSamplerDescriptor.lodMaxClamp(desc, 0.0F);
			WGPUSamplerDescriptor.maxAnisotropy(desc, (short) 1);
			MemorySegment sampler = wgpuDeviceCreateSampler(ctx.device(), desc);
			if (sampler.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("scale sampler creation failed");
			}
			return sampler;
		}
	}

	/**
	 * Opens a pass on {@code target} and draws {@code source} into it, resampled.
	 *
	 * @param sourceView a view of the scaled world target
	 * @param srcW source size, which is what the filters need to find texel centres
	 * @param dstW destination size, used only by integer snapping
	 */
	public void draw(Frame frame, MemorySegment target, MemorySegment sourceView,
			int srcW, int srcH, int dstW, int dstH, String filter) {
		if (target.equals(MemorySegment.NULL) || sourceView.equals(MemorySegment.NULL)) {
			return;
		}
		boolean point = RenderScale.NEAREST.equals(filter) || RenderScale.INTEGER.equals(filter);
		float mode = switch (filter) {
			case RenderScale.BILINEAR -> 1.0F;
			case RenderScale.BICUBIC -> 2.0F;
			case RenderScale.FSR1 -> 3.0F;
			default -> 0.0F;
		};

		// The destination rectangle, in NDC. Integer snapping floors the ratio and letterboxes the
		// remainder; everything else fills the screen. Done in the RECT rather than in the sampler
		// because that is what makes it exact -- every source texel then covers the same whole number
		// of destination pixels, which no amount of filtering can arrange for itself.
		float halfX = 1.0F;
		float halfY = 1.0F;
		boolean letterboxed = false;
		if (RenderScale.INTEGER.equals(filter)) {
			int ratio = Math.max(1, Math.min(dstW / Math.max(1, srcW), dstH / Math.max(1, srcH)));
			halfX = (float) (srcW * ratio) / dstW;
			halfY = (float) (srcH * ratio) / dstH;
			letterboxed = halfX < 1.0F || halfY < 1.0F;
		}

		ByteBuffer data = ByteBuffer.allocateDirect((int) UNIFORM_BYTES).order(ByteOrder.nativeOrder());
		data.putFloat(srcW).putFloat(srcH).putFloat(1.0F / srcW).putFloat(1.0F / srcH);
		data.putFloat(mode).putFloat(FSR_SHARPNESS).putFloat(0.0F).putFloat(0.0F);
		// Centred: offset stays 0 and only the half-extent shrinks, so the border splits evenly.
		data.putFloat(0.0F).putFloat(0.0F).putFloat(halfX).putFloat(halfY);
		data.flip();
		uniforms.writeAt(0, data, (int) UNIFORM_BYTES);

		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment group = Bindings.fixedFunctionGroup(ctx, tmp, groupLayout,
				uniforms.handle(), UNIFORM_BYTES, point ? nearestSampler : linearSampler, sourceView);

			// Cleared only when letterboxing leaves pixels this draw will not cover. Otherwise the
			// border keeps whatever the swapchain image held, which on a recycled drawable is an
			// older frame -- a smear along the edges that looks like a compositing bug.
			// ONE dynamic offset, of zero. Bindings.fixedFunctionLayout declares its uniform buffer as
			// dynamically offset -- that is how the per-draw path indexes one big buffer -- and the
			// count passed here has to match the layout's, not the number this pass actually varies.
			// Passing none is a validation error that invalidates the whole command buffer, so the
			// frame is dropped at submit and the screen keeps the last good one: a black or frozen
			// world rather than anything that points at a bind group.
			MemorySegment dynamicOffsets = tmp.allocate(ValueLayout.JAVA_INT, 1);
			dynamicOffsets.setAtIndex(ValueLayout.JAVA_INT, 0, 0);

			MemorySegment pass = frame.beginPass(tmp, target, letterboxed,
				0.0F, 0.0F, 0.0F, 1.0F, MemorySegment.NULL, false);
			wgpuRenderPassEncoderSetPipeline(pass, pipeline);
			wgpuRenderPassEncoderSetBindGroup(pass, 0, group, 1, dynamicOffsets);
			wgpuRenderPassEncoderDraw(pass, 3, 1, 0, 0);
			frame.endPass();
			wgpuBindGroupRelease(group);
		}
	}

	@Override
	public void close() {
		if (uniforms != null) {
			uniforms.close();
		}
		if (!nearestSampler.equals(MemorySegment.NULL)) {
			wgpuSamplerRelease(nearestSampler);
		}
		if (!linearSampler.equals(MemorySegment.NULL)) {
			wgpuSamplerRelease(linearSampler);
		}
		if (!pipeline.equals(MemorySegment.NULL)) {
			wgpuRenderPipelineRelease(pipeline);
		}
		RetroDragon.detail("scale blit released");
	}
}
