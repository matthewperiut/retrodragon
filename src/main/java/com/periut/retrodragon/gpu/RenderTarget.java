package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUColor;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPURenderPassColorAttachment;
import com.periut.webgpu.WGPURenderPassDescriptor;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * An offscreen colour target and the render pass that draws into it.
 *
 * <p>Exists so the draw chain can be exercised with no window at all -- which is how the pipeline
 * and shader permutations get tested in CI, and how phase 1 proves rasterisation works before
 * surface handling (phase 2) introduces swapchains, resize and present.
 *
 * <p>The same render-pass plumbing serves a swapchain texture later; only where the view comes from
 * changes, so nothing here is throwaway.
 */
public final class RenderTarget implements AutoCloseable {
	/** RGBA8Unorm: renderable everywhere and copyable straight to a buffer for readback. */

	private final MemorySegment texture;
	private final MemorySegment view;
	private final int width;
	private final int height;
	private final int format;

	private RenderTarget(MemorySegment texture, MemorySegment view, int w, int h, int format) {
		this.texture = texture;
		this.view = view;
		this.width = w;
		this.height = h;
		this.format = format;
	}

	public static RenderTarget create(WebGPUContext ctx, Arena arena, int width, int height) {
		return create(ctx, arena, width, height, WGPUTextureFormat_RGBA8Unorm());
	}

	/**
	 * @param format must MATCH the destination when this target is blitted to a swapchain -- a
	 *               texture-to-texture copy requires identical formats, and the swapchain is BGRA on
	 *               Metal while the offscreen default is RGBA
	 */
	public static RenderTarget create(WebGPUContext ctx, Arena arena, int width, int height,
			int format) {

		MemorySegment desc = WGPUTextureDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUTextureDescriptor.label(desc), "retrodragon-offscreen");
		// CopySrc as well as RenderAttachment: readback is how a headless test asserts what was
		// drawn, and usage cannot be widened after creation.
		// CopyDst as well: this doubles as the "last presented frame" that glReadPixels reads, which
		// means the swapchain image gets copied INTO it once a frame. Usage cannot be widened later.
		// TextureBinding as well: render scale RESAMPLES this target rather than copying it, and a
		// filtered resample is a shader reading it as a texture. Usage cannot be widened after
		// creation, so it is cheaper to ask for it here than to grow a second target type for the one
		// case that needs to sample.
		WGPUTextureDescriptor.usage(desc, Flags.TEXTURE_USAGE_RENDER_ATTACHMENT
			| Flags.TEXTURE_USAGE_COPY_SRC | Flags.TEXTURE_USAGE_COPY_DST
			| Flags.TEXTURE_USAGE_TEXTURE_BINDING);
		WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
		WGPUTextureDescriptor.format(desc, format);
		WGPUTextureDescriptor.mipLevelCount(desc, 1);
		WGPUTextureDescriptor.sampleCount(desc, 1);

		MemorySegment size = WGPUTextureDescriptor.size(desc);
		WGPUExtent3D.width(size, width);
		WGPUExtent3D.height(size, height);
		WGPUExtent3D.depthOrArrayLayers(size, 1);

		MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
		if (texture.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuDeviceCreateTexture returned NULL");
		}
		MemorySegment view = wgpuTextureCreateView(texture, MemorySegment.NULL);
		if (view.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuTextureCreateView returned NULL");
		}
		return new RenderTarget(texture, view, width, height, format);
	}

	/**
	 * Builds a render pass descriptor that clears to {@code (r,g,b,a)} and stores the result.
	 *
	 * @param arena must outlive the pass encoding, not just this call -- Dawn reads the descriptor
	 *              during {@code beginRenderPass}, and the attachment array has to still be there.
	 */
	public MemorySegment passDescriptor(Arena arena, double r, double g, double b, double a) {
		MemorySegment attachment = WGPURenderPassColorAttachment.allocate(arena);
		WGPURenderPassColorAttachment.view(attachment, view);
		WGPURenderPassColorAttachment.loadOp(attachment, WGPULoadOp_Clear());
		WGPURenderPassColorAttachment.storeOp(attachment, WGPUStoreOp_Store());
		// Sentinel meaning "not a 3D slice"; zero would name slice 0 of a volume texture.
		WGPURenderPassColorAttachment.depthSlice(attachment, WGPU_DEPTH_SLICE_UNDEFINED());

		MemorySegment clear = WGPURenderPassColorAttachment.clearValue(attachment);
		WGPUColor.r(clear, r);
		WGPUColor.g(clear, g);
		WGPUColor.b(clear, b);
		WGPUColor.a(clear, a);

		MemorySegment desc = WGPURenderPassDescriptor.allocate(arena);
		WGPURenderPassDescriptor.colorAttachmentCount(desc, 1);
		WGPURenderPassDescriptor.colorAttachments(desc, attachment);
		return desc;
	}

	public MemorySegment view() {
		return view;
	}

	/** The texture itself, which readback copies from -- a view cannot be a copy source. */
	public MemorySegment handle() {
		return texture;
	}

	public int format() {
		return format;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() {
		if (!view.equals(MemorySegment.NULL)) wgpuTextureViewRelease(view);
		if (!texture.equals(MemorySegment.NULL)) wgpuTextureRelease(texture);
	}
}
