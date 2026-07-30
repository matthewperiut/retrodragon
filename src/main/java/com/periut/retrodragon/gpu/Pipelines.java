package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUBlendComponent;
import com.periut.webgpu.WGPUBlendState;
import com.periut.webgpu.WGPUColorTargetState;
import com.periut.webgpu.WGPUDepthStencilState;
import com.periut.webgpu.WGPUFragmentState;
import com.periut.webgpu.WGPUMultisampleState;
import com.periut.webgpu.WGPUPrimitiveState;
import com.periut.webgpu.WGPURenderPipelineDescriptor;
import com.periut.webgpu.WGPUStencilFaceState;
import com.periut.webgpu.WGPUVertexAttribute;
import com.periut.webgpu.WGPUVertexBufferLayout;
import com.periut.webgpu.WGPUVertexState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Builds render pipelines.
 *
 * <p>A WebGPU pipeline bakes in what GL let you change per-draw -- blend, depth, cull, topology,
 * shaders, target format -- so beta's freely-mutating fixed-function state has to be reduced to a
 * finite set of pipeline objects and cached. {@code shim/PipelineKey} is that reduction; this turns
 * one into a real pipeline.
 *
 * <p>Phase 1 keeps it minimal on purpose: no vertex buffers (the shader generates positions from
 * {@code vertex_index}), no bind groups, no depth. Those arrive with phases 4 and 5, once there is
 * geometry and state worth binding. Getting a triangle onto a texture first means the harder pieces
 * land on a chain already known to work.
 */
public final class Pipelines {
	private Pipelines() {
	}

	/**
	 * Minimal pipeline: one colour target, triangle list, no vertex buffers.
	 *
	 * @param format the target texture format; must match the render pass attachment or Dawn rejects
	 *               the draw at submit time rather than at pipeline creation.
	 */
	public static MemorySegment create(WebGPUContext ctx, Arena arena,
			MemorySegment shaderModule, int format) {
		MemorySegment desc = WGPURenderPipelineDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPURenderPipelineDescriptor.label(desc), "retrodragon");

		MemorySegment vertex = WGPURenderPipelineDescriptor.vertex(desc);
		WGPUVertexState.module(vertex, shaderModule);
		Shaders.stringView(arena, WGPUVertexState.entryPoint(vertex), "vs");
		WGPUVertexState.bufferCount(vertex, 0);

		MemorySegment primitive = WGPURenderPipelineDescriptor.primitive(desc);
		WGPUPrimitiveState.topology(primitive, WGPUPrimitiveTopology_TriangleList());
		WGPUPrimitiveState.cullMode(primitive, WGPUCullMode_None());
		WGPUPrimitiveState.frontFace(primitive, WGPUFrontFace_CCW());

		// count=1 mask=~0 is "no MSAA"; leaving these zero is a validation error, not a default.
		MemorySegment multisample = WGPURenderPipelineDescriptor.multisample(desc);
		WGPUMultisampleState.count(multisample, 1);
		WGPUMultisampleState.mask(multisample, 0xFFFFFFFF);
		WGPUMultisampleState.alphaToCoverageEnabled(multisample, 0);

		MemorySegment target = WGPUColorTargetState.allocate(arena);
		WGPUColorTargetState.format(target, format);
		WGPUColorTargetState.writeMask(target, Flags.COLOR_WRITE_ALL);

		MemorySegment fragment = WGPUFragmentState.allocate(arena);
		WGPUFragmentState.module(fragment, shaderModule);
		Shaders.stringView(arena, WGPUFragmentState.entryPoint(fragment), "fs");
		WGPUFragmentState.targetCount(fragment, 1);
		WGPUFragmentState.targets(fragment, target);
		WGPURenderPipelineDescriptor.fragment(desc, fragment);

