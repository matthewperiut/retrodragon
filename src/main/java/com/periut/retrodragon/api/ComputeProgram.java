package com.periut.retrodragon.api;

import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUBindGroupDescriptor;
import com.periut.webgpu.WGPUBindGroupEntry;
import com.periut.webgpu.WGPUBindGroupLayoutDescriptor;
import com.periut.webgpu.WGPUBindGroupLayoutEntry;
import com.periut.webgpu.WGPUBufferBindingLayout;
import com.periut.webgpu.WGPUComputePipelineDescriptor;
import com.periut.webgpu.WGPUComputeState;
import com.periut.webgpu.WGPUStorageTextureBindingLayout;
import com.periut.webgpu.WGPUTextureBindingLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.periut.webgpu.webgpu_h.*;

/**
 * A compute shader, its bind group layout, and the dispatches an extension records with it.
 *
 * <p>The engine itself computes nothing -- everything it draws is a rasterised triangle -- so this
 * exists entirely for shader extensions, in the same way {@link ShaderResources#createVolume} does.
 * It lives here rather than in {@code gpu/} for that reason, and it is deliberately NOT tied to
 * {@link ShaderResources}: the fixed {@code @group(1)} layout is shaped around what a fragment stage
 * samples, and a compute pass wants storage textures, integer textures and a per-dispatch uniform,
 * none of which belong in a layout every draw program is built against.
 *
 * <h2>Everything is at {@code @group(0)}</h2>
 *
 * A compute pipeline has no per-draw state to keep separate from per-frame state, so there is one
 * group and the program declares it directly:
 *
 * <pre>
 * &#64;group(0) &#64;binding(0) var&lt;uniform&gt; params : Params;              // always, if uniforms were asked for
 * &#64;group(0) &#64;binding(1) var dst : texture_storage_3d&lt;rgba16float, write&gt;;
 * &#64;group(0) &#64;binding(2) var src : texture_3d&lt;f32&gt;;
 * </pre>
 *
 * <p>Sampled bindings carry no sampler. {@code textureLoad} is what a port of GL's
 * {@code texelFetch} wants, it needs no sampler at all, and a compute stage cannot use an implicit
 * derivative -- so offering a filtering sampler here would only invite a binding that is illegal
 * against half the formats a bake wants to read.
 *
 * <h2>Storage textures are write-only</h2>
 *
 * Core WebGPU has no read-write storage texture; {@code readonly_and_readwrite_storage_textures} is
 * an optional feature. So a kernel ported from GL's {@code layout(rgba16f) uniform image2D}, which
 * reads and writes one image, has to become a PING-PONG: the previous contents bound as a sampled
 * texture, the result bound as a storage texture, and the two swapped between dispatches. That is
 * what {@link #createGroup} is for -- build one group per configuration up front and alternate
 * between them, rather than rebuilding a group per dispatch.
 *
 * <h2>Uniforms use a dynamic offset</h2>
 *
 * A queue write is not interleaved with the commands in an encoder: every
 * {@code wgpuQueueWriteBuffer} issued during a frame lands before the frame's command buffer runs,
 * so writing one uniform block twice and dispatching between the writes gives BOTH dispatches the
 * second value. Silently. The block is therefore sliced into fixed slots and a dispatch names the
 * slot it reads, which is the same mechanism the engine's own per-draw uniforms use and for the same
 * reason.
 */
public final class ComputeProgram implements AutoCloseable {

	/** A single {@code @group(0)} binding, as declared to the builder. */
	private record Binding(int index, Kind kind, int dimension, int sampleTypeOrFormat) {
	}

	private enum Kind {
		UNIFORM, SAMPLED, STORAGE
	}

	private final WebGPUContext ctx;
	private final String name;
	private final Arena arena = Arena.ofShared();
	private final List<Binding> bindings;
	private final List<Binding> views;
	private final MemorySegment layout;
	private final MemorySegment module;
	private final MemorySegment pipeline;

	private final GpuBuffer uniforms;
	private final int uniformSlotBytes;
	private final int uniformSlots;
	private final ByteBuffer uniformStaging;

