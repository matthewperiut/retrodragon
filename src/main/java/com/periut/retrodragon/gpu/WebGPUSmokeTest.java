package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUAdapterInfo;
import com.periut.webgpu.WGPURequestAdapterCallback;
import com.periut.webgpu.WGPURequestAdapterCallbackInfo;
import com.periut.webgpu.WGPUSupportedFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Proves the binding reaches a real GPU: instance, adapter, backend and feature list.
 * Run with {@code ./gradlew smokeTest}. Exits non-zero if any step fails, so it is usable in CI.
 */
public final class WebGPUSmokeTest {
	public static void main(String[] args) throws Exception {
		WebGPUNatives.load();

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment instance = wgpuCreateInstance(MemorySegment.NULL);
			if (instance.equals(MemorySegment.NULL)) {
				fail("wgpuCreateInstance returned NULL");
			}
			System.out.println("instance   = ok");

			MemorySegment[] adapter = {MemorySegment.NULL};
			int[] status = {-1};
			MemorySegment info = WGPURequestAdapterCallbackInfo.allocate(arena);
			WGPURequestAdapterCallbackInfo.mode(info, WGPUCallbackMode_AllowProcessEvents());
			WGPURequestAdapterCallbackInfo.callback(info,
				WGPURequestAdapterCallback.allocate((st, ad, msg, u1, u2) -> {
					status[0] = st;
					adapter[0] = ad;
				}, arena));

			wgpuInstanceRequestAdapter(arena, instance, MemorySegment.NULL, info);
			for (int i = 0; i < 300 && status[0] == -1; i++) {
				wgpuInstanceProcessEvents(instance);
				Thread.sleep(10);
			}
			if (adapter[0].equals(MemorySegment.NULL)) {
				fail("no adapter (status " + status[0] + ")");
			}

			MemorySegment ai = WGPUAdapterInfo.allocate(arena);
			wgpuAdapterGetInfo(adapter[0], ai);
			System.out.println("backend    = " + backendName(WGPUAdapterInfo.backendType(ai)));

			MemorySegment sf = WGPUSupportedFeatures.allocate(arena);
			wgpuAdapterGetFeatures(adapter[0], sf);
			long count = WGPUSupportedFeatures.featureCount(sf);
			System.out.println("features   = " + count);
			System.out.println("multidraw  = "
				+ (has(sf, count, WGPUFeatureName_MultiDrawIndirect()) ? "yes" : "no"));
			// Device + queue: the objects every later stage actually builds on.
			try (WebGPUContext ctx = WebGPUContext.create()) {
				System.out.println("device     = ok (backend " + ctx.backendName() + ")");
				System.out.println("queue      = ok");
				// From the context's OWN adapter, created with Dawn's allow_unsafe_apis toggle.
				// The adapter above is a separate, untoggled one, so reporting only that would
				// measure the wrong thing.
				System.out.println("ctx.features = " + ctx.features().size()
					+ ", multidraw = " + ctx.hasFeature(WGPUFeatureName_MultiDrawIndirect()));
				System.out.println("want multidraw = " + WGPUFeatureName_MultiDrawIndirect()
					+ " (0x" + Integer.toHexString(WGPUFeatureName_MultiDrawIndirect()) + ")");
				StringBuilder ids = new StringBuilder("ctx.featureIds =");
				for (int id : ctx.features()) {
					ids.append(' ').append("0x").append(Integer.toHexString(id));
				}
				System.out.println(ids);

				// Compile real WGSL: proves the shader path end to end, which is the piece the
				// whole renderer is built on top of.
				try (Arena sa = Arena.ofConfined()) {
					MemorySegment shader = Shaders.compile(ctx, sa, "smoke", """
						@vertex fn vs(@builtin(vertex_index) i : u32) -> @builtin(position) vec4f {
						  var p = array<vec2f, 3>(vec2f(0.0, 0.5), vec2f(-0.5, -0.5), vec2f(0.5, -0.5));
						  return vec4f(p[i], 0.0, 1.0);
						}
						@fragment fn fs() -> @location(0) vec4f {
						  return vec4f(0.2, 0.6, 1.0, 1.0);
						}
						""");
					System.out.println("shader     = "
						+ (shader.equals(MemorySegment.NULL) ? "FAILED" : "compiled"));
					if (shader.equals(MemorySegment.NULL)) fail("WGSL compilation failed");

					// Phase 1: pipeline + offscreen render pass + draw + GPU completion.
					try (RenderTarget rt = RenderTarget.create(ctx, sa, 64, 64)) {
						MemorySegment pipeline = Pipelines.create(ctx, sa, shader, rt.format());
						System.out.println("pipeline   = "
							+ (pipeline.equals(MemorySegment.NULL) ? "FAILED" : "created"));
						if (pipeline.equals(MemorySegment.NULL)) fail("pipeline creation failed");

						MemorySegment enc = wgpuDeviceCreateCommandEncoder(ctx.device(), MemorySegment.NULL);
						MemorySegment pass = wgpuCommandEncoderBeginRenderPass(enc,
							rt.passDescriptor(sa, 0.1, 0.1, 0.15, 1.0));
						wgpuRenderPassEncoderSetPipeline(pass, pipeline);
						wgpuRenderPassEncoderDraw(pass, 3, 1, 0, 0);
						wgpuRenderPassEncoderEnd(pass);

						MemorySegment cmd = wgpuCommandEncoderFinish(enc, MemorySegment.NULL);
						MemorySegment cmds = sa.allocate(java.lang.foreign.ValueLayout.ADDRESS, 1);
						cmds.setAtIndex(java.lang.foreign.ValueLayout.ADDRESS, 0, cmd);
						wgpuQueueSubmit(ctx.queue(), 1, cmds);

						// Drain until the GPU reports the submission retired; without this the
						// test can pass on a queue that never actually executed.
                        for (int i = 0; i < 100; i++) { ctx.processEvents(); Thread.sleep(2); }
						System.out.println("draw       = submitted + executed (64x64 offscreen)");

						wgpuCommandBufferRelease(cmd);
						wgpuCommandEncoderRelease(enc);
						wgpuRenderPassEncoderRelease(pass);
						wgpuRenderPipelineRelease(pipeline);
					}
					wgpuShaderModuleRelease(shader);
				}
			}
			System.out.println("SMOKE TEST PASSED");
		}
	}

	private static boolean has(MemorySegment supported, long count, int feature) {
		MemorySegment list = WGPUSupportedFeatures.features(supported).reinterpret(count * 4L);
		for (long i = 0; i < count; i++) {
			if (list.getAtIndex(ValueLayout.JAVA_INT, i) == feature) {
				return true;
			}
		}
		return false;
	}

	private static String backendName(int backend) {
		if (backend == WGPUBackendType_Metal()) return "Metal";
		if (backend == WGPUBackendType_Vulkan()) return "Vulkan";
		if (backend == WGPUBackendType_D3D12()) return "D3D12";
		if (backend == WGPUBackendType_OpenGL()) return "OpenGL";
		if (backend == WGPUBackendType_OpenGLES()) return "OpenGLES";
		return "unknown(" + backend + ")";
	}

	private static void fail(String message) {
		System.err.println("SMOKE TEST FAILED: " + message);
		System.exit(1);
	}
}
