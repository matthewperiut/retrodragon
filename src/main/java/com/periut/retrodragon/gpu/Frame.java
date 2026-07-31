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
		return beginPass(arena, colorView, NO_AUX, clearColor, r, g, b, a, depthView, clearDepth);
	}

	private static final MemorySegment[] NO_AUX = new MemorySegment[0];

	/**
	 * As above, with further colour attachments and an optional {@code NULL} colour view.
	 *
	 * <p>Two extensions, both for shader extensions rather than for beta:
	 *
	 * <ul>
	 * <li>{@code aux} views become attachments 1..n, so one draw can write a G-buffer. They always
	 *     take the same load op as attachment 0 -- a pass that clears the scene clears the material
	 *     data it is about to rewrite, and one that continues a pass loads both.</li>
	 * <li>{@code colorView} may be {@link MemorySegment#NULL}, for a depth-only pass. WebGPU allows a
	 *     pass with zero colour attachments provided it has a depth one, which is exactly a shadow
	 *     map.</li>
	 * </ul>
	 */
	public MemorySegment beginPass(Arena arena, MemorySegment colorView, MemorySegment[] aux,
			boolean clearColor, float r, float g, float b, float a,
			MemorySegment depthView, boolean clearDepth) {
		if (!pass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("a render pass is already open on this frame");
		}
		boolean hasColor = !colorView.equals(MemorySegment.NULL);
		if (!hasColor && depthView.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("a render pass needs at least one attachment");
		}

		MemorySegment desc = WGPURenderPassDescriptor.allocate(arena);
		if (hasColor) {
			int count = 1 + aux.length;
			MemorySegment attachments = WGPURenderPassColorAttachment.allocateArray(count, arena);
			for (int i = 0; i < count; i++) {
				MemorySegment attachment = WGPURenderPassColorAttachment.asSlice(attachments, i);
				WGPURenderPassColorAttachment.view(attachment, i == 0 ? colorView : aux[i - 1]);
				WGPURenderPassColorAttachment.loadOp(attachment,
					clearColor ? WGPULoadOp_Clear() : WGPULoadOp_Load());
				WGPURenderPassColorAttachment.storeOp(attachment, WGPUStoreOp_Store());
				// Sentinel meaning "not a 3D slice"; zero would name slice 0 of a volume texture.
				WGPURenderPassColorAttachment.depthSlice(attachment, WGPU_DEPTH_SLICE_UNDEFINED());

				MemorySegment clear = WGPURenderPassColorAttachment.clearValue(attachment);
				// Attachment 0 takes the game's clear colour; the aux targets clear to zero, which is
				// "no material here" for every encoding a pack is likely to choose.
				WGPUColor.r(clear, i == 0 ? r : 0.0F);
				WGPUColor.g(clear, i == 0 ? g : 0.0F);
				WGPUColor.b(clear, i == 0 ? b : 0.0F);
				WGPUColor.a(clear, i == 0 ? a : 0.0F);
			}
			WGPURenderPassDescriptor.colorAttachmentCount(desc, count);
			WGPURenderPassDescriptor.colorAttachments(desc, attachments);
		} else {
			WGPURenderPassDescriptor.colorAttachmentCount(desc, 0);
		}

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

	private MemorySegment computePass = MemorySegment.NULL;

	/**
	 * Opens a compute pass on this frame's encoder.
	 *
	 * <p>A compute pass is the only place a dispatch can be recorded, and WebGPU allows exactly one
	 * pass -- of either kind -- open on an encoder at a time. So this refuses to start while a render
	 * pass is up rather than letting Dawn reject the whole command buffer later, which costs the frame
	 * and reports it against whatever was recorded next.
	 *
	 * <p><b>One pass per dispatch is the normal shape here, not a waste.</b> WebGPU's read/write
	 * hazard rules are evaluated per pass: a texture written by one dispatch and read by the next
	 * cannot be both inside a single pass, and that is exactly what a ping-pong is. Between passes the
	 * implementation inserts the barrier itself, so splitting them is what makes the ping-pong legal
	 * as well as correct.
	 *
	 * @param arena must stay open until {@link #endComputePass()}; Dawn reads the descriptor during
	 *              {@code beginComputePass}
	 */
	public MemorySegment beginComputePass(Arena arena, String label) {
		if (!pass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("a render pass is open on this frame; a compute pass"
				+ " cannot start until it ends");
		}
		if (!computePass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("a compute pass is already open on this frame");
		}
		MemorySegment desc = com.periut.webgpu.WGPUComputePassDescriptor.allocate(arena);
		Shaders.stringView(arena, com.periut.webgpu.WGPUComputePassDescriptor.label(desc), label);
		computePass = wgpuCommandEncoderBeginComputePass(encoder, desc);
		if (computePass.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuCommandEncoderBeginComputePass returned NULL");
		}
		return computePass;
	}

	public void endComputePass() {
		if (computePass.equals(MemorySegment.NULL)) {
			return;
		}
		wgpuComputePassEncoderEnd(computePass);
		wgpuComputePassEncoderRelease(computePass);
		computePass = MemorySegment.NULL;
	}

	/** Finishes the encoder and hands the command buffer to the queue. Idempotent. */
	public void submit() {
		if (submitted) {
			return;
		}
		endPass();
		endComputePass();
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
