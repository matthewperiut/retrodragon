package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * The depth attachment for a frame, sized to the surface.
 *
 * <p>Unlike GL, WebGPU has no implicit depth buffer: the swapchain hands out colour textures only,
 * so depth is an object the renderer owns and resizes itself. Beta depth-tests essentially
 * everything, so this is not optional past the clear-screen stage.
 *
 * <p>{@code Depth24Plus} rather than {@code Depth24PlusStencil8}: beta never touches the stencil
 * buffer, and the stencil-less format lets the depth-stencil attachment leave its stencil load/store
 * ops {@code Undefined}, which is what Dawn requires -- passing real ops for a format with no stencil
 * aspect is a validation error.
 */
public final class DepthBuffer implements AutoCloseable {
	private final MemorySegment texture;
	private final MemorySegment view;
	private final int width;
	private final int height;
	private final int format;

	private DepthBuffer(MemorySegment texture, MemorySegment view, int width, int height, int format) {
		this.texture = texture;
		this.view = view;
		this.width = width;
		this.height = height;
		this.format = format;
	}

	public static DepthBuffer create(WebGPUContext ctx, Arena arena, int width, int height) {
		int format = WGPUTextureFormat_Depth24Plus();

		MemorySegment desc = WGPUTextureDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUTextureDescriptor.label(desc), "retrodragon-depth");
		WGPUTextureDescriptor.usage(desc, Flags.TEXTURE_USAGE_RENDER_ATTACHMENT);
		WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
		WGPUTextureDescriptor.format(desc, format);
		WGPUTextureDescriptor.mipLevelCount(desc, 1);
		WGPUTextureDescriptor.sampleCount(desc, 1);

		MemorySegment size = WGPUTextureDescriptor.size(desc);
		WGPUExtent3D.width(size, Math.max(1, width));
		WGPUExtent3D.height(size, Math.max(1, height));
		WGPUExtent3D.depthOrArrayLayers(size, 1);

		MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
		if (texture.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("depth texture creation returned NULL");
		}
		MemorySegment view = wgpuTextureCreateView(texture, MemorySegment.NULL);
		if (view.equals(MemorySegment.NULL)) {
			wgpuTextureRelease(texture);
			throw new IllegalStateException("depth texture view creation returned NULL");
		}
		return new DepthBuffer(texture, view, width, height, format);
	}

	/** True when this buffer already matches the requested size, so a resize can skip reallocating. */
	public boolean matches(int width, int height) {
		return this.width == width && this.height == height;
	}

	public MemorySegment view() {
		return view;
	}

	public int format() {
		return format;
	}

	@Override
	public void close() {
		if (!view.equals(MemorySegment.NULL)) wgpuTextureViewRelease(view);
		if (!texture.equals(MemorySegment.NULL)) wgpuTextureRelease(texture);
	}
}
