package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuTexture;
import com.periut.retrodragon.gpu.PipelineSpec;
import com.periut.retrodragon.gpu.Pipelines;
import com.periut.retrodragon.gpu.RenderTarget;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUOrigin3D;
import com.periut.webgpu.WGPUSamplerDescriptor;
import com.periut.webgpu.WGPUTexelCopyTextureInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * {@code glCopyTexSubImage2D}: a rectangle of the colour attachment, into a texture the game names.
 *
 * <h2>Why this is a draw and not a copy</h2>
 *
 * {@link com.periut.retrodragon.gpu.Blit} would be the obvious tool and cannot be used, for two
 * independent reasons:
 *
 * <ul>
 *   <li><b>Format.</b> {@code copyTextureToTexture} requires identical formats. The swapchain is
 *       BGRA8Unorm on Metal and every texture {@link TextureStore} hands out is RGBA8Unorm. Sampling
 *       converts; copying refuses.</li>
 *   <li><b>Orientation.</b> GL's window origin is bottom-left, so the first row of the destination
 *       image is the BOTTOM row of the region read. A copy preserves row order, so the image would
 *       arrive upside down -- which is not a crash or a validation error, just a panorama rendered
 *       on its head.</li>
 * </ul>
 *
 * <p>So the region is staged into a texture of its own -- a copy, which the swapchain's
 * {@code COPY_SRC} usage allows and its lack of {@code TEXTURE_BINDING} makes necessary -- and then
 * drawn into the destination through {@code copytex.wgsl}, which does the flip. The destination
 * rectangle is the pass viewport, so a sub-image copy touches nothing else, and the pass LOADS
 * rather than clears, so the rest of the texture survives.
 *
 * <p>Recorded on the frame's own encoder, between passes, at the point in the frame the game made
 * the call: everything drawn before it is in the image and nothing drawn after it is. That ordering
 * is the entire content of the call, and it is why this cannot simply run at present time.
 */
public final class FramebufferCopy implements AutoCloseable {
	private static final String WGSL_PATH = "/assets/retrodragon/shaders/wgsl/copytex.wgsl";

	private final WebGPUContext ctx;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment pipeline;
	private final MemorySegment groupLayout;
	private final MemorySegment sampler;

	/** The region, in the SOURCE's format, so it can be sampled. Reallocated when the size changes. */
	private RenderTarget staging;
	private int stagingFormat;

	public FramebufferCopy(WebGPUContext ctx) {
		this.ctx = ctx;
		MemorySegment module = Shaders.compile(ctx, arena, "retrodragon-copytex", readWgsl());
		this.groupLayout = Bindings.samplerTextureLayout(ctx, arena, "retrodragon-copytex");

		PipelineSpec spec = new PipelineSpec();
		spec.label = "retrodragon-copytex";
		spec.shader = module;
		// The destination is always a TextureStore texture, and those are RGBA8Unorm throughout.
		spec.colorFormat = WGPUTextureFormat_RGBA8Unorm();
		spec.pipelineLayout = Bindings.pipelineLayout(ctx, arena, "retrodragon-copytex", groupLayout);
		spec.vertexStride = 0;
		spec.attributes = new int[0][];
		spec.depthTest = false;
		spec.depthWrite = false;
		spec.depthFormat = 0;
		spec.blend = false;
		spec.cullMode = WGPUCullMode_None();

		this.pipeline = Pipelines.create(ctx, arena, spec);
		this.sampler = nearestClampSampler(ctx);
	}

	private static String readWgsl() {
		try (java.io.InputStream in = FramebufferCopy.class.getResourceAsStream(WGSL_PATH)) {
			if (in == null) {
				throw new IllegalStateException("missing shader resource " + WGSL_PATH);
			}
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("could not read " + WGSL_PATH, e);
		}
	}

