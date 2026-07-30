package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUOrigin3D;
import com.periut.webgpu.WGPUSamplerDescriptor;
import com.periut.webgpu.WGPUTexelCopyBufferLayout;
import com.periut.webgpu.WGPUTexelCopyTextureInfo;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import static com.periut.webgpu.webgpu_h.*;

/**
 * A sampled texture and its view.
 *
 * <p>RGBA8Unorm throughout, because that is what beta's PNG loader already produces and what
 * {@code TerrainMipmaps} operates on. Anything else would mean a conversion pass on the CPU for no
 * visual gain.
 *
 * <p>Mip levels are allocated but not generated: WebGPU has no {@code glGenerateMipmap}, so the
 * chain has to be built either on the CPU or with a compute pass. RetroDragon already builds one on
 * the CPU with a solidify pre-pass and Castano alpha-coverage rescale -- beta's transparent texels
 * store black RGB, which a naive box filter drags in as dark fringes -- so the CPU chain is uploaded
 * level by level rather than regenerated here.
 */
public final class GpuTexture implements AutoCloseable {
	private final WebGPUContext ctx;
	private final MemorySegment texture;
	private final MemorySegment view;
	private final int width;
	private final int height;
	private final int mipLevels;

	private GpuTexture(WebGPUContext ctx, MemorySegment texture, MemorySegment view,
			int width, int height, int mipLevels) {
		this.ctx = ctx;
		this.texture = texture;
		this.view = view;
		this.width = width;
		this.height = height;
		this.mipLevels = mipLevels;
	}