	private ComputeProgram(WebGPUContext ctx, String name, List<Binding> bindings, String wgsl,
			String entryPoint, int uniformBytes, int uniformSlots) {
		this.ctx = ctx;
		this.name = name;
		this.bindings = bindings;
		this.views = bindings.stream().filter(b -> b.kind() != Kind.UNIFORM).toList();
		this.uniformSlots = uniformBytes > 0 ? Math.max(1, uniformSlots) : 0;
		// Every slot is a whole dynamic-offset alignment unit, because that is the granularity a
		// dynamic offset can name at all.
		this.uniformSlotBytes = uniformBytes > 0
			? (uniformBytes + Bindings.UNIFORM_ALIGNMENT - 1) / Bindings.UNIFORM_ALIGNMENT
				* Bindings.UNIFORM_ALIGNMENT
			: 0;
		if (this.uniformSlots > 0) {
			this.uniforms = GpuBuffer.sized(ctx, Flags.BUFFER_USAGE_UNIFORM,
				name + "-uniforms", (long) uniformSlotBytes * this.uniformSlots);
			this.uniformStaging = ByteBuffer
				.allocateDirect(uniformSlotBytes * this.uniformSlots)
				.order(ByteOrder.nativeOrder());
		} else {
			this.uniforms = null;
			this.uniformStaging = null;
		}

		this.layout = buildLayout();
		this.module = Shaders.compile(ctx, arena, name, wgsl);
		if (module.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("compute program '" + name + "' failed to compile");
		}
		MemorySegment pipelineLayout = Bindings.pipelineLayout(ctx, arena, name,
			new MemorySegment[] { layout });

		MemorySegment desc = WGPUComputePipelineDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUComputePipelineDescriptor.label(desc), name);
		WGPUComputePipelineDescriptor.layout(desc, pipelineLayout);
		MemorySegment compute = WGPUComputePipelineDescriptor.compute(desc);
		WGPUComputeState.module(compute, module);
		Shaders.stringView(arena, WGPUComputeState.entryPoint(compute), entryPoint);
		this.pipeline = wgpuDeviceCreateComputePipeline(ctx.device(), desc);
		if (pipeline.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("compute pipeline '" + name + "' creation failed");
		}
	}

	public static Builder builder(WebGPUContext ctx, String name) {
		return new Builder(ctx, name);
	}

	/**
	 * Declares a program's bindings, then builds it.
	 *
	 * <p>Binding indices are given explicitly rather than inferred from declaration order, because
	 * the WGSL states them explicitly too and a mismatch between the two is the one error here whose
	 * symptom -- a kernel reading a texture it did not mean to -- is invisible in a log.
	 */
	public static final class Builder {
		private final WebGPUContext ctx;
		private final String name;
		private final List<Binding> bindings = new ArrayList<>();
		private String wgsl;
		private String entryPoint = "main";
		private int uniformBytes;
		private int uniformSlots = 1;

		private Builder(WebGPUContext ctx, String name) {
			this.ctx = ctx;
			this.name = name;
		}

		/**
		 * Reserves {@code bytes} of uniform block at {@code @binding(0)}, in {@code slots}
		 * independently-addressable copies.
		 *
		 * @param slots how many dispatches in one frame need DIFFERENT parameters. One is the common
		 *              case; a denoiser run several times with a growing step needs one per pass.
		 */
		public Builder uniforms(int bytes, int slots) {
			this.uniformBytes = bytes;
			this.uniformSlots = slots;
			bindings.add(new Binding(0, Kind.UNIFORM, 0, 0));
			return this;
		}

		/**
		 * A write-only storage texture, declared in WGSL as
		 * {@code texture_storage_{2d,3d}<format, write>}.
		 *
		 * @param dimension one of {@code WGPUTextureViewDimension_*}
		 * @param format    one of {@code WGPUTextureFormat_*}; it must be one of core WebGPU's
		 *                  storage-capable formats and must match the WGSL letter for letter
		 */
		public Builder storageTexture(int binding, int dimension, int format) {
			bindings.add(new Binding(binding, Kind.STORAGE, dimension, format));
			return this;
		}

		/**
		 * A sampled texture read with {@code textureLoad}.
		 *
		 * @param sampleType {@code WGPUTextureSampleType_UnfilterableFloat} for a float texture,
		 *                   {@code _Uint} for {@code texture_*<u32>}, {@code _Sint} for
		 *                   {@code texture_*<i32>}. Unfilterable rather than
		 *                   {@code WGPUTextureSampleType_Float} because no sampler is bound and
		 *                   several formats a bake wants -- rgba32float above all -- are not
		 *                   filterable and are rejected outright against a filtering declaration.
		 */
		public Builder texture(int binding, int dimension, int sampleType) {
			bindings.add(new Binding(binding, Kind.SAMPLED, dimension, sampleType));
			return this;
		}

		public Builder source(String wgsl) {
			this.wgsl = wgsl;
			return this;
		}

		public Builder entryPoint(String entryPoint) {
			this.entryPoint = entryPoint;
			return this;
		}

		public ComputeProgram build() {
			if (wgsl == null || wgsl.isEmpty()) {
				throw new IllegalArgumentException("compute program '" + name + "' has no source");
			}
			return new ComputeProgram(ctx, name, List.copyOf(bindings), wgsl, entryPoint,
				uniformBytes, uniformSlots);
		}
	}

	// --- uniforms --------------------------------------------------------------------------------

	/**
	 * The CPU side of one uniform slot, positioned at its start and limited to its size.
	 *
	 * <p>A raw {@link ByteBuffer} rather than a float array because a compute kernel's parameters are
	 * usually a mix of {@code i32} and {@code f32} -- a grid size and a falloff -- and reinterpreting
	 * ints through {@code Float.intBitsToFloat} to pass them as floats is exactly the kind of clever
	 * that breaks the first time somebody reorders a field.
	 *
	 * <p>Fill it, then call {@link #uploadUniforms()} before the frame's dispatches.
	 */
	public ByteBuffer uniformSlot(int slot) {
		if (uniformStaging == null) {
			throw new IllegalStateException("compute program '" + name + "' declared no uniforms");
		}
		if (slot < 0 || slot >= uniformSlots) {
			throw new IllegalArgumentException("uniform slot " + slot + " outside 0.."
				+ (uniformSlots - 1));
		}
		uniformStaging.clear();
		uniformStaging.position(slot * uniformSlotBytes).limit((slot + 1) * uniformSlotBytes);
		return uniformStaging.slice().order(ByteOrder.nativeOrder());
	}

	/** Uploads every slot. Call once per frame, before the dispatches that read them. */
	public void uploadUniforms() {
		if (uniformStaging == null) {
			return;
		}
		uniformStaging.clear();
		uniforms.write(uniformStaging, uniformSlotBytes * uniformSlots);
		uniformStaging.clear();
	}

	// --- bind groups -----------------------------------------------------------------------------

	/**
	 * A bind group for one configuration of this program's textures.
	 *
	 * <p>Views are given in the order the non-uniform bindings were DECLARED to the builder, not by
	 * binding index -- which are the same thing whenever the declarations are in index order, and
	 * they should be.
	 *
	 * <p>Held by the caller and reused. A group is an object the GPU reads while the frame that
	 * references it executes, so creating one per dispatch means either leaking them or freeing one
	 * that is still in flight; building the two or three that a ping-pong needs at startup avoids the
	 * question entirely.
	 */
	public MemorySegment createGroup(String label, MemorySegment... textureViews) {
		if (textureViews.length != views.size()) {
			throw new IllegalArgumentException("compute program '" + name + "' declares "
				+ views.size() + " texture binding(s); " + textureViews.length + " given");
		}
		try (Arena tmp = Arena.ofConfined()) {
			int count = bindings.size();
			MemorySegment entries = WGPUBindGroupEntry.allocateArray(count, tmp);
			int at = 0;
			int viewIndex = 0;
			for (Binding binding : bindings) {
				MemorySegment entry = WGPUBindGroupEntry.asSlice(entries, at++);
				WGPUBindGroupEntry.binding(entry, binding.index());
				if (binding.kind() == Kind.UNIFORM) {
					WGPUBindGroupEntry.buffer(entry, uniforms.handle());
					WGPUBindGroupEntry.offset(entry, 0);
					// The BINDING is one slot wide; the dynamic offset at dispatch time picks which.
					WGPUBindGroupEntry.size(entry, uniformSlotBytes);
				} else {
					MemorySegment view = textureViews[viewIndex++];
					if (view == null || view.equals(MemorySegment.NULL)) {
						throw new IllegalArgumentException("compute program '" + name + "' binding "
							+ binding.index() + " was given a null view; a bind group with a hole in"
							+ " it is rejected whole, not per entry");
					}
					WGPUBindGroupEntry.textureView(entry, view);
				}
			}

			MemorySegment desc = WGPUBindGroupDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUBindGroupDescriptor.label(desc), label);
			WGPUBindGroupDescriptor.layout(desc, layout);
			WGPUBindGroupDescriptor.entryCount(desc, count);
			WGPUBindGroupDescriptor.entries(desc, entries);

			MemorySegment group = wgpuDeviceCreateBindGroup(ctx.device(), desc);
			if (group.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("compute bind group '" + label + "' creation failed");
			}
			return group;
		}
	}

	/** Frees a group from {@link #createGroup}. Only safe once no frame referencing it is in flight. */
	public static void releaseGroup(MemorySegment group) {
		if (group != null && !group.equals(MemorySegment.NULL)) {
			wgpuBindGroupRelease(group);
		}
	}

	// --- dispatch --------------------------------------------------------------------------------

	/**
	 * Records one dispatch into the frame, in a compute pass of its own.
	 *
	 * <p>The counts are WORKGROUPS, not invocations -- so a kernel with
	 * {@code @workgroup_size(4,4,4)} over a 64-cell cube dispatches (16,16,16). Getting that wrong by
	 * the workgroup size is the classic way to compute a sixty-fourth of the intended volume and see
	 * a result that looks merely dim.
	 *
	 * @param uniformSlot which parameter block this dispatch reads; 0 when the program declared one
	 */
	public void dispatch(Frame frame, MemorySegment group, int uniformSlot,
			int workgroupsX, int workgroupsY, int workgroupsZ) {
		if (workgroupsX <= 0 || workgroupsY <= 0 || workgroupsZ <= 0) {
			return;
		}
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment pass = frame.beginComputePass(tmp, name);
			wgpuComputePassEncoderSetPipeline(pass, pipeline);
			if (uniformSlots > 0) {
				MemorySegment offsets = tmp.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0,
					uniformSlot * uniformSlotBytes);
				wgpuComputePassEncoderSetBindGroup(pass, 0, group, 1, offsets);
			} else {
				wgpuComputePassEncoderSetBindGroup(pass, 0, group, 0, MemorySegment.NULL);
			}
			wgpuComputePassEncoderDispatchWorkgroups(pass, workgroupsX, workgroupsY, workgroupsZ);
			frame.endComputePass();
		}
	}

	public String name() {
		return name;
	}

	// --- construction ----------------------------------------------------------------------------

	private MemorySegment buildLayout() {
		int count = bindings.size();
		MemorySegment entries = WGPUBindGroupLayoutEntry.allocateArray(count, arena);
		int at = 0;
		for (Binding binding : bindings) {
			MemorySegment entry = WGPUBindGroupLayoutEntry.asSlice(entries, at++);
			WGPUBindGroupLayoutEntry.binding(entry, binding.index());
			WGPUBindGroupLayoutEntry.visibility(entry, Flags.SHADER_STAGE_COMPUTE);
			switch (binding.kind()) {
				case UNIFORM -> {
					MemorySegment buffer = WGPUBindGroupLayoutEntry.buffer(entry);
					WGPUBufferBindingLayout.type(buffer, WGPUBufferBindingType_Uniform());
					WGPUBufferBindingLayout.hasDynamicOffset(buffer, 1);
					WGPUBufferBindingLayout.minBindingSize(buffer, uniformSlotBytes);
				}
				case SAMPLED -> {
					MemorySegment texture = WGPUBindGroupLayoutEntry.texture(entry);
					WGPUTextureBindingLayout.sampleType(texture, binding.sampleTypeOrFormat());
					WGPUTextureBindingLayout.viewDimension(texture, binding.dimension());
					WGPUTextureBindingLayout.multisampled(texture, 0);
				}
				case STORAGE -> {
					MemorySegment storage = WGPUBindGroupLayoutEntry.storageTexture(entry);
					WGPUStorageTextureBindingLayout.access(storage,
						WGPUStorageTextureAccess_WriteOnly());
					WGPUStorageTextureBindingLayout.format(storage, binding.sampleTypeOrFormat());
					WGPUStorageTextureBindingLayout.viewDimension(storage, binding.dimension());
				}
			}
		}

		MemorySegment desc = WGPUBindGroupLayoutDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUBindGroupLayoutDescriptor.label(desc), name);
		WGPUBindGroupLayoutDescriptor.entryCount(desc, count);
		WGPUBindGroupLayoutDescriptor.entries(desc, entries);

		MemorySegment created = wgpuDeviceCreateBindGroupLayout(ctx.device(), desc);
		if (created.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("compute bind group layout for '" + name + "' failed");
		}
		return created;
	}

	@Override
	public void close() {
		if (!pipeline.equals(MemorySegment.NULL)) {
			wgpuComputePipelineRelease(pipeline);
		}
		if (!module.equals(MemorySegment.NULL)) {
			wgpuShaderModuleRelease(module);
		}
		if (!layout.equals(MemorySegment.NULL)) {
			wgpuBindGroupLayoutRelease(layout);
		}
		if (uniforms != null) {
			uniforms.close();
		}
		arena.close();
	}
}