	/** Nearest and clamped: this is a 1:1 transfer, so there is nothing to filter and nothing to wrap. */
	private static MemorySegment nearestClampSampler(WebGPUContext ctx) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUSamplerDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUSamplerDescriptor.label(desc), "retrodragon-copytex");
			int clamp = WGPUAddressMode_ClampToEdge();
			WGPUSamplerDescriptor.addressModeU(desc, clamp);
			WGPUSamplerDescriptor.addressModeV(desc, clamp);
			WGPUSamplerDescriptor.addressModeW(desc, clamp);
			WGPUSamplerDescriptor.magFilter(desc, WGPUFilterMode_Nearest());
			WGPUSamplerDescriptor.minFilter(desc, WGPUFilterMode_Nearest());
			WGPUSamplerDescriptor.mipmapFilter(desc, WGPUMipmapFilterMode_Nearest());
			WGPUSamplerDescriptor.lodMinClamp(desc, 0.0F);
			WGPUSamplerDescriptor.lodMaxClamp(desc, 0.0F);
			WGPUSamplerDescriptor.maxAnisotropy(desc, (short) 1);
			MemorySegment sampler = wgpuDeviceCreateSampler(ctx.device(), desc);
			if (sampler.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("copytex sampler creation failed");
			}
			return sampler;
		}
	}

	/**
	 * Records one copy. No render pass may be open on {@code frame}.
	 *
	 * <p>Coordinates arrive exactly as GL stated them: {@code srcX}/{@code srcY} from the BOTTOM-left
	 * of the framebuffer, {@code dstX}/{@code dstY} in the destination image's own rows, which under
	 * this shim are WebGPU's rows -- an upload's first row is row 0 both here and in GL.
	 *
	 * @param sourceTexture the colour attachment being drawn into, which must carry {@code COPY_SRC}
	 * @param sourceFormat  its format, which the staging texture has to match to be copied into
	 */
	public void copy(Frame frame, MemorySegment sourceTexture, int sourceFormat,
			int framebufferWidth, int framebufferHeight, GpuTexture destination,
			int dstX, int dstY, int srcX, int srcY, int width, int height) {
		if (frame == null || destination == null || !destination.renderable()
				|| sourceTexture.equals(MemorySegment.NULL)
				|| width <= 0 || height <= 0) {
			return;
		}

		// Clamp to both rectangles, in GL's own coordinates -- source row srcY+k lands in destination
		// row dstY+k, so one adjustment serves both. GL allows a region hanging off the framebuffer
		// (the pixels are simply undefined); Dawn fails validation for one, and a failed command
		// buffer costs the whole frame rather than this copy.
		int trimLeft = Math.max(0, Math.max(-srcX, -dstX));
		srcX += trimLeft;
		dstX += trimLeft;
		width -= trimLeft;
		int trimBottom = Math.max(0, Math.max(-srcY, -dstY));
		srcY += trimBottom;
		dstY += trimBottom;
		height -= trimBottom;
		width = Math.min(width, Math.min(framebufferWidth - srcX, destination.width() - dstX));
		height = Math.min(height, Math.min(framebufferHeight - srcY, destination.height() - dstY));
		if (width <= 0 || height <= 0) {
			return;
		}

		// GL's y is from the bottom; the copy below wants WebGPU's, from the top.
		int sourceTop = framebufferHeight - srcY - height;

		if (!ensureStaging(width, height, sourceFormat)) {
			return;
		}

		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment from = WGPUTexelCopyTextureInfo.allocate(tmp);
			WGPUTexelCopyTextureInfo.texture(from, sourceTexture);
			WGPUTexelCopyTextureInfo.mipLevel(from, 0);
			WGPUTexelCopyTextureInfo.aspect(from, WGPUTextureAspect_All());
			MemorySegment origin = WGPUTexelCopyTextureInfo.origin(from);
			WGPUOrigin3D.x(origin, srcX);
			WGPUOrigin3D.y(origin, sourceTop);
			WGPUOrigin3D.z(origin, 0);

			MemorySegment to = WGPUTexelCopyTextureInfo.allocate(tmp);
			WGPUTexelCopyTextureInfo.texture(to, staging.handle());
			WGPUTexelCopyTextureInfo.mipLevel(to, 0);
			WGPUTexelCopyTextureInfo.aspect(to, WGPUTextureAspect_All());
			MemorySegment target = WGPUTexelCopyTextureInfo.origin(to);
			WGPUOrigin3D.x(target, 0);
			WGPUOrigin3D.y(target, 0);
			WGPUOrigin3D.z(target, 0);

			MemorySegment extent = WGPUExtent3D.allocate(tmp);
			WGPUExtent3D.width(extent, width);
			WGPUExtent3D.height(extent, height);
			WGPUExtent3D.depthOrArrayLayers(extent, 1);

			wgpuCommandEncoderCopyTextureToTexture(frame.encoder(), from, to, extent);

			MemorySegment group = Bindings.samplerTextureGroup(ctx, tmp, groupLayout,
				"retrodragon-copytex", sampler, staging.view());
			// LOAD, not clear: this is glCopyTexSubImage2D, and everything outside the rectangle keeps
			// whatever the game last uploaded there.
			MemorySegment pass = frame.beginPass(tmp, destination.attachmentView(), false,
				0.0F, 0.0F, 0.0F, 1.0F, MemorySegment.NULL, false);
			// Viewport AND scissor, and the scissor is the one that makes this a SUB-image copy: a
			// viewport is only a transform, so the half of the oversized triangle that falls outside
			// it still rasterises anywhere inside the texture. The scissor is what stops it.
			wgpuRenderPassEncoderSetViewport(pass, dstX, dstY, width, height, 0.0F, 1.0F);
			wgpuRenderPassEncoderSetScissorRect(pass, dstX, dstY, width, height);
			wgpuRenderPassEncoderSetPipeline(pass, pipeline);
			wgpuRenderPassEncoderSetBindGroup(pass, 0, group, 0, MemorySegment.NULL);
			wgpuRenderPassEncoderDraw(pass, 3, 1, 0, 0);
			frame.endPass();
			wgpuBindGroupRelease(group);
		}
	}

	/**
	 * Makes the staging texture match the region about to be copied.
	 *
	 * <p>Sized to the region exactly rather than to the framebuffer: the caller that motivates this
	 * copies a 256-square corner of the screen eight times a frame, and a full-window staging texture
	 * would move an order of magnitude more bytes for the same result.
	 *
	 * @return false if the texture could not be created, in which case the copy is skipped -- a
	 *     missing rectangle is survivable, a half-recorded frame is not
	 */
	private boolean ensureStaging(int width, int height, int format) {
		if (staging != null && staging.width() == width && staging.height() == height
				&& stagingFormat == format) {
			return true;
		}
		try {
			// The GPU may still be reading the old one: it was written by a frame that has been
			// submitted, and nothing here waits for it.
			ctx.drain(2000);
			if (staging != null) {
				staging.close();
				staging = null;
			}
			try (Arena tmp = Arena.ofConfined()) {
				staging = RenderTarget.create(ctx, tmp, width, height, format);
			}
			stagingFormat = format;
			return true;
		} catch (RuntimeException e) {
			RetroDragon.LOGGER.error("glCopyTexSubImage2D: could not allocate a {}x{} staging texture;"
				+ " the copy is skipped", width, height, e);
			staging = null;
			return false;
		}
	}

	@Override
	public void close() {
		if (staging != null) {
			staging.close();
			staging = null;
		}
		if (!sampler.equals(MemorySegment.NULL)) {
			wgpuSamplerRelease(sampler);
		}
		if (!pipeline.equals(MemorySegment.NULL)) {
			wgpuRenderPipelineRelease(pipeline);
		}
		arena.close();
	}
}
