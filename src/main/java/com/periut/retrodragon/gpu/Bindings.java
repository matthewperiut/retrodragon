package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUBindGroupDescriptor;
import com.periut.webgpu.WGPUBindGroupEntry;
import com.periut.webgpu.WGPUBindGroupLayoutDescriptor;
import com.periut.webgpu.WGPUBindGroupLayoutEntry;
import com.periut.webgpu.WGPUBufferBindingLayout;
import com.periut.webgpu.WGPUPipelineLayoutDescriptor;
import com.periut.webgpu.WGPUSamplerBindingLayout;
import com.periut.webgpu.WGPUTextureBindingLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Bind group layouts, pipeline layouts and bind groups.
 *
 * <p>The layout is declared ONCE and shared by every pipeline, rather than letting Dawn infer one
 * per pipeline. Inferred layouts are per-pipeline objects, so a bind group built for one pipeline is
 * rejected by another -- which would mean rebuilding the uniform binding every time beta changed
 * blend mode. Declaring it explicitly makes a bind group interchangeable across the whole cache.
 */
public final class Bindings {
	/** The fixed-function group: uniforms at 0, sampler at 1, texture at 2 -- as fixedfunc.wgsl declares. */
	public static final int UNIFORM_BINDING = 0;
	public static final int SAMPLER_BINDING = 1;
	public static final int TEXTURE_BINDING = 2;

	private Bindings() {
	}

	/**
	 * Alignment every dynamic uniform offset must be a multiple of.
	 *
	 * <p>256 is the WebGPU-guaranteed maximum for {@code minUniformBufferOffsetAlignment}, so using
	 * it unconditionally is correct on every adapter and costs 16 bytes of padding per draw against
	 * this project's 240-byte block. Querying the real limit would save that and buy nothing.
	 */
	public static final int UNIFORM_ALIGNMENT = 256;

	/**
	 * The layout {@code fixedfunc.wgsl} expects.
	 *
	 * <p>The uniform binding uses a DYNAMIC offset. Beta's state changes between essentially every
	 * draw, so the alternative is either one uniform buffer per draw or one bind group per draw --
	 * both of which allocate per frame. With a dynamic offset the whole frame's uniforms are one
	 * buffer written once, and a draw costs a 4-byte offset in {@code setBindGroup}.
	 *
	 * @param uniformSize the uniform block's exact size. Declaring it rather than leaving it zero
	 *                    turns a too-small uniform buffer into a creation-time error instead of
	 *                    garbage in the shader.
	 */
	public static MemorySegment fixedFunctionLayout(WebGPUContext ctx, Arena arena, long uniformSize) {
		MemorySegment entries = WGPUBindGroupLayoutEntry.allocateArray(3, arena);

		MemorySegment uniform = WGPUBindGroupLayoutEntry.asSlice(entries, 0);
		WGPUBindGroupLayoutEntry.binding(uniform, UNIFORM_BINDING);
		WGPUBindGroupLayoutEntry.visibility(uniform, Flags.SHADER_STAGE_VERTEX | Flags.SHADER_STAGE_FRAGMENT);
		MemorySegment bufferLayout = WGPUBindGroupLayoutEntry.buffer(uniform);
		WGPUBufferBindingLayout.type(bufferLayout, WGPUBufferBindingType_Uniform());
		WGPUBufferBindingLayout.hasDynamicOffset(bufferLayout, 1);
		WGPUBufferBindingLayout.minBindingSize(bufferLayout, uniformSize);

		MemorySegment sampler = WGPUBindGroupLayoutEntry.asSlice(entries, 1);
		WGPUBindGroupLayoutEntry.binding(sampler, SAMPLER_BINDING);
		WGPUBindGroupLayoutEntry.visibility(sampler, Flags.SHADER_STAGE_FRAGMENT);
		WGPUSamplerBindingLayout.type(WGPUBindGroupLayoutEntry.sampler(sampler),
			WGPUSamplerBindingType_Filtering());

		MemorySegment texture = WGPUBindGroupLayoutEntry.asSlice(entries, 2);
		WGPUBindGroupLayoutEntry.binding(texture, TEXTURE_BINDING);
		WGPUBindGroupLayoutEntry.visibility(texture, Flags.SHADER_STAGE_FRAGMENT);
		MemorySegment textureLayout = WGPUBindGroupLayoutEntry.texture(texture);
		WGPUTextureBindingLayout.sampleType(textureLayout, WGPUTextureSampleType_Float());
		WGPUTextureBindingLayout.viewDimension(textureLayout, WGPUTextureViewDimension_2D());
		WGPUTextureBindingLayout.multisampled(textureLayout, 0);

		MemorySegment desc = WGPUBindGroupLayoutDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUBindGroupLayoutDescriptor.label(desc), "retrodragon-fixedfunc");
		WGPUBindGroupLayoutDescriptor.entryCount(desc, 3);
		WGPUBindGroupLayoutDescriptor.entries(desc, entries);

