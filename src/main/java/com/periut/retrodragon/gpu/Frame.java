package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUColor;
import com.periut.webgpu.WGPURenderPassColorAttachment;
import com.periut.webgpu.WGPURenderPassDepthStencilAttachment;
import com.periut.webgpu.WGPURenderPassDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * One frame's command encoder, and the render passes recorded into it.
 *
 * <p>WebGPU has no implicit "current framebuffer": commands are recorded into an encoder, finished
 * into a command buffer, and submitted as a unit. Beta's renderer, by contrast, assumes every GL
 * call takes effect immediately, so something has to hold the encoder open across the whole frame
 * and hand out passes. That is this.
 *
 * <p>Passes are opened and closed rather than nested -- WebGPU forbids a second pass while one is
 * open on the same encoder, which is exactly the constraint that forces beta's interleaved
 * clear/draw/clear sequences to be reordered into batches.
 */
public final class Frame implements AutoCloseable {
	private final WebGPUContext ctx;
	private final MemorySegment encoder;
	private MemorySegment pass = MemorySegment.NULL;
	private boolean submitted;

	private Frame(WebGPUContext ctx, MemorySegment encoder) {
		this.ctx = ctx;
		this.encoder = encoder;
	}

	public static Frame begin(WebGPUContext ctx) {
		MemorySegment encoder = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
		if (encoder.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuDeviceCreateCommandEncoder returned NULL");
		}
		return new Frame(ctx, encoder);
	}

	public MemorySegment encoder() {
		return encoder;
	}

	/** The pass currently open, or NULL. */
	public MemorySegment pass() {
		return pass;
	}

	/**
	 * Opens a render pass over {@code colorView}, optionally with depth.
	 *
	 * @param clearColor true to clear, false to load what is already there -- the difference between
	 *                   starting a frame and continuing one after a pass break.
	 * @param depthView  {@link MemorySegment#NULL} for a colour-only pass.
	 * @param arena      must stay open until {@link #endPass()}; Dawn reads the descriptor during
	 *                   {@code beginRenderPass}, and the attachment structs have to still be there.
	 */
	public MemorySegment beginPass(Arena arena, MemorySegment colorView, boolean clearColor,
			float r, float g, float b, float a,
			MemorySegment depthView, boolean clearDepth) {
		if (!pass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("a render pass is already open on this frame");
		}

		MemorySegment attachment = WGPURenderPassColorAttachment.allocate(arena);
		WGPURenderPassColorAttachment.view(attachment, colorView);
		WGPURenderPassColorAttachment.loadOp(attachment,
			clearColor ? WGPULoadOp_Clear() : WGPULoadOp_Load());
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

		if (!depthView.equals(MemorySegment.NULL)) {
			MemorySegment depth = WGPURenderPassDepthStencilAttachment.allocate(arena);
			WGPURenderPassDepthStencilAttachment.view(depth, depthView);
			WGPURenderPassDepthStencilAttachment.depthLoadOp(depth,
				clearDepth ? WGPULoadOp_Clear() : WGPULoadOp_Load());
			WGPURenderPassDepthStencilAttachment.depthStoreOp(depth, WGPUStoreOp_Store());
			WGPURenderPassDepthStencilAttachment.depthClearValue(depth, 1.0F);
			WGPURenderPassDepthStencilAttachment.depthReadOnly(depth, 0);
			// Depth24Plus has no stencil aspect, so its ops must stay Undefined -- supplying real
			// ones is a validation error rather than a harmless default.
			WGPURenderPassDepthStencilAttachment.stencilLoadOp(depth, WGPULoadOp_Undefined());
			WGPURenderPassDepthStencilAttachment.stencilStoreOp(depth, WGPUStoreOp_Undefined());
			WGPURenderPassDepthStencilAttachment.stencilReadOnly(depth, 0);
			WGPURenderPassDescriptor.depthStencilAttachment(desc, depth);
		}

		pass = wgpuCommandEncoderBeginRenderPass(encoder, desc);
		if (pass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuCommandEncoderBeginRenderPass returned NULL");
		}
		return pass;
	}

	public void endPass() {
		if (pass.equals(MemorySegment.NULL)) {
			return;
		}
		wgpuRenderPassEncoderEnd(pass);
		wgpuRenderPassEncoderRelease(pass);
		pass = MemorySegment.NULL;
	}

	/** Finishes the encoder and hands the command buffer to the queue. Idempotent. */
	public void submit() {
		if (submitted) {
			return;
		}
		endPass();
		submitted = true;
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment cmd = wgpuCommandEncoderFinish(encoder, MemorySegment.NULL);
			MemorySegment list = tmp.allocate(ValueLayout.ADDRESS, 1);
			list.setAtIndex(ValueLayout.ADDRESS, 0, cmd);
			wgpuQueueSubmit(ctx.queue(), 1, list);
			wgpuCommandBufferRelease(cmd);
		}
	}

	/** Submits if the caller has not already, then releases the encoder. */
	@Override
	public void close() {
		submit();
		wgpuCommandEncoderRelease(encoder);
	}
}
