package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUChainedStruct;
import com.periut.webgpu.WGPUShaderModuleDescriptor;
import com.periut.webgpu.WGPUShaderSourceWGSL;
import com.periut.webgpu.WGPUStringView;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Compiles WGSL into WebGPU shader modules.
 *
 * <p>WGSL rather than SPIR-V for now, deliberately: SPIR-V ingestion is the eventual path (one
 * master shader, glslang, permutations expanded at build time), but it needs a build step, whereas
 * WGSL can be a string today. The distinction is invisible at this layer -- swapping the source
 * type later changes only {@link #compile}.
 *
 * <p>Compilation failures do not throw. Dawn reports them through the device's uncaptured-error
 * callback and returns a module that fails at pipeline creation, so the useful signal is the
 * message, which is logged here rather than converted into an exception nobody can act on.
 */
public final class Shaders {
	private Shaders() {
	}

	/**
	 * @param arena controls how long the module's descriptor memory lives; the returned module is
	 *              refcounted by Dawn independently and must be released by the caller.
	 * @return the shader module, or {@link MemorySegment#NULL} if Dawn refused it.
	 */
	public static MemorySegment compile(WebGPUContext ctx, Arena arena, String label, String wgsl) {
		MemorySegment source = WGPUShaderSourceWGSL.allocate(arena);
		MemorySegment chain = WGPUShaderSourceWGSL.chain(source);
		WGPUChainedStruct.sType(chain, WGPUSType_ShaderSourceWGSL());
		stringView(arena, WGPUShaderSourceWGSL.code(source), wgsl);

		MemorySegment desc = WGPUShaderModuleDescriptor.allocate(arena);
		WGPUShaderModuleDescriptor.nextInChain(desc, source);
		stringView(arena, WGPUShaderModuleDescriptor.label(desc), label);

		MemorySegment module = wgpuDeviceCreateShaderModule(ctx.device(), desc);
		if (module.equals(MemorySegment.NULL)) {
			System.err.println("[RetroDragon] shader '" + label + "' failed to compile");
		}
		return module;
	}

	/**
	 * Fills a WGPUStringView in place.
	 *
	 * <p>Dawn takes pointer+length, not a NUL-terminated string, and does NOT copy -- the bytes must
	 * outlive the call that reads them, which is why they are allocated in the caller's arena
	 * rather than a confined one that closes on return.
	 */
	public static void stringView(Arena arena, MemorySegment view, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		MemorySegment data = arena.allocate(bytes.length + 1);
		MemorySegment.copy(bytes, 0, data, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
		WGPUStringView.data(view, data);
		WGPUStringView.length(view, bytes.length);
	}
}
