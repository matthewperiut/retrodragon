package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUBufferDescriptor;
import com.periut.webgpu.WGPUBufferMapCallback;
import com.periut.webgpu.WGPUBufferMapCallbackInfo;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUOrigin3D;
import com.periut.webgpu.WGPUTexelCopyBufferInfo;
import com.periut.webgpu.WGPUTexelCopyBufferLayout;
import com.periut.webgpu.WGPUTexelCopyTextureInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Reads a rendered texture back to the CPU.
 *
 * <p>Slow by design and only for tests: it stalls the pipeline waiting for the GPU. That is exactly
 * what a rendering test needs, though -- without it a "passing" test only proves Dawn raised no
 * validation errors, which is entirely compatible with drawing nothing at all. Every visual claim in
 * this project is checked through here.
 *
 * <p>The row-pitch rule is the thing that catches people: {@code CopyTextureToBuffer} requires
 * {@code bytesPerRow} to be a multiple of 256, so a 100-pixel-wide RGBA8 texture copies with 400
 * bytes of data and 112 bytes of padding per row. Reading the buffer as if it were tightly packed
 * gives an image that shears progressively to one side.
 */
public final class Readback {
	private static final int ROW_ALIGNMENT = 256;

	private Readback() {
	}

	/**
	 * @return RGBA8 pixels, tightly packed, {@code width * height * 4} bytes, top row first
	 */
	public static byte[] rgba(WebGPUContext ctx, MemorySegment texture, int width, int height) {
		int tightRow = width * 4;
		int paddedRow = (tightRow + ROW_ALIGNMENT - 1) / ROW_ALIGNMENT * ROW_ALIGNMENT;
		long size = (long) paddedRow * height;

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment desc = WGPUBufferDescriptor.allocate(arena);
			Shaders.stringView(arena, WGPUBufferDescriptor.label(desc), "retrodragon-readback");
			WGPUBufferDescriptor.usage(desc, Flags.BUFFER_USAGE_MAP_READ | Flags.BUFFER_USAGE_COPY_DST);
			WGPUBufferDescriptor.size(desc, size);
			WGPUBufferDescriptor.mappedAtCreation(desc, 0);
			MemorySegment staging = wgpuDeviceCreateBuffer(ctx.device(), desc);
			if (staging.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("readback buffer creation failed");
			}

			try {
				MemorySegment source = WGPUTexelCopyTextureInfo.allocate(arena);
				WGPUTexelCopyTextureInfo.texture(source, texture);
				WGPUTexelCopyTextureInfo.mipLevel(source, 0);
				WGPUTexelCopyTextureInfo.aspect(source, WGPUTextureAspect_All());
				MemorySegment origin = WGPUTexelCopyTextureInfo.origin(source);
				WGPUOrigin3D.x(origin, 0);
				WGPUOrigin3D.y(origin, 0);
				WGPUOrigin3D.z(origin, 0);

				MemorySegment destination = WGPUTexelCopyBufferInfo.allocate(arena);
				WGPUTexelCopyBufferInfo.buffer(destination, staging);
				MemorySegment layout = WGPUTexelCopyBufferInfo.layout(destination);
				WGPUTexelCopyBufferLayout.offset(layout, 0);
				WGPUTexelCopyBufferLayout.bytesPerRow(layout, paddedRow);
				WGPUTexelCopyBufferLayout.rowsPerImage(layout, height);

				MemorySegment extent = WGPUExtent3D.allocate(arena);
				WGPUExtent3D.width(extent, width);
				WGPUExtent3D.height(extent, height);
				WGPUExtent3D.depthOrArrayLayers(extent, 1);

				MemorySegment encoder = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
				wgpuCommandEncoderCopyTextureToBuffer(encoder, source, destination, extent);
				MemorySegment cmd = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);
				MemorySegment list = arena.allocate(ValueLayout.ADDRESS, 1);
				list.setAtIndex(ValueLayout.ADDRESS, 0, cmd);
				wgpuQueueSubmit(ctx.queue(), 1, list);
				wgpuCommandBufferRelease(cmd);
				wgpuCommandEncoderRelease(encoder);

				int[] status = { -1 };
				MemorySegment info = WGPUBufferMapCallbackInfo.allocate(arena);
				WGPUBufferMapCallbackInfo.mode(info, WGPUCallbackMode_AllowProcessEvents());
				WGPUBufferMapCallbackInfo.callback(info,
					WGPUBufferMapCallback.allocate((st, msg, u1, u2) -> status[0] = st, arena));
				wgpuBufferMapAsync(arena, staging, Flags.MAP_MODE_READ, 0, size, info);

				for (int i = 0; i < 2000 && status[0] == -1; i++) {
					ctx.processEvents();
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("interrupted awaiting readback", e);
					}
				}
				if (status[0] != WGPUMapAsyncStatus_Success()) {
					throw new IllegalStateException("buffer map failed, status " + status[0]);
				}

