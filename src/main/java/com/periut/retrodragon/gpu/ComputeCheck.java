package com.periut.retrodragon.gpu;

import com.periut.retrodragon.api.ComputeProgram;
import com.periut.retrodragon.api.ShaderResources;
import com.periut.webgpu.WGPUExtent3D;
import com.periut.webgpu.WGPUTextureDescriptor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Proves the compute path end to end against a real device, headless.
 *
 * <pre>./gradlew computeCheck</pre>
 *
 * <p>It exists because a compute pass that produces nothing is indistinguishable, from the outside,
 * from one that works: no pixels change, no error is raised, and the only symptom in the game is
 * that a light does not appear -- which has a dozen other explanations. So the whole chain is
 * exercised here, where the answer can be READ BACK and compared against a number:
 *
 * <ul>
 * <li>a CPU upload into an {@code r8unorm} volume, whose 8-byte natural row pitch is not a legal
 *     one -- the padding path in {@link ShaderResources#uploadVolume};</li>
 * <li>a dispatch writing a 3D {@code rgba16float} storage texture while reading that volume;</li>
 * <li>a SECOND dispatch reading the first one's output as a sampled 3D texture, which is the
 *     ping-pong the whole design turns on, and writing a 2D storage texture;</li>
 * <li>dynamic-offset uniforms, with the two passes reading different slots of one buffer.</li>
 * </ul>
 *
 * <p>Exits non-zero if any value is wrong or if the device reported a single validation error.
 */
public final class ComputeCheck {
	private static final int N = 8;

	private ComputeCheck() {
	}

	/**
	 * Writes {@code vec4(x, y, z, 1) * scale} into the volume, but only where the opacity grid says
	 * the cell is passable -- so the result depends on data that came from the CPU, and an upload
	 * that silently did nothing cannot pass.
	 */
	private static final String FILL = """
		struct Params {
		    gridSize : i32,
		    scale    : f32,
		};
		@group(0) @binding(0) var<uniform> params : Params;
		@group(0) @binding(1) var dst : texture_storage_3d<rgba16float, write>;
		@group(0) @binding(2) var opacity : texture_3d<f32>;

		@compute @workgroup_size(4, 4, 4)
		fn main(@builtin(global_invocation_id) gid : vec3<u32>) {
		    let p = vec3<i32>(gid);
		    if (any(p >= vec3<i32>(params.gridSize))) {
		        return;
		    }
		    let solid = textureLoad(opacity, p, 0).r * 255.0 >= 15.0;
		    let v = vec3<f32>(p) * params.scale;
		    textureStore(dst, p, vec4<f32>(select(v, vec3<f32>(0.0), solid), 1.0));
		}
		""";

	/**
	 * Reads the volume back out along z and writes one 2D texel per column, so the whole 3D result
	 * can be checked through the ordinary 2D readback.
	 */
	private static final String GATHER = """
		struct Params {
		    gridSize : i32,
		    layer    : i32,
		};
		@group(0) @binding(0) var<uniform> params : Params;
		@group(0) @binding(1) var out2d : texture_storage_2d<rgba8unorm, write>;
		@group(0) @binding(2) var src : texture_3d<f32>;

		@compute @workgroup_size(8, 8, 1)
		fn main(@builtin(global_invocation_id) gid : vec3<u32>) {
		    let xy = vec2<i32>(gid.xy);
		    if (any(xy >= vec2<i32>(params.gridSize))) {
		        return;
		    }
		    let v = textureLoad(src, vec3<i32>(xy, params.layer), 0);
		    textureStore(out2d, xy, vec4<f32>(v.rgb, 1.0));
		}
		""";

	public static void main(String[] args) {
		WebGPUNatives.load();
		int failures = 0;
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("device     = " + ctx.apiSummary());

			// --- an opacity grid the CPU fills: every cell passable except the plane x == 3 ---
			MemorySegment opacity = ShaderResources.createVolume(ctx, N, N, N,
				WGPUTextureFormat_R8Unorm(), "computecheck-opacity",
				Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_COPY_DST);
			MemorySegment opacityView = ShaderResources.view3d(opacity);
			ByteBuffer cells = ByteBuffer.allocateDirect(N * N * N).order(ByteOrder.nativeOrder());
			for (int z = 0; z < N; z++) {
				for (int y = 0; y < N; y++) {
					for (int x = 0; x < N; x++) {
						cells.put((z * N + y) * N + x, (byte) (x == 3 ? 255 : 0));
					}
				}
			}
			// bytesPerTexel 1: the row is 8 bytes, which is exactly the case a tight upload cannot
			// describe. If the padding path is wrong this reads as garbage rather than as an error.
			ShaderResources.uploadVolume(ctx, opacity, N, N, N, 1, cells);

			MemorySegment volume = ShaderResources.createVolume(ctx, N, N, N,
				WGPUTextureFormat_RGBA16Float(), "computecheck-volume",
				Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_STORAGE_BINDING);
			MemorySegment volumeView = ShaderResources.view3d(volume);

			MemorySegment flat = create2d(ctx, N, N, WGPUTextureFormat_RGBA8Unorm(),
				Flags.TEXTURE_USAGE_STORAGE_BINDING | Flags.TEXTURE_USAGE_COPY_SRC);
			MemorySegment flatView = wgpuTextureCreateView(flat, MemorySegment.NULL);

			// The value the fill writes per unit of coordinate. 1/32 keeps x*scale under 1 at N = 8,
			// so it survives the rgba8unorm the gather writes without clamping.
			final float scale = 1.0F / 32.0F;
			final int layer = 5;

			try (ComputeProgram fill = ComputeProgram.builder(ctx, "computecheck-fill")
					.uniforms(8, 1)
					.storageTexture(1, WGPUTextureViewDimension_3D(), WGPUTextureFormat_RGBA16Float())
					.texture(2, WGPUTextureViewDimension_3D(), WGPUTextureSampleType_UnfilterableFloat())
					.source(FILL)
					.build();
				ComputeProgram gather = ComputeProgram.builder(ctx, "computecheck-gather")
					.uniforms(8, 1)
					.storageTexture(1, WGPUTextureViewDimension_2D(), WGPUTextureFormat_RGBA8Unorm())
					.texture(2, WGPUTextureViewDimension_3D(), WGPUTextureSampleType_UnfilterableFloat())
					.source(GATHER)
					.build()) {

				ByteBuffer p0 = fill.uniformSlot(0);
				p0.putInt(N).putFloat(scale);
				fill.uploadUniforms();

				ByteBuffer p1 = gather.uniformSlot(0);
				p1.putInt(N).putInt(layer);
				gather.uploadUniforms();

				MemorySegment fillGroup = fill.createGroup("computecheck-fill", volumeView, opacityView);
				MemorySegment gatherGroup =
					gather.createGroup("computecheck-gather", flatView, volumeView);

				try (Frame frame = Frame.begin(ctx)) {
					// Two passes on ONE encoder, the second reading what the first wrote. Legal only
					// because they are separate passes; inside one, a texture cannot be both.
					fill.dispatch(frame, fillGroup, 0, N / 4, N / 4, N / 4);
					gather.dispatch(frame, gatherGroup, 0, 1, 1, 1);
					frame.submit();
				}
				ctx.markFrameSubmitted();
				ctx.drain(2000);

				byte[] pixels = Readback.rgba(ctx, flat, N, N);
				for (int y = 0; y < N; y++) {
					for (int x = 0; x < N; x++) {
						int i = (y * N + x) * 4;
						int r = pixels[i] & 0xFF;
						int g = pixels[i + 1] & 0xFF;
						int b = pixels[i + 2] & 0xFF;
						boolean solid = x == 3;
						int wantR = solid ? 0 : Math.round(x * scale * 255.0F);
						int wantG = solid ? 0 : Math.round(y * scale * 255.0F);
						int wantB = solid ? 0 : Math.round(layer * scale * 255.0F);
						// One unit of slack: the value crosses f16 and then unorm8 on the way here.
						if (Math.abs(r - wantR) > 1 || Math.abs(g - wantG) > 1
								|| Math.abs(b - wantB) > 1) {
							System.out.println("  FAIL (" + x + "," + y + ") = " + r + "," + g + "," + b
								+ " want " + wantR + "," + wantG + "," + wantB);
							failures++;
						}
					}
				}
				ComputeProgram.releaseGroup(fillGroup);
				ComputeProgram.releaseGroup(gatherGroup);
			}

			wgpuTextureViewRelease(flatView);
			wgpuTextureRelease(flat);
			wgpuTextureViewRelease(volumeView);
			wgpuTextureRelease(volume);
			wgpuTextureViewRelease(opacityView);
			wgpuTextureRelease(opacity);

			System.out.println("texels     = " + (N * N - failures) + "/" + (N * N) + " correct");
			System.out.println("gpu errors = " + ctx.errorCount()
				+ (ctx.errorCount() > 0 ? " -- first: " + ctx.firstError() : ""));
			if (ctx.errorCount() > 0) {
				failures++;
			}
		}

		if (failures > 0) {
			System.err.println("COMPUTE CHECK FAILED");
			System.exit(1);
		}
		System.out.println("COMPUTE CHECK PASSED");
	}

	private static MemorySegment create2d(WebGPUContext ctx, int width, int height, int format,
			long usage) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUTextureDescriptor.label(desc), "computecheck-2d");
			WGPUTextureDescriptor.usage(desc, usage);
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
				throw new IllegalStateException("2D texture creation failed");
			}
			return texture;
		}
	}
}