		MemorySegment layout = wgpuDeviceCreateBindGroupLayout(ctx.device(), desc);
		if (layout.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("bind group layout creation failed");
		}
		return layout;
	}

	public static MemorySegment pipelineLayout(WebGPUContext ctx, Arena arena, String label,
			MemorySegment bindGroupLayout) {
		MemorySegment layouts = arena.allocate(ValueLayout.ADDRESS, 1);
		layouts.setAtIndex(ValueLayout.ADDRESS, 0, bindGroupLayout);

		MemorySegment desc = WGPUPipelineLayoutDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUPipelineLayoutDescriptor.label(desc), label);
		WGPUPipelineLayoutDescriptor.bindGroupLayoutCount(desc, 1);
		WGPUPipelineLayoutDescriptor.bindGroupLayouts(desc, layouts);

		MemorySegment layout = wgpuDeviceCreatePipelineLayout(ctx.device(), desc);
		if (layout.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("pipeline layout creation failed");
		}
		return layout;
	}

	/** Binds a uniform buffer, a sampler and a texture view into one group matching the layout above. */
	public static MemorySegment fixedFunctionGroup(WebGPUContext ctx, Arena arena, MemorySegment layout,
			MemorySegment uniformBuffer, long uniformSize,
			MemorySegment sampler, MemorySegment textureView) {
		MemorySegment entries = WGPUBindGroupEntry.allocateArray(3, arena);

		MemorySegment uniform = WGPUBindGroupEntry.asSlice(entries, 0);
		WGPUBindGroupEntry.binding(uniform, UNIFORM_BINDING);
		WGPUBindGroupEntry.buffer(uniform, uniformBuffer);
		WGPUBindGroupEntry.offset(uniform, 0);
		WGPUBindGroupEntry.size(uniform, uniformSize);

		MemorySegment samplerEntry = WGPUBindGroupEntry.asSlice(entries, 1);
		WGPUBindGroupEntry.binding(samplerEntry, SAMPLER_BINDING);
		WGPUBindGroupEntry.sampler(samplerEntry, sampler);

		MemorySegment textureEntry = WGPUBindGroupEntry.asSlice(entries, 2);
		WGPUBindGroupEntry.binding(textureEntry, TEXTURE_BINDING);
		WGPUBindGroupEntry.textureView(textureEntry, textureView);

		MemorySegment desc = WGPUBindGroupDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUBindGroupDescriptor.label(desc), "retrodragon-fixedfunc");
		WGPUBindGroupDescriptor.layout(desc, layout);
		WGPUBindGroupDescriptor.entryCount(desc, 3);
		WGPUBindGroupDescriptor.entries(desc, entries);

		MemorySegment group = wgpuDeviceCreateBindGroup(ctx.device(), desc);
		if (group.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("bind group creation failed");
		}
		return group;
	}
}
