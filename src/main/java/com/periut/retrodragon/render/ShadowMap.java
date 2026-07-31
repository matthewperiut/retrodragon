package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * The depth texture a shader extension's shadow pass renders into.
 *
 * <p>Depth32Float rather than the depth-plus-stencil format the main pass uses. A shadow map is
 * compared against, not stencilled through, and a 32-bit float depth is what makes a comparison
 * sampler's four-tap filter give a clean edge over a large orthographic range -- 24 bits over 256
 * blocks is enough to make coplanar faces flicker.
 */
public final class ShadowMap implements AutoCloseable {
	public static final int FORMAT = WGPUTextureFormat_Depth32Float();

	private final WebGPUContext ctx;
	private MemorySegment texture = MemorySegment.NULL;
	private MemorySegment view = MemorySegment.NULL;
	private int resolution;

	public ShadowMap(WebGPUContext ctx) {
		this.ctx = ctx;
	}

	/** Allocates or reallocates for {@code resolution}; idempotent when unchanged. */
	public void ensure(int resolution) {
		int size = Math.max(256, resolution);
		if (size == this.resolution && !view.equals(MemorySegment.NULL)) {
			return;
		}
		release();
		this.resolution = size;
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUTextureDescriptor.label(desc), "retrodragon-shadowmap");
			WGPUTextureDescriptor.usage(desc,
				Flags.TEXTURE_USAGE_RENDER_ATTACHMENT | Flags.TEXTURE_USAGE_TEXTURE_BINDING);
			WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
			WGPUTextureDescriptor.format(desc, FORMAT);
			WGPUTextureDescriptor.mipLevelCount(desc, 1);
			WGPUTextureDescriptor.sampleCount(desc, 1);
			MemorySegment extent = WGPUTextureDescriptor.size(desc);
			WGPUExtent3D.width(extent, size);
			WGPUExtent3D.height(extent, size);
			WGPUExtent3D.depthOrArrayLayers(extent, 1);
			texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("shadow map creation failed at " + size + "px");
			}
			view = wgpuTextureCreateView(texture, MemorySegment.NULL);
			if (view.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("shadow map view creation failed");
			}
		}
	}

	public MemorySegment view() {
		return view;
	}

	public int resolution() {
		return resolution;
	}

	private void release() {
		if (!view.equals(MemorySegment.NULL)) {
			wgpuTextureViewRelease(view);
			view = MemorySegment.NULL;
		}
		if (!texture.equals(MemorySegment.NULL)) {
			wgpuTextureRelease(texture);
			texture = MemorySegment.NULL;
		}
	}

	@Override
	public void close() {
		release();
		resolution = 0;
	}
}
