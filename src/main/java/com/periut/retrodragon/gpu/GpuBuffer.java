package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUBufferDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import static com.periut.webgpu.webgpu_h.*;

/**
 * A GPU buffer that grows to fit what is written into it.
 *
 * <p>Growth matters because beta's per-frame geometry is not a fixed size -- the GUI, entity and
 * particle counts swing by an order of magnitude between the title screen and a busy world -- and a
 * WebGPU buffer's size is immutable after creation. Reallocating on growth only, and never
 * shrinking, converges after a few seconds of play to zero allocations per frame.
 *
 * <p>Writes go through {@code wgpuQueueWriteBuffer}, which stages internally. That is the right
 * default: mapped-at-creation buffers need a fresh buffer per frame, and persistent mapping needs
 * fence tracking neither of which pays off until the geometry path is otherwise settled.
 */
public final class GpuBuffer implements AutoCloseable {
	private final WebGPUContext ctx;
	private final long usage;
	private final String label;
	private MemorySegment buffer = MemorySegment.NULL;
	private long capacity;

	public GpuBuffer(WebGPUContext ctx, long usage, String label) {
		this.ctx = ctx;
		this.usage = usage | Flags.BUFFER_USAGE_COPY_DST;
		this.label = label;
	}

	/** Allocates up front; useful when the size is known and the first frame should not stall. */
	public static GpuBuffer sized(WebGPUContext ctx, long usage, String label, long bytes) {
		GpuBuffer b = new GpuBuffer(ctx, usage, label);
		b.ensure(bytes);
		return b;
	}

	public MemorySegment handle() {
		return buffer;
	}

	public long capacity() {
		return capacity;
	}

	/**
	 * Makes sure at least {@code bytes} fit, reallocating if not.
	 *
	 * @return true if the buffer was replaced -- bind groups referencing the old handle are stale and
	 *         must be rebuilt, which is the one way this can silently render nothing.
	 */
	/**
	 * The device's {@code maxBufferSize}, past which creation yields an INVALID buffer rather than a
	 * null one.
	 *
	 * <p>That distinction cost a hung game. Dawn hands back a real handle whose every later use
	 * fails validation, so a null check passes and the failure surfaces hundreds of frames later as
	 * {@code [Invalid Buffer] is invalid} on every write and every draw -- with no indication that
	 * the cause was a single oversized allocation. Checking the limit up front turns that into one
	 * exception at the point of the mistake.
	 */
	/** The largest buffer this device will create; see {@link #maxBufferSize}. */
	public static long maxSize(WebGPUContext ctx) {
		return maxBufferSize(ctx);
	}

