package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUOrigin3D;
import com.periut.webgpu.WGPUTexelCopyTextureInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Copies one texture straight into another on the GPU.
 *
 * <p>Used to put an offscreen frame into a swapchain drawable, which is how the renderer runs
 * uncapped without asking the compositor to present more often than it can. A copy rather than a
 * full-screen draw: the two textures are the same size and format, so there is nothing to filter or
 * transform, and a copy costs no pipeline, no bind group and no fragment shading.
 *
 * <p>Both textures must have been created with the matching usage -- {@code COPY_SRC} on the source,
 * {@code COPY_DST} on the destination -- and identical formats. A format mismatch is the easy mistake
 * here: swapchains are BGRA on Metal while an offscreen target defaults to RGBA.
 */
public final class Blit {
	private Blit() {
	}

	public static void copy(WebGPUContext ctx, MemorySegment source, MemorySegment destination,
			int width, int height) {
		if (source.equals(MemorySegment.NULL) || destination.equals(MemorySegment.NULL)) {
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment from = WGPUTexelCopyTextureInfo.allocate(arena);
			WGPUTexelCopyTextureInfo.texture(from, source);
			WGPUTexelCopyTextureInfo.mipLevel(from, 0);
			WGPUTexelCopyTextureInfo.aspect(from, WGPUTextureAspect_All());
			zeroOrigin(WGPUTexelCopyTextureInfo.origin(from));

			MemorySegment to = WGPUTexelCopyTextureInfo.allocate(arena);
			WGPUTexelCopyTextureInfo.texture(to, destination);
			WGPUTexelCopyTextureInfo.mipLevel(to, 0);
			WGPUTexelCopyTextureInfo.aspect(to, WGPUTextureAspect_All());
			zeroOrigin(WGPUTexelCopyTextureInfo.origin(to));

			MemorySegment extent = WGPUExtent3D.allocate(arena);
			WGPUExtent3D.width(extent, width);
			WGPUExtent3D.height(extent, height);
			WGPUExtent3D.depthOrArrayLayers(extent, 1);

			MemorySegment encoder = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
			wgpuCommandEncoderCopyTextureToTexture(encoder, from, to, extent);
			MemorySegment cmd = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);
			MemorySegment list = arena.allocate(ValueLayout.ADDRESS, 1);
			list.setAtIndex(ValueLayout.ADDRESS, 0, cmd);
			wgpuQueueSubmit(ctx.queue(), 1, list);
			wgpuCommandBufferRelease(cmd);
			wgpuCommandEncoderRelease(encoder);
		}
	}

	private static void zeroOrigin(MemorySegment origin) {
		WGPUOrigin3D.x(origin, 0);
		WGPUOrigin3D.y(origin, 0);
		WGPUOrigin3D.z(origin, 0);
	}
}