	public static GpuTexture create(WebGPUContext ctx, int width, int height, int mipLevels, String label) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUTextureDescriptor.label(desc), label);
			WGPUTextureDescriptor.usage(desc,
				Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_COPY_DST);
			WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
			WGPUTextureDescriptor.format(desc, WGPUTextureFormat_RGBA8Unorm());
			WGPUTextureDescriptor.mipLevelCount(desc, Math.max(1, mipLevels));
			WGPUTextureDescriptor.sampleCount(desc, 1);

			MemorySegment size = WGPUTextureDescriptor.size(desc);
			WGPUExtent3D.width(size, width);
			WGPUExtent3D.height(size, height);
			WGPUExtent3D.depthOrArrayLayers(size, 1);

			MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("texture '" + label + "' creation failed");
			}
			MemorySegment view = wgpuTextureCreateView(texture, MemorySegment.NULL);
			if (view.equals(MemorySegment.NULL)) {
				wgpuTextureRelease(texture);
				throw new IllegalStateException("texture view creation failed for '" + label + "'");
			}
			return new GpuTexture(ctx, texture, view, width, height, Math.max(1, mipLevels));
		}
	}

	/** A 1x1 opaque white texture, so a pipeline with texturing disabled still has something bound. */
	public static GpuTexture white(WebGPUContext ctx) {
		GpuTexture t = create(ctx, 1, 1, 1, "retrodragon-white");
		ByteBuffer pixel = ByteBuffer.allocateDirect(4);
		pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
		t.upload(0, 0, 0, 1, 1, pixel);
		return t;
	}

	/**
	 * Uploads a rectangle of RGBA8 texels into one mip level.
	 *
	 * <p>{@code source} must hold {@code w * h * 4} bytes from its current position, tightly packed.
	 * GL's {@code GL_UNPACK_ALIGNMENT} has no WebGPU equivalent -- rows are described explicitly by
	 * {@code bytesPerRow} instead, and beta's textures are all tightly packed anyway.
	 */
	public void upload(int level, int x, int y, int w, int h, ByteBuffer source) {
		if (w <= 0 || h <= 0) {
			return;
		}
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment destination = WGPUTexelCopyTextureInfo.allocate(tmp);
			WGPUTexelCopyTextureInfo.texture(destination, texture);
			WGPUTexelCopyTextureInfo.mipLevel(destination, level);
			WGPUTexelCopyTextureInfo.aspect(destination, WGPUTextureAspect_All());
			MemorySegment origin = WGPUTexelCopyTextureInfo.origin(destination);
			WGPUOrigin3D.x(origin, x);
			WGPUOrigin3D.y(origin, y);
			WGPUOrigin3D.z(origin, 0);

			MemorySegment layout = WGPUTexelCopyBufferLayout.allocate(tmp);
			WGPUTexelCopyBufferLayout.offset(layout, 0);
			WGPUTexelCopyBufferLayout.bytesPerRow(layout, w * 4);
			WGPUTexelCopyBufferLayout.rowsPerImage(layout, h);

			MemorySegment extent = WGPUExtent3D.allocate(tmp);
			WGPUExtent3D.width(extent, w);
			WGPUExtent3D.height(extent, h);
			WGPUExtent3D.depthOrArrayLayers(extent, 1);

			long bytes = (long) w * h * 4L;
			MemorySegment data = source.isDirect()
				? MemorySegment.ofBuffer(source)
				: copyToNative(tmp, source, (int) bytes);
			wgpuQueueWriteTexture(ctx.queue(), destination, data, bytes, layout, extent);
		}
	}

	private static MemorySegment copyToNative(Arena arena, ByteBuffer source, int bytes) {
		MemorySegment segment = arena.allocate(bytes);
		int position = source.position();
		for (int i = 0; i < bytes; i++) {
			segment.set(ValueLayout.JAVA_BYTE, i, source.get(position + i));
		}
		return segment;
	}

	/**
	 * Nearest filtering, which is what beta's look depends on -- bilinear magnification turns the
	 * 16x16 block art into mush. Minification uses the mip chain when there is one.
	 */
	public static MemorySegment nearestSampler(WebGPUContext ctx, boolean mipmap, int maxAnisotropy) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUSamplerDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUSamplerDescriptor.label(desc), "retrodragon-nearest");
			WGPUSamplerDescriptor.addressModeU(desc, WGPUAddressMode_Repeat());
			WGPUSamplerDescriptor.addressModeV(desc, WGPUAddressMode_Repeat());
			WGPUSamplerDescriptor.addressModeW(desc, WGPUAddressMode_Repeat());
			WGPUSamplerDescriptor.magFilter(desc, WGPUFilterMode_Nearest());
			// NEAREST within a level, in both directions. WebGPU's minFilter is the within-level
			// filter, NOT the mip filter -- setting it to Linear gives GL_LINEAR_MIPMAP_LINEAR, which
			// bilinearly blurs the texels themselves and softens every edge in a pixel-art game. The
			// blend BETWEEN levels is mipmapFilter below, and that is the only linear step wanted:
			// together they are GL_NEAREST_MIPMAP_LINEAR, which is what the GL path uses.
			WGPUSamplerDescriptor.minFilter(desc, WGPUFilterMode_Nearest());
			WGPUSamplerDescriptor.mipmapFilter(desc,
				mipmap ? WGPUMipmapFilterMode_Linear() : WGPUMipmapFilterMode_Nearest());
			WGPUSamplerDescriptor.lodMinClamp(desc, 0.0F);
			WGPUSamplerDescriptor.lodMaxClamp(desc, mipmap ? 32.0F : 0.0F);
			WGPUSamplerDescriptor.maxAnisotropy(desc, (short) Math.max(1, maxAnisotropy));

			MemorySegment sampler = wgpuDeviceCreateSampler(ctx.device(), desc);
			if (sampler.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("sampler creation failed");
			}
			return sampler;
		}
	}

	/**
	 * A sampler for beta's texture conventions.
	 *
	 * <p>beta encodes sampler state in the texture PATH, with two prefixes that mean different things
	 * and can combine:
	 *
	 * <ul>
	 *   <li>{@code %clamp%} -- clamp to edge instead of repeating. The entity shadow blob uses this,
	 *       and it is not cosmetic: the shadow is drawn as a quad per ground block with UVs that run
	 *       outside 0..1 for blocks away from the entity, relying on clamping to fade to nothing.
	 *       Sampled with REPEAT those UVs wrap, and the blob tiles across every block around the
	 *       entity instead of appearing once beneath it.</li>
	 *   <li>{@code %blur%} -- linear filtering instead of nearest, for textures meant to be smooth.</li>
	 * </ul>
	 *
	 * <p>Everything else stays nearest and repeating, which is what beta's pixel art needs.
	 *
	 * @param mipmap linear BETWEEN levels, still nearest within one -- GL_NEAREST_MIPMAP_LINEAR
	 */
	public static MemorySegment sampler(WebGPUContext ctx, boolean linear, boolean clamp,
			boolean mipmap) {
		try (Arena tmp = Arena.ofConfined()) {
			int address = clamp ? WGPUAddressMode_ClampToEdge() : WGPUAddressMode_Repeat();
			int filter = linear ? WGPUFilterMode_Linear() : WGPUFilterMode_Nearest();

			MemorySegment desc = WGPUSamplerDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUSamplerDescriptor.label(desc),
				"retrodragon-" + (linear ? "linear" : "nearest") + (clamp ? "-clamp" : "")
					+ (mipmap ? "-mip" : ""));
			WGPUSamplerDescriptor.addressModeU(desc, address);
			WGPUSamplerDescriptor.addressModeV(desc, address);
			WGPUSamplerDescriptor.addressModeW(desc, address);
			WGPUSamplerDescriptor.magFilter(desc, filter);
			// WebGPU's minFilter is the WITHIN-level filter, not the mip filter. Setting it to Linear
			// gives GL_LINEAR_MIPMAP_LINEAR, which blurs the texels themselves.
			WGPUSamplerDescriptor.minFilter(desc, filter);
			WGPUSamplerDescriptor.mipmapFilter(desc,
				mipmap ? WGPUMipmapFilterMode_Linear() : WGPUMipmapFilterMode_Nearest());
			WGPUSamplerDescriptor.lodMinClamp(desc, 0.0F);
			WGPUSamplerDescriptor.lodMaxClamp(desc, mipmap ? 32.0F : 0.0F);
			WGPUSamplerDescriptor.maxAnisotropy(desc, (short) 1);

			MemorySegment sampler = wgpuDeviceCreateSampler(ctx.device(), desc);
			if (sampler.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("sampler creation failed");
			}
			return sampler;
		}
	}

	public MemorySegment view() {
		return view;
	}

	public MemorySegment handle() {
		return texture;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int mipLevels() {
		return mipLevels;
	}

	@Override
	public void close() {
		if (!view.equals(MemorySegment.NULL)) wgpuTextureViewRelease(view);
		if (!texture.equals(MemorySegment.NULL)) wgpuTextureRelease(texture);
	}
}