				MemorySegment mapped = wgpuBufferGetConstMappedRange(staging, 0, size).reinterpret(size);
				byte[] pixels = new byte[tightRow * height];
				for (int row = 0; row < height; row++) {
					MemorySegment.copy(mapped, ValueLayout.JAVA_BYTE, (long) row * paddedRow,
						pixels, row * tightRow, tightRow);
				}
				wgpuBufferUnmap(staging);
				return pixels;
			} finally {
				wgpuBufferRelease(staging);
			}
		}
	}

	/**
	 * Reads {@code size} bytes back from a GPU buffer.
	 *
	 * <p>Requires {@code COPY_SRC} on the source. Like the texture path, this stalls the pipeline and
	 * exists for tests -- but it is the only way to assert that a buffer holds what it should, as
	 * opposed to that the calls which filled it returned without error.
	 */
	public static byte[] buffer(WebGPUContext ctx, MemorySegment source, long size) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment desc = WGPUBufferDescriptor.allocate(arena);
			Shaders.stringView(arena, WGPUBufferDescriptor.label(desc), "retrodragon-buffer-readback");
			WGPUBufferDescriptor.usage(desc, Flags.BUFFER_USAGE_MAP_READ | Flags.BUFFER_USAGE_COPY_DST);
			WGPUBufferDescriptor.size(desc, size);
			WGPUBufferDescriptor.mappedAtCreation(desc, 0);
			MemorySegment staging = wgpuDeviceCreateBuffer(ctx.device(), desc);
			if (staging.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("buffer readback allocation failed");
			}
			try {
				MemorySegment encoder = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
				wgpuCommandEncoderCopyBufferToBuffer(encoder, source, 0, staging, 0, size);
				MemorySegment cmd = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);
				MemorySegment list = arena.allocate(ValueLayout.ADDRESS, 1);
				list.setAtIndex(ValueLayout.ADDRESS, 0, cmd);
				wgpuQueueSubmit(ctx.queue(), 1, list);
				wgpuCommandBufferRelease(cmd);
				wgpuCommandEncoderRelease(encoder);

				int[] status = { -1 };
				MemorySegment info = WGPUBufferMapCallbackInfo.allocate(arena);
				WGPUBufferMapCallbackInfo.mode(info, WGPUCallbackMode_AllowProcessEvents());
				WGPUBufferMapCallbackInfo.callback(info,
					WGPUBufferMapCallback.allocate((st, msg, u1, u2) -> status[0] = st, arena));
				wgpuBufferMapAsync(arena, staging, Flags.MAP_MODE_READ, 0, size, info);
				for (int i = 0; i < 2000 && status[0] == -1; i++) {
					ctx.processEvents();
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("interrupted awaiting buffer readback", e);
					}
				}
				if (status[0] != WGPUMapAsyncStatus_Success()) {
					throw new IllegalStateException("buffer map failed, status " + status[0]);
				}
				MemorySegment mapped = wgpuBufferGetConstMappedRange(staging, 0, size).reinterpret(size);
				byte[] bytes = mapped.toArray(ValueLayout.JAVA_BYTE);
				wgpuBufferUnmap(staging);
				return bytes;
			} finally {
				wgpuBufferRelease(staging);
			}
		}
	}

	/** The RGBA of one pixel, as {@code 0xAARRGGBB}, for a spot check. */
	public static int pixel(byte[] rgba, int width, int x, int y) {
		int i = (y * width + x) * 4;
		return (rgba[i + 3] & 0xFF) << 24 | (rgba[i] & 0xFF) << 16
			| (rgba[i + 1] & 0xFF) << 8 | rgba[i + 2] & 0xFF;
	}
}