	private static long maxBufferSize(WebGPUContext ctx) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment limits = com.periut.webgpu.WGPULimits.allocate(tmp);
			wgpuDeviceGetLimits(ctx.device(), limits);
			long max = com.periut.webgpu.WGPULimits.maxBufferSize(limits);
			// A zero here means the query failed; fall back to WebGPU's guaranteed minimum rather
			// than treating "unknown" as "unlimited".
			return max > 0 ? max : 256L << 20;
		} catch (RuntimeException e) {
			return 256L << 20;
		}
	}

	public boolean ensure(long bytes) {
		// WebGPU requires buffer sizes to be a multiple of 4, and writes to be too.
		long needed = Math.max(4L, bytes + 3L & ~3L);
		if (needed <= capacity) {
			return false;
		}
		long limit = maxBufferSize(ctx);
		if (needed > limit) {
			throw new IllegalStateException("buffer '" + label + "' needs " + needed
				+ " bytes but the device's maxBufferSize is " + limit
				+ "; Dawn would return an INVALID buffer rather than failing, and every later"
				+ " write and draw against it would fail validation instead");
		}
		// Double rather than fit exactly: a buffer that grows by one vertex per frame would
		// otherwise reallocate every frame forever.
		long size = Math.max(needed, Math.max(capacity * 2L, 4096L));
		close();
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUBufferDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUBufferDescriptor.label(desc), label);
			WGPUBufferDescriptor.usage(desc, usage);
			WGPUBufferDescriptor.size(desc, size);
			WGPUBufferDescriptor.mappedAtCreation(desc, 0);
			buffer = wgpuDeviceCreateBuffer(ctx.device(), desc);
		}
		if (buffer.equals(MemorySegment.NULL)) {
			capacity = 0;
			throw new IllegalStateException("buffer '" + label + "' creation failed at " + size + " bytes");
		}
		capacity = size;
		return true;
	}

	/**
	 * Uploads {@code length} bytes from {@code source}'s current position.
	 *
	 * @return true if the underlying buffer had to be reallocated to fit
	 */
	public boolean write(ByteBuffer source, int length) {
		if (length <= 0) {
			return false;
		}
		boolean grown = ensure(length);
		MemorySegment data = MemorySegment.ofBuffer(source);
		// Dawn requires a 4-byte multiple; the tail padding is uninitialised memory the GPU never
		// reads, because the draw's vertex count stops before it.
		long padded = length + 3L & ~3L;
		if (padded > data.byteSize()) {
			// Rare: the source ends on a non-multiple-of-4 and has no slack. Copy through a scratch
			// segment rather than reading past the buffer.
			try (Arena tmp = Arena.ofConfined()) {
				MemorySegment scratch = tmp.allocate(padded);
				MemorySegment.copy(data, 0, scratch, 0, length);
				wgpuQueueWriteBuffer(ctx.queue(), buffer, 0, scratch, padded);
			}
			return grown;
		}
		wgpuQueueWriteBuffer(ctx.queue(), buffer, 0, data, padded);
		return grown;
	}

	/** Uploads a float array; the common case for uniform blocks built on the Java side. */
	public boolean write(float[] values) {
		long bytes = values.length * 4L;
		boolean grown = ensure(bytes);
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment data = tmp.allocate(bytes);
			MemorySegment.copy(values, 0, data, ValueLayout.JAVA_FLOAT, 0, values.length);
			wgpuQueueWriteBuffer(ctx.queue(), buffer, 0, data, bytes);
		}
		return grown;
	}

	/**
	 * Grows the buffer while KEEPING its contents, by copying them on the GPU.
	 *
	 * <p>Different from {@link #ensure} in exactly the way that matters for an arena. {@code ensure}
	 * reallocates and drops what was there, which is fine for a buffer rewritten every frame and
	 * catastrophic for one whose users hold offsets into it: every offset stays valid while the data
	 * it points at becomes whatever the allocator left behind. For terrain that renders as garbage
	 * geometry -- the world appears to be missing its surface, with cave walls drawn in mid-air.
	 *
	 * <p>Requires {@code COPY_SRC} in the usage flags, which the caller must have asked for.
	 *
	 * @return true if the buffer grew; false if it was already big enough
	 */
	public boolean growPreserving(long bytes) {
		long needed = Math.max(4L, bytes + 3L & ~3L);
		if (needed <= capacity) {
			return false;
		}
		long limit = maxBufferSize(ctx);
		if (needed > limit) {
			throw new IllegalStateException("buffer '" + label + "' needs " + needed
				+ " bytes but the device's maxBufferSize is " + limit);
		}
		// Doubling must not overshoot the limit either: an oversized request produces an invalid
		// buffer, not an error, and the symptom appears much later and far from the cause.
		long size = Math.min(limit, Math.max(needed, Math.max(capacity * 2L, 4096L)));
		MemorySegment previous = buffer;
		long previousCapacity = capacity;

		MemorySegment grown;
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUBufferDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUBufferDescriptor.label(desc), label);
			WGPUBufferDescriptor.usage(desc, usage);
			WGPUBufferDescriptor.size(desc, size);
			WGPUBufferDescriptor.mappedAtCreation(desc, 0);
			grown = wgpuDeviceCreateBuffer(ctx.device(), desc);
		}
		if (grown.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("buffer '" + label + "' could not grow to " + size);
		}

		if (!previous.equals(MemorySegment.NULL) && previousCapacity > 0) {
			try (Arena tmp = Arena.ofConfined()) {
				MemorySegment encoder = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
				wgpuCommandEncoderCopyBufferToBuffer(encoder, previous, 0, grown, 0, previousCapacity);
				MemorySegment cmd = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);
				MemorySegment list = tmp.allocate(ValueLayout.ADDRESS, 1);
				list.setAtIndex(ValueLayout.ADDRESS, 0, cmd);
				wgpuQueueSubmit(ctx.queue(), 1, list);
				wgpuCommandBufferRelease(cmd);
				wgpuCommandEncoderRelease(encoder);
			}
			wgpuBufferRelease(previous);
		}
		buffer = grown;
		capacity = size;
		return true;
	}

	/** Uploads at a byte offset, for sub-allocating one big buffer across many draws. */
	public void writeAt(long offset, ByteBuffer source, int length) {
		if (length <= 0) {
			return;
		}
		MemorySegment data = MemorySegment.ofBuffer(source);
		wgpuQueueWriteBuffer(ctx.queue(), buffer, offset, data, length + 3L & ~3L);
	}

	@Override
	public void close() {
		if (!buffer.equals(MemorySegment.NULL)) {
			wgpuBufferRelease(buffer);
			buffer = MemorySegment.NULL;
			capacity = 0;
		}
	}
}