		MemorySegment pipeline = wgpuDeviceCreateRenderPipeline(ctx.device(), desc);
		if (pipeline.equals(MemorySegment.NULL)) {
			System.err.println("[RetroDragon] render pipeline creation failed");
		}
		return pipeline;
	}

	/**
	 * The full pipeline: vertex layout, blend, depth, cull, topology, explicit bind group layout.
	 *
	 * <p>This is what the fixed-function cache builds -- one of these per distinct
	 * {@code shim/PipelineKey}, created lazily and kept forever, because beta's state churns per draw
	 * but the SET of states it visits is small and closed.
	 */
	public static MemorySegment create(WebGPUContext ctx, Arena arena, PipelineSpec spec) {
		MemorySegment desc = WGPURenderPipelineDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPURenderPipelineDescriptor.label(desc), spec.label);
		if (!spec.pipelineLayout.equals(MemorySegment.NULL)) {
			WGPURenderPipelineDescriptor.layout(desc, spec.pipelineLayout);
		}

		MemorySegment vertex = WGPURenderPipelineDescriptor.vertex(desc);
		WGPUVertexState.module(vertex, spec.shader);
		Shaders.stringView(arena, WGPUVertexState.entryPoint(vertex), spec.vertexEntry);
		if (spec.vertexStride > 0 && spec.attributes.length > 0) {
			MemorySegment attributes = WGPUVertexAttribute.allocateArray(spec.attributes.length, arena);
			for (int i = 0; i < spec.attributes.length; i++) {
				int[] attribute = spec.attributes[i];
				MemorySegment slot = WGPUVertexAttribute.asSlice(attributes, i);
				WGPUVertexAttribute.shaderLocation(slot, attribute[0]);
				WGPUVertexAttribute.offset(slot, attribute[1]);
				WGPUVertexAttribute.format(slot, attribute[2]);
			}
			MemorySegment layout = WGPUVertexBufferLayout.allocate(arena);
			WGPUVertexBufferLayout.arrayStride(layout, spec.vertexStride);
			WGPUVertexBufferLayout.stepMode(layout, WGPUVertexStepMode_Vertex());
			WGPUVertexBufferLayout.attributeCount(layout, spec.attributes.length);
			WGPUVertexBufferLayout.attributes(layout, attributes);
			WGPUVertexState.bufferCount(vertex, 1);
			WGPUVertexState.buffers(vertex, layout);
		} else {
			WGPUVertexState.bufferCount(vertex, 0);
		}

		MemorySegment primitive = WGPURenderPipelineDescriptor.primitive(desc);
		WGPUPrimitiveState.topology(primitive, spec.topology);
		WGPUPrimitiveState.cullMode(primitive, spec.cullMode);
		WGPUPrimitiveState.frontFace(primitive, spec.frontFace);

		if (spec.depthFormat != 0) {
			MemorySegment depth = WGPUDepthStencilState.allocate(arena);
			WGPUDepthStencilState.format(depth, spec.depthFormat);
			WGPUDepthStencilState.depthWriteEnabled(depth,
				spec.depthWrite ? WGPUOptionalBool_True() : WGPUOptionalBool_False());
			// Depth TEST off is expressed as "always passes" plus whatever write mask GL had --
			// WebGPU has no separate enable, and dropping the attachment instead would also drop
			// the write, which is not the same thing.
			WGPUDepthStencilState.depthCompare(depth,
				spec.depthTest ? spec.depthCompare : WGPUCompareFunction_Always());
			// A depth-only format still needs its stencil faces filled in; Undefined here is a
			// validation error, and Always/Keep is the no-op configuration.
			for (MemorySegment face : new MemorySegment[] {
					WGPUDepthStencilState.stencilFront(depth), WGPUDepthStencilState.stencilBack(depth) }) {
				WGPUStencilFaceState.compare(face, WGPUCompareFunction_Always());
				WGPUStencilFaceState.failOp(face, WGPUStencilOperation_Keep());
				WGPUStencilFaceState.depthFailOp(face, WGPUStencilOperation_Keep());
				WGPUStencilFaceState.passOp(face, WGPUStencilOperation_Keep());
			}
			WGPUDepthStencilState.depthBias(depth, spec.depthBias);
			WGPUDepthStencilState.depthBiasSlopeScale(depth, spec.depthBiasSlopeScale);
			WGPUDepthStencilState.depthBiasClamp(depth, spec.depthBiasClamp);
			WGPUDepthStencilState.stencilReadMask(depth, 0);
			WGPUDepthStencilState.stencilWriteMask(depth, 0);
			WGPURenderPipelineDescriptor.depthStencil(desc, depth);
		}

		MemorySegment multisample = WGPURenderPipelineDescriptor.multisample(desc);
		WGPUMultisampleState.count(multisample, spec.sampleCount);
		WGPUMultisampleState.mask(multisample, 0xFFFFFFFF);
		WGPUMultisampleState.alphaToCoverageEnabled(multisample, spec.alphaToCoverage ? 1 : 0);

		MemorySegment target = WGPUColorTargetState.allocate(arena);
		WGPUColorTargetState.format(target, spec.colorFormat);
		WGPUColorTargetState.writeMask(target, Flags.COLOR_WRITE_ALL);
		if (spec.blend) {
			MemorySegment blend = WGPUBlendState.allocate(arena);
			MemorySegment color = WGPUBlendState.color(blend);
			WGPUBlendComponent.operation(color, WGPUBlendOperation_Add());
			WGPUBlendComponent.srcFactor(color, spec.blendSrcColor);
			WGPUBlendComponent.dstFactor(color, spec.blendDstColor);
			MemorySegment alpha = WGPUBlendState.alpha(blend);
			WGPUBlendComponent.operation(alpha, WGPUBlendOperation_Add());
			WGPUBlendComponent.srcFactor(alpha, spec.blendSrcAlpha);
			WGPUBlendComponent.dstFactor(alpha, spec.blendDstAlpha);
			WGPUColorTargetState.blend(target, blend);
		}

		MemorySegment fragment = WGPUFragmentState.allocate(arena);
		WGPUFragmentState.module(fragment, spec.shader);
		Shaders.stringView(arena, WGPUFragmentState.entryPoint(fragment), spec.fragmentEntry);
		WGPUFragmentState.targetCount(fragment, 1);
		WGPUFragmentState.targets(fragment, target);
		WGPURenderPipelineDescriptor.fragment(desc, fragment);

		MemorySegment pipeline = wgpuDeviceCreateRenderPipeline(ctx.device(), desc);
		if (pipeline.equals(MemorySegment.NULL)) {
			System.err.println("[RetroDragon] render pipeline '" + spec.label + "' creation failed");
		}
		return pipeline;
	}
}
