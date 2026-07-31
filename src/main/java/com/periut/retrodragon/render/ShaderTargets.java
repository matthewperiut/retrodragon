package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * The colour attachments a shader extension renders the world into, instead of the swapchain.
 *
 * <p>Target 0 is the scene. It is a FLOAT format, and that is the whole point: an 8-bit swapchain
 * clamps every value at 1.0 the instant it is written, so a sun disc, a torch flame and a white
 * cloud all arrive at a tonemapper as the same white. With headroom above 1.0 the tonemapper has
 * something to work with, and bloom has something to threshold.
 *
 * <p>Targets 1..n are auxiliary attachments written by the same draws -- a G-buffer. What they mean
 * is entirely the extension's business; the engine only allocates them and attaches them.
 *
 * <h2>One side, not two</h2>
 *
 * There is deliberately no read/write flip. A ping-pong pair exists to let a post pass read what it
 * is writing, and nothing here needs that: the world pass writes target 0 and never samples it, and
 * a composite chain reads target 0 while writing the SWAPCHAIN, which is a different texture
 * already. An extension that wants a multi-pass chain allocates its own intermediates, where it can
 * size and format them for what it is actually doing.
 */
public final class ShaderTargets implements AutoCloseable {
	/** Float, with headroom above 1.0 and enough precision for a dark night sky not to band. */
	public static final int HDR_FORMAT = WGPUTextureFormat_RGBA16Float();

	private final WebGPUContext ctx;
	private final Arena arena = Arena.ofShared();
	private final int format;
	private final int auxCount;

	private MemorySegment[] textures = new MemorySegment[0];
	private MemorySegment[] views = new MemorySegment[0];
	private int width;
	private int height;

	public ShaderTargets(WebGPUContext ctx, int format, int auxCount) {
		this.ctx = ctx;
		this.format = format == 0 ? HDR_FORMAT : format;
		this.auxCount = Math.max(0, auxCount);
	}

	/** Reallocates if the size changed; cheap and idempotent otherwise. */
	public void resize(int width, int height) {
		int w = Math.max(1, width);
		int h = Math.max(1, height);
		if (w == this.width && h == this.height && views.length > 0) {
			return;
		}
		release();
		this.width = w;
		this.height = h;
		int count = 1 + auxCount;
		textures = new MemorySegment[count];
		views = new MemorySegment[count];
		for (int i = 0; i < count; i++) {
			textures[i] = create(w, h, "retrodragon-colortex" + i);
			views[i] = wgpuTextureCreateView(textures[i], MemorySegment.NULL);
			if (views[i].equals(MemorySegment.NULL)) {
				throw new IllegalStateException("colortex" + i + " view creation failed");
			}
		}
		RetroDragon.detail("shader targets sized to {}x{} ({} attachment(s))", w, h, count);
	}

	private MemorySegment create(int w, int h, String label) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUTextureDescriptor.label(desc), label);
			// TEXTURE_BINDING so a composite pass can sample it; COPY_SRC so it can also be read back
			// or blitted straight out when the extension has no chain of its own.
			WGPUTextureDescriptor.usage(desc, Flags.TEXTURE_USAGE_RENDER_ATTACHMENT
				| Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_COPY_SRC);
			WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
			WGPUTextureDescriptor.format(desc, format);
			WGPUTextureDescriptor.mipLevelCount(desc, 1);
			WGPUTextureDescriptor.sampleCount(desc, 1);
			MemorySegment size = WGPUTextureDescriptor.size(desc);
			WGPUExtent3D.width(size, w);
			WGPUExtent3D.height(size, h);
			WGPUExtent3D.depthOrArrayLayers(size, 1);
			MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException(label + " creation failed");
			}
			return texture;
		}
	}

	/** The scene attachment. */
	public MemorySegment view() {
		return views.length > 0 ? views[0] : MemorySegment.NULL;
	}

	/** Attachments 1..n, as the render pass wants them. Empty when none were requested. */
	public MemorySegment[] auxViews() {
		if (views.length <= 1) {
			return EMPTY;
		}
		MemorySegment[] aux = new MemorySegment[views.length - 1];
		System.arraycopy(views, 1, aux, 0, aux.length);
		return aux;
	}

	private static final MemorySegment[] EMPTY = new MemorySegment[0];

	public MemorySegment view(int index) {
		return index >= 0 && index < views.length ? views[index] : MemorySegment.NULL;
	}

	public MemorySegment texture(int index) {
		return index >= 0 && index < textures.length ? textures[index] : MemorySegment.NULL;
	}

	public int format() {
		return format;
	}

	/** Formats of attachments 1..n, for the pipeline cache. */
	public int[] auxFormats() {
		int[] formats = new int[Math.max(0, views.length - 1)];
		java.util.Arrays.fill(formats, format);
		return formats;
	}

	public int auxCount() {
		return auxCount;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	private void release() {
		for (MemorySegment view : views) {
			if (view != null && !view.equals(MemorySegment.NULL)) {
				wgpuTextureViewRelease(view);
			}
		}
		for (MemorySegment texture : textures) {
			if (texture != null && !texture.equals(MemorySegment.NULL)) {
				wgpuTextureRelease(texture);
			}
		}
		views = new MemorySegment[0];
		textures = new MemorySegment[0];
	}

	@Override
	public void close() {
		release();
		arena.close();
	}
}
