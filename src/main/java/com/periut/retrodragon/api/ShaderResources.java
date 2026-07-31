package com.periut.retrodragon.api;

import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.GpuTexture;
import com.periut.retrodragon.gpu.Shaders;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.webgpu.WGPUBindGroupDescriptor;
import com.periut.webgpu.WGPUBindGroupEntry;
import com.periut.webgpu.WGPUBindGroupLayoutDescriptor;
import com.periut.webgpu.WGPUBindGroupLayoutEntry;
import com.periut.webgpu.WGPUBufferBindingLayout;
import com.periut.webgpu.WGPUSamplerBindingLayout;
import com.periut.webgpu.WGPUTextureBindingLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Everything a shader extension binds beyond the engine's own per-draw state: {@code @group(1)}.
 *
 * <h2>Why a second group rather than a bigger first one</h2>
 *
 * The engine's {@code @group(0)} is exactly 256 bytes and completely full -- two matrices and eight
 * vec4s, sized to WebGPU's dynamic-offset alignment so one slot per draw wastes nothing. It is also
 * rewritten and re-bound constantly, because it holds the state beta mutates between draws.
 *
 * <p>An extension's data has the opposite shape: a kilobyte of celestial matrices, palette anchors
 * and world state that changes ONCE PER FRAME and is identical for every draw in it. Appending that
 * to group 0 would round every per-draw slot up to the next kilobyte and re-upload the whole thing
 * hundreds of times a frame. In its own group it is written once, bound once, and left alone.
 *
 * <h2>The contract</h2>
 *
 * The layout is FIXED -- the same bindings for every extension and every program, whether or not a
 * given program uses them all. That is what lets one bind group serve every pipeline in the frame,
 * exactly as the engine's single group-0 layout does. A program declaring fewer bindings than the
 * layout provides is legal and costs nothing.
 *
 * <p>Binding 0 is the extension's own uniform struct, so the extension declares it; everything else
 * is spelled out in {@link #WGSL_PRELUDE}, which is meant to be concatenated into each program's
 * source verbatim.
 */
public final class ShaderResources implements AutoCloseable {
	/** Sampled 2D textures an extension can offer its programs. */
	public static final int TEXTURE_SLOTS = 6;

	/**
	 * The slot the engine binds the rendered world into -- and ONLY for a composite pass.
	 *
	 * <p>It has to be only for the composite. During the world pass that texture is the render
	 * ATTACHMENT, and WebGPU forbids a texture being writable and readable inside one
	 * synchronization scope: bind it in the group every world draw uses and Dawn rejects the whole
	 * command buffer, which drops the frame. The symptom is the window flickering black and the
	 * driver logging about a texture that "includes writable usage and another usage".
	 *
	 * <p>Reserved rather than left to the extension, because the engine owns the target and is the
	 * only thing that knows when it is reallocated. An extension's own textures use 0..4.
	 */
	public static final int WORLD_TARGET_SLOT = TEXTURE_SLOTS - 1;
	/** Sampled 3D textures -- a voxel light volume, a LUT. */
	public static final int VOLUME_SLOTS = 2;

	/**
	 * Sampled 3D INTEGER volumes -- an index, not a colour.
	 *
	 * <p>Separate from {@link #VOLUME_SLOTS} because the two cannot share a binding: a float volume
	 * declares {@code texture_3d<f32>} and an integer one {@code texture_3d<u32>}, and WGSL treats
	 * those as different types against a layout that must name one of them.
	 *
	 * <p>It exists for an INDIRECTION: a per-face atlas needs a volume that answers "which atlas slot
	 * holds this cell's face", and a slot number is not a colour. Storing it in a float volume is
	 * possible and is a trap -- the formats that hold an exact integer past 2048 (r32float) are not
	 * filterable, so they cannot be bound to a float slot at all, and the ones that are filterable
	 * cost a hand-rolled byte unpack that silently loses the top of the range.
	 */
	public static final int UINT_VOLUME_SLOTS = 1;

	private static final int BINDING_UNIFORM = 0;
	private static final int BINDING_LINEAR = 1;
	private static final int BINDING_NEAREST = 2;
	private static final int BINDING_REPEAT = 3;
	private static final int BINDING_SHADOW_SAMPLER = 4;
	private static final int BINDING_SHADOW_MAP = 5;
	private static final int BINDING_TEXTURE0 = 6;
	private static final int BINDING_VOLUME0 = BINDING_TEXTURE0 + TEXTURE_SLOTS;
	private static final int BINDING_UINT_VOLUME0 = BINDING_VOLUME0 + VOLUME_SLOTS;
	private static final int BINDING_COUNT = BINDING_UINT_VOLUME0 + UINT_VOLUME_SLOTS;

	/**
	 * The {@code @group(1)} declarations, minus binding 0.
	 *
	 * <p>Paste it into a program after the extension's own uniform block. It is a plain string rather
	 * than an include directive because WGSL has no preprocessor: there is nothing to hook, and a
	 * concatenation is both the simplest thing that works and the one whose failure mode is a
	 * compile error naming the line.
	 */
	public static final String WGSL_PRELUDE = """
		// --- retrodragon @group(1): the shader-extension resource group ---------------------------
		// Binding 0 is your own uniform struct; declare it yourself, as:
		//     @group(1) @binding(0) var<uniform> shaderUniforms : YourStruct;
		// Every binding below is always present, whether or not this program samples it.
		@group(1) @binding(1)  var linearSampler  : sampler;
		@group(1) @binding(2)  var nearestSampler : sampler;
		@group(1) @binding(3)  var repeatSampler  : sampler;
		@group(1) @binding(4)  var shadowSampler  : sampler_comparison;
		@group(1) @binding(5)  var shadowMap      : texture_depth_2d;
		@group(1) @binding(6)  var tex0 : texture_2d<f32>;
		@group(1) @binding(7)  var tex1 : texture_2d<f32>;
		@group(1) @binding(8)  var tex2 : texture_2d<f32>;
		@group(1) @binding(9)  var tex3 : texture_2d<f32>;
		@group(1) @binding(10) var tex4 : texture_2d<f32>;
		// tex5 is the engine's: the rendered world, bound only while a COMPOSITE pass runs. Sampling
		// it from a world program reads the very attachment that program is writing.
		@group(1) @binding(11) var tex5 : texture_2d<f32>;
		@group(1) @binding(12) var vol0 : texture_3d<f32>;
		@group(1) @binding(13) var vol1 : texture_3d<f32>;
		// An INDEX volume, read with textureLoad and no sampler -- an atlas indirection, not a colour.
		@group(1) @binding(14) var volU0 : texture_3d<u32>;
		""";

	private final WebGPUContext ctx;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment layout;
	private final GpuBuffer uniforms;
	private final int uniformBytes;
	private final ByteBuffer staging;

	private final MemorySegment linearSampler;
	private final MemorySegment nearestSampler;
	private final MemorySegment repeatSampler;
	private final MemorySegment shadowSampler;

	/**
	 * A 1x1 white 2D texture and a 1x1x1 white volume, bound wherever the extension has set nothing.
	 *
	 * <p>Not an optimisation -- a bind group with a hole in it is a validation error, and Dawn rejects
	 * the whole group rather than the one entry. Without placeholders, an extension that leaves any
	 * slot unset loses every draw in the frame, which looks like the renderer broke rather than like
	 * a missing texture.
	 */
	private final GpuTexture placeholder;
	private final MemorySegment placeholderVolume;
	private final MemorySegment placeholderVolumeView;
	private final MemorySegment placeholderUintVolume;
	private final MemorySegment placeholderUintVolumeView;
	private final MemorySegment placeholderDepth;
	private final MemorySegment placeholderDepthView;

	private final MemorySegment[] textures = new MemorySegment[TEXTURE_SLOTS];
	private final MemorySegment[] volumes = new MemorySegment[VOLUME_SLOTS];
	private final MemorySegment[] uintVolumes = new MemorySegment[UINT_VOLUME_SLOTS];
	private MemorySegment shadowMap = MemorySegment.NULL;
	private MemorySegment worldTarget = MemorySegment.NULL;

	private MemorySegment group = MemorySegment.NULL;
	/** Set when a view changed, so the bind group is rebuilt before the next pass that uses it. */
	private boolean dirty = true;

	public ShaderResources(WebGPUContext ctx, int uniformBytes) {
		this.ctx = ctx;
		// Rounded up to the uniform alignment: a bind group's declared minBindingSize must not exceed
		// the buffer, and the buffer is written in whole alignment units.
		this.uniformBytes = Math.max(Bindings.UNIFORM_ALIGNMENT,
			(uniformBytes + Bindings.UNIFORM_ALIGNMENT - 1) / Bindings.UNIFORM_ALIGNMENT
				* Bindings.UNIFORM_ALIGNMENT);
		this.staging = ByteBuffer.allocateDirect(this.uniformBytes).order(ByteOrder.nativeOrder());
		this.uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "retrodragon-shader-uniforms");
		this.uniforms.ensure(this.uniformBytes);

		this.linearSampler = GpuTexture.sampler(ctx, true, true, false);
		this.nearestSampler = GpuTexture.sampler(ctx, false, true, false);
		this.repeatSampler = GpuTexture.sampler(ctx, true, false, false);
		this.shadowSampler = comparisonSampler(ctx);

		this.placeholder = GpuTexture.white(ctx);
		this.placeholderVolume = createVolume(ctx, 1, 1, 1, WGPUTextureFormat_RGBA8Unorm(),
			"retrodragon-placeholder-volume");
		this.placeholderVolumeView = view3d(placeholderVolume);
		this.placeholderUintVolume = createVolume(ctx, 1, 1, 1, WGPUTextureFormat_R32Uint(),
			"retrodragon-placeholder-uint-volume",
			Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_COPY_DST);
		this.placeholderUintVolumeView = view3d(placeholderUintVolume);
		this.placeholderDepth = createDepth(ctx, 1, 1);
		this.placeholderDepthView = wgpuTextureCreateView(placeholderDepth, MemorySegment.NULL);

		this.layout = buildLayout();
	}

	// --- what the extension calls ----------------------------------------------------------------

	/**
	 * Replaces the whole uniform block. Cheap enough to call every frame, which is the intent: an
	 * extension recomputes its world state once per frame and hands the array over.
	 *
	 * <p>Shorter arrays are allowed and leave the tail of the block as it was; longer ones are
	 * truncated rather than rejected, because a program that reads past what it declared gets zeros
	 * and a frame that throws gets nothing.
	 */
	public void setUniforms(float[] data) {
		staging.clear();
		int floats = Math.min(data.length, uniformBytes / 4);
		staging.asFloatBuffer().put(data, 0, floats);
		staging.position(0).limit(uniformBytes);
		uniforms.write(staging, uniformBytes);
		staging.clear();
	}

	/** Binds a 2D texture view into {@code tex0..tex5}; {@code NULL} restores the placeholder. */
	public void setTexture(int slot, MemorySegment view) {
		if (slot < 0 || slot >= WORLD_TARGET_SLOT) {
			throw new IllegalArgumentException("texture slot " + slot + " outside 0.."
				+ (WORLD_TARGET_SLOT - 1) + "; slot " + WORLD_TARGET_SLOT + " is the engine's world"
				+ " target");
		}
		if (textures[slot] != view) {
			textures[slot] = view;
			dirty = true;
		}
	}

	/** Binds a 3D texture view into {@code vol0..vol1}; {@code NULL} restores the placeholder. */
	public void setVolume(int slot, MemorySegment view) {
		if (slot < 0 || slot >= VOLUME_SLOTS) {
			throw new IllegalArgumentException("volume slot " + slot + " outside 0.." + (VOLUME_SLOTS - 1));
		}
		if (volumes[slot] != view) {
			volumes[slot] = view;
			dirty = true;
		}
	}

	/** Binds a 3D INTEGER texture view into {@code volU0}; {@code NULL} restores the placeholder. */
	public void setUintVolume(int slot, MemorySegment view) {
		if (slot < 0 || slot >= UINT_VOLUME_SLOTS) {
			throw new IllegalArgumentException("uint volume slot " + slot + " outside 0.."
				+ (UINT_VOLUME_SLOTS - 1));
		}
		if (uintVolumes[slot] != view) {
			uintVolumes[slot] = view;
			dirty = true;
		}
	}

	/**
	 * Binds the depth texture programs sample as {@code shadowMap}.
	 *
	 * <p>Normally the engine sets this from the shadow pass it ran for the extension; an extension
	 * that renders its own may set it directly. The view must be a depth format with a comparison
	 * sampler -- that is what {@code textureSampleCompare} needs, and it is what gives a shadow edge
	 * hardware-filtered coverage in one tap instead of a hand-rolled PCF loop.
	 */
	public void setShadowMap(MemorySegment view) {
		if (shadowMap != view) {
			shadowMap = view;
			dirty = true;
		}
	}

	/**
	 * Engine-owned: the rendered world, for {@link #compositeGroup()} and nothing else.
	 *
	 * <p>Set when the target is allocated and when a resize replaces it -- never per frame, so the
	 * bind groups are built once and left alone.
	 */
	public void setWorldTarget(MemorySegment view) {
		if (worldTarget != view) {
			worldTarget = view;
			dirty = true;
		}
	}

	// --- what the engine calls -------------------------------------------------------------------

	/** The bind group layout every extension pipeline is built against. Engine-owned. */
	public MemorySegment layout() {
		return layout;
	}

	private MemorySegment emptyLayout = MemorySegment.NULL;
	private MemorySegment emptyGroup = MemorySegment.NULL;

	/**
	 * An empty bind group layout, so a pass with no per-draw state can still put these resources at
	 * {@code @group(1)}.
	 *
	 * <p>A full-screen pass has no vertices, no texture of its own and no matrices -- everything it
	 * reads is in this group. It could declare them at group 0 and skip the empty slot, but then the
	 * same helper functions could not be shared between a post pass and a world program: the
	 * bindings would be at different group indices in each. An empty group costs one
	 * {@code setBindGroup} per pass.
	 */
	public MemorySegment emptyLayout() {
		if (emptyLayout.equals(MemorySegment.NULL)) {
			MemorySegment desc = WGPUBindGroupLayoutDescriptor.allocate(arena);
			Shaders.stringView(arena, WGPUBindGroupLayoutDescriptor.label(desc), "retrodragon-empty");
			WGPUBindGroupLayoutDescriptor.entryCount(desc, 0);
			emptyLayout = wgpuDeviceCreateBindGroupLayout(ctx.device(), desc);
			if (emptyLayout.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("empty bind group layout creation failed");
			}
		}
		return emptyLayout;
	}

	public MemorySegment emptyGroup() {
		if (emptyGroup.equals(MemorySegment.NULL)) {
			try (Arena tmp = Arena.ofConfined()) {
				MemorySegment desc = WGPUBindGroupDescriptor.allocate(tmp);
				Shaders.stringView(tmp, WGPUBindGroupDescriptor.label(desc), "retrodragon-empty");
				WGPUBindGroupDescriptor.layout(desc, emptyLayout());
				WGPUBindGroupDescriptor.entryCount(desc, 0);
				emptyGroup = wgpuDeviceCreateBindGroup(ctx.device(), desc);
				if (emptyGroup.equals(MemorySegment.NULL)) {
					throw new IllegalStateException("empty bind group creation failed");
				}
			}
		}
		return emptyGroup;
	}

	public WebGPUContext context() {
		return ctx;
	}

	/** The bind group for this frame, rebuilt only when a view changed. Engine-owned. */
	public MemorySegment group() {
		if (!dirty && !group.equals(MemorySegment.NULL)) {
			return group;
		}
		refresh();
		return group;
	}

	/**
	 * The same group with the shadow map replaced by a placeholder, for the shadow pass itself.
	 *
	 * <p>A texture may not be readable and writable inside one synchronization scope, and the shadow
	 * pass wants both at once: it RENDERS INTO the shadow map, while every program in this group
	 * declares that same texture as something it SAMPLES. Dawn rejects the whole command buffer for
	 * it -- "usage includes writable usage and another usage in the same synchronization scope" --
	 * so the entire frame is dropped, not just the shadow.
	 *
	 * <p>Binding a placeholder in that one slot is the whole fix. A caster program has no business
	 * sampling the map it is drawing, so nothing is lost; everything else in the group -- the
	 * uniforms it needs for the light's matrices, the samplers, the textures -- is the same object.
	 */
	public MemorySegment casterGroup() {
		if (!dirty && !casterGroup.equals(MemorySegment.NULL)) {
			return casterGroup;
		}
		refresh();
		return casterGroup;
	}

	/**
	 * The group for a COMPOSITE pass: the same resources, plus the rendered world at
	 * {@link #WORLD_TARGET_SLOT}.
	 *
	 * <p>Separate from {@link #group()} for the same reason {@link #casterGroup()} is: the world
	 * target is the world pass's attachment, and a texture cannot be sampled and rendered into at
	 * once. By the time a composite runs the world pass has ended, so here it is just a texture.
	 */
	public MemorySegment compositeGroup() {
		if (!dirty && !compositeGroup.equals(MemorySegment.NULL)) {
			return compositeGroup;
		}
		refresh();
		return compositeGroup;
	}

	private MemorySegment compositeGroup = MemorySegment.NULL;

	/**
	 * Bind groups replaced this session, held until the GPU can no longer be reading them.
	 *
	 * <p>Releasing one the moment it is replaced is a use-after-free, and a spectacular one. A bind
	 * group is referenced by every command buffer recorded against it, and this renderer keeps two
	 * frames in flight -- so a group swapped out during frame N is still being read while frame N-1
	 * executes. Dawn rejects the whole command buffer for it, which drops the entire frame: the
	 * window flickers black, the driver logs errors, and on macOS the compositor is dragged down
	 * with it.
	 *
	 * <p>Retired instead, and freed once enough frames have certainly retired. The list only ever has
	 * entries after a resize or a texture swap, so in steady state this costs one integer compare.
	 */
	private final java.util.ArrayDeque<long[]> retired = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<MemorySegment> retiredGroups = new java.util.ArrayDeque<>();
	private long frameCounter;

	/** Comfortably more than the renderer's frames in flight. */
	private static final long RETIRE_AFTER_FRAMES = 4;

	private void refresh() {
		retire(group);
		retire(casterGroup);
		retire(compositeGroup);
		MemorySegment shadow =
			shadowMap.equals(MemorySegment.NULL) ? placeholderDepthView : shadowMap;
		group = buildGroup(shadow, MemorySegment.NULL);
		casterGroup = buildGroup(placeholderDepthView, MemorySegment.NULL);
		compositeGroup = buildGroup(shadow, worldTarget);
		dirty = false;
	}

	private void retire(MemorySegment bindGroup) {
		if (bindGroup.equals(MemorySegment.NULL)) {
			return;
		}
		retiredGroups.addLast(bindGroup);
		retired.addLast(new long[] { frameCounter });
	}

	/**
	 * Called once a frame by the engine; frees anything retired long enough ago.
	 *
	 * <p>Here rather than at the point of replacement, because the point of replacement is exactly
	 * where it is not safe.
	 */
	public void endFrame() {
		frameCounter++;
		while (!retired.isEmpty()
				&& frameCounter - retired.peekFirst()[0] >= RETIRE_AFTER_FRAMES) {
			retired.removeFirst();
			wgpuBindGroupRelease(retiredGroups.removeFirst());
		}
	}

	private MemorySegment casterGroup = MemorySegment.NULL;

	/**
	 * Called when the uniform buffer may have been reallocated underneath the cached group.
	 *
	 * <p>{@link GpuBuffer#write} can move the buffer, and a bind group holding the old handle is
	 * rejected wholesale -- so the group has to be rebuilt, not merely refreshed.
	 */
	public void invalidate() {
		dirty = true;
	}

	// --- construction ----------------------------------------------------------------------------

	private MemorySegment buildLayout() {
		MemorySegment entries = WGPUBindGroupLayoutEntry.allocateArray(BINDING_COUNT, arena);
		long stages = Flags.SHADER_STAGE_VERTEX | Flags.SHADER_STAGE_FRAGMENT;

		MemorySegment uniform = WGPUBindGroupLayoutEntry.asSlice(entries, BINDING_UNIFORM);
		WGPUBindGroupLayoutEntry.binding(uniform, BINDING_UNIFORM);
		WGPUBindGroupLayoutEntry.visibility(uniform, stages);
		MemorySegment bufferLayout = WGPUBindGroupLayoutEntry.buffer(uniform);
		WGPUBufferBindingLayout.type(bufferLayout, WGPUBufferBindingType_Uniform());
		// NOT dynamic. The block is per-FRAME, so there is one offset and it is zero; a dynamic offset
		// would cost four bytes in every setBindGroup for a number that never changes.
		WGPUBufferBindingLayout.hasDynamicOffset(bufferLayout, 0);
		WGPUBufferBindingLayout.minBindingSize(bufferLayout, uniformBytes);

		sampler(entries, BINDING_LINEAR, WGPUSamplerBindingType_Filtering());
		sampler(entries, BINDING_NEAREST, WGPUSamplerBindingType_Filtering());
		sampler(entries, BINDING_REPEAT, WGPUSamplerBindingType_Filtering());
		sampler(entries, BINDING_SHADOW_SAMPLER, WGPUSamplerBindingType_Comparison());

		MemorySegment shadow = WGPUBindGroupLayoutEntry.asSlice(entries, BINDING_SHADOW_MAP);
		WGPUBindGroupLayoutEntry.binding(shadow, BINDING_SHADOW_MAP);
		WGPUBindGroupLayoutEntry.visibility(shadow, Flags.SHADER_STAGE_FRAGMENT);
		MemorySegment shadowTex = WGPUBindGroupLayoutEntry.texture(shadow);
		WGPUTextureBindingLayout.sampleType(shadowTex, WGPUTextureSampleType_Depth());
		WGPUTextureBindingLayout.viewDimension(shadowTex, WGPUTextureViewDimension_2D());
		WGPUTextureBindingLayout.multisampled(shadowTex, 0);

		for (int i = 0; i < TEXTURE_SLOTS; i++) {
			texture(entries, BINDING_TEXTURE0 + i, WGPUTextureViewDimension_2D(), stages);
		}
		for (int i = 0; i < VOLUME_SLOTS; i++) {
			texture(entries, BINDING_VOLUME0 + i, WGPUTextureViewDimension_3D(), stages);
		}
		for (int i = 0; i < UINT_VOLUME_SLOTS; i++) {
			MemorySegment entry = WGPUBindGroupLayoutEntry.asSlice(entries, BINDING_UINT_VOLUME0 + i);
			WGPUBindGroupLayoutEntry.binding(entry, BINDING_UINT_VOLUME0 + i);
			WGPUBindGroupLayoutEntry.visibility(entry, stages);
			MemorySegment layout = WGPUBindGroupLayoutEntry.texture(entry);
			WGPUTextureBindingLayout.sampleType(layout, WGPUTextureSampleType_Uint());
			WGPUTextureBindingLayout.viewDimension(layout, WGPUTextureViewDimension_3D());
			WGPUTextureBindingLayout.multisampled(layout, 0);
		}

		MemorySegment desc = WGPUBindGroupLayoutDescriptor.allocate(arena);
		Shaders.stringView(arena, WGPUBindGroupLayoutDescriptor.label(desc), "retrodragon-shader-group");
		WGPUBindGroupLayoutDescriptor.entryCount(desc, BINDING_COUNT);
		WGPUBindGroupLayoutDescriptor.entries(desc, entries);

		MemorySegment created = wgpuDeviceCreateBindGroupLayout(ctx.device(), desc);
		if (created.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("shader-extension bind group layout creation failed");
		}
		return created;
	}

	private void sampler(MemorySegment entries, int binding, int type) {
		MemorySegment entry = WGPUBindGroupLayoutEntry.asSlice(entries, binding);
		WGPUBindGroupLayoutEntry.binding(entry, binding);
		WGPUBindGroupLayoutEntry.visibility(entry, Flags.SHADER_STAGE_FRAGMENT);
		WGPUSamplerBindingLayout.type(WGPUBindGroupLayoutEntry.sampler(entry), type);
	}

	private void texture(MemorySegment entries, int binding, int dimension, long stages) {
		MemorySegment entry = WGPUBindGroupLayoutEntry.asSlice(entries, binding);
		WGPUBindGroupLayoutEntry.binding(entry, binding);
		WGPUBindGroupLayoutEntry.visibility(entry, stages);
		MemorySegment layout = WGPUBindGroupLayoutEntry.texture(entry);
		WGPUTextureBindingLayout.sampleType(layout, WGPUTextureSampleType_Float());
		WGPUTextureBindingLayout.viewDimension(layout, dimension);
		WGPUTextureBindingLayout.multisampled(layout, 0);
	}

	private MemorySegment buildGroup(MemorySegment shadowView, MemorySegment worldTargetView) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment entries = WGPUBindGroupEntry.allocateArray(BINDING_COUNT, tmp);

			MemorySegment uniform = WGPUBindGroupEntry.asSlice(entries, BINDING_UNIFORM);
			WGPUBindGroupEntry.binding(uniform, BINDING_UNIFORM);
			WGPUBindGroupEntry.buffer(uniform, uniforms.handle());
			WGPUBindGroupEntry.offset(uniform, 0);
			WGPUBindGroupEntry.size(uniform, uniformBytes);

			samplerEntry(entries, BINDING_LINEAR, linearSampler);
			samplerEntry(entries, BINDING_NEAREST, nearestSampler);
			samplerEntry(entries, BINDING_REPEAT, repeatSampler);
			samplerEntry(entries, BINDING_SHADOW_SAMPLER, shadowSampler);
			viewEntry(entries, BINDING_SHADOW_MAP, shadowView);

			for (int i = 0; i < TEXTURE_SLOTS; i++) {
				MemorySegment view = i == WORLD_TARGET_SLOT ? worldTargetView : textures[i];
				viewEntry(entries, BINDING_TEXTURE0 + i,
					view == null || view.equals(MemorySegment.NULL) ? placeholder.view() : view);
			}
			for (int i = 0; i < VOLUME_SLOTS; i++) {
				MemorySegment view = volumes[i];
				viewEntry(entries, BINDING_VOLUME0 + i,
					view == null || view.equals(MemorySegment.NULL) ? placeholderVolumeView : view);
			}
			for (int i = 0; i < UINT_VOLUME_SLOTS; i++) {
				MemorySegment view = uintVolumes[i];
				viewEntry(entries, BINDING_UINT_VOLUME0 + i,
					view == null || view.equals(MemorySegment.NULL)
						? placeholderUintVolumeView : view);
			}

			MemorySegment desc = WGPUBindGroupDescriptor.allocate(tmp);
			Shaders.stringView(tmp, WGPUBindGroupDescriptor.label(desc), "retrodragon-shader-group");
			WGPUBindGroupDescriptor.layout(desc, layout);
			WGPUBindGroupDescriptor.entryCount(desc, BINDING_COUNT);
			WGPUBindGroupDescriptor.entries(desc, entries);

			MemorySegment created = wgpuDeviceCreateBindGroup(ctx.device(), desc);
			if (created.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("shader-extension bind group creation failed");
			}
			return created;
		}
	}

	private static void samplerEntry(MemorySegment entries, int binding, MemorySegment sampler) {
		MemorySegment entry = WGPUBindGroupEntry.asSlice(entries, binding);
		WGPUBindGroupEntry.binding(entry, binding);
		WGPUBindGroupEntry.sampler(entry, sampler);
	}

	private static void viewEntry(MemorySegment entries, int binding, MemorySegment view) {
		MemorySegment entry = WGPUBindGroupEntry.asSlice(entries, binding);
		WGPUBindGroupEntry.binding(entry, binding);
		WGPUBindGroupEntry.textureView(entry, view);
	}

	// --- helpers an extension also needs to build its own volumes --------------------------------

	/**
	 * A 3D texture, sampleable and writable from the CPU.
	 *
	 * <p>Here rather than in {@code gpu/} because the engine itself has no use for one: 3D textures
	 * exist in this renderer entirely so a shader extension can hold a voxel volume.
	 */
	public static MemorySegment createVolume(WebGPUContext ctx, int width, int height, int depth,
			int format, String label) {
		return createVolume(ctx, width, height, depth, format, label,
			Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_COPY_DST
				| Flags.TEXTURE_USAGE_STORAGE_BINDING);
	}

	/**
	 * As above, with the usage flags spelled out.
	 *
	 * <p>Worth spelling out for a volume a compute pass writes. A texture may only be a storage
	 * binding in a format that supports one, so the blanket set above is wrong for, say, an
	 * {@code r8unorm} opacity grid the CPU uploads and the GPU only ever reads -- Dawn rejects the
	 * texture at creation rather than at first use, which is at least prompt but says nothing about
	 * which of the three flags was the problem.
	 */
	public static MemorySegment createVolume(WebGPUContext ctx, int width, int height, int depth,
			int format, String label, long usage) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = com.periut.webgpu.WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, com.periut.webgpu.WGPUTextureDescriptor.label(desc), label);
			com.periut.webgpu.WGPUTextureDescriptor.usage(desc, usage);
			com.periut.webgpu.WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_3D());
			com.periut.webgpu.WGPUTextureDescriptor.format(desc, format);
			com.periut.webgpu.WGPUTextureDescriptor.mipLevelCount(desc, 1);
			com.periut.webgpu.WGPUTextureDescriptor.sampleCount(desc, 1);
			MemorySegment size = com.periut.webgpu.WGPUTextureDescriptor.size(desc);
			com.periut.webgpu.WGPUExtent3D.width(size, width);
			com.periut.webgpu.WGPUExtent3D.height(size, height);
			com.periut.webgpu.WGPUExtent3D.depthOrArrayLayers(size, depth);
			MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("volume '" + label + "' creation failed");
			}
			return texture;
		}
	}

	/**
	 * Uploads a whole 3D texture from tightly packed RGBA8 texels.
	 *
	 * <p>{@code data} must hold {@code width * height * depth * 4} bytes from its position. Rows are
	 * described explicitly rather than inferred -- WebGPU has no {@code GL_UNPACK_ALIGNMENT}, which is
	 * a mercy, since a 3D upload is where that would be hardest to get right.
	 */
	public static void uploadVolume(WebGPUContext ctx, MemorySegment volume,
			int width, int height, int depth, ByteBuffer data) {
		uploadVolume(ctx, volume, width, height, depth, 4, data);
	}

	/**
	 * As above, for a volume whose texel is not four bytes.
	 *
	 * <p>{@code data} must hold {@code width * height * depth * bytesPerTexel} bytes from its
	 * position, tightly packed, and must be direct.
	 *
	 * <p><b>Rows are padded to a 256-byte pitch when they do not already land on one.</b> A texture
	 * upload is a buffer-to-texture copy underneath, and that copy's {@code bytesPerRow} has to be a
	 * multiple of 256 -- so a 64-wide {@code r8unorm} grid, whose natural row is 64 bytes, cannot be
	 * described tightly at all. Padding here rather than at every call site is what lets a volume be
	 * the format it wants to be: the alternative, which this renderer lived with, is storing one
	 * channel in RGBA8 purely because 64 x 4 happens to equal 256.
	 */
	public static void uploadVolume(WebGPUContext ctx, MemorySegment volume,
			int width, int height, int depth, int bytesPerTexel, ByteBuffer data) {
		uploadTexels(ctx, volume, width, height, depth, bytesPerTexel, data);
	}

	/** WebGPU's buffer-to-texture row alignment; see {@link #uploadVolume}. */
	private static final int ROW_PITCH = 256;

	/**
	 * A 2D texture in an arbitrary format, for an extension that needs one the engine's
	 * RGBA8-everywhere {@link GpuTexture} does not cover.
	 *
	 * <p>A light atlas wants {@code rgba16float}, a patch list wants {@code rgba16sint} and an index
	 * list wants {@code r32uint} -- none of which are colours a texture loader would produce, and all
	 * of which a compute pass writes or reads directly.
	 */
	public static MemorySegment createTexture2d(WebGPUContext ctx, int width, int height, int format,
			String label, long usage) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = com.periut.webgpu.WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, com.periut.webgpu.WGPUTextureDescriptor.label(desc), label);
			com.periut.webgpu.WGPUTextureDescriptor.usage(desc, usage);
			com.periut.webgpu.WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
			com.periut.webgpu.WGPUTextureDescriptor.format(desc, format);
			com.periut.webgpu.WGPUTextureDescriptor.mipLevelCount(desc, 1);
			com.periut.webgpu.WGPUTextureDescriptor.sampleCount(desc, 1);
			MemorySegment size = com.periut.webgpu.WGPUTextureDescriptor.size(desc);
			com.periut.webgpu.WGPUExtent3D.width(size, width);
			com.periut.webgpu.WGPUExtent3D.height(size, height);
			com.periut.webgpu.WGPUExtent3D.depthOrArrayLayers(size, 1);
			MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("texture '" + label + "' creation failed");
			}
			return texture;
		}
	}

	/** Uploads a whole 2D texture, padding rows to the 256-byte pitch as {@link #uploadVolume} does. */
	public static void uploadTexture2d(WebGPUContext ctx, MemorySegment texture,
			int width, int height, int bytesPerTexel, ByteBuffer data) {
		uploadTexels(ctx, texture, width, height, 1, bytesPerTexel, data);
	}

	private static void uploadTexels(WebGPUContext ctx, MemorySegment texture,
			int width, int height, int depth, int bytesPerTexel, ByteBuffer data) {
		if (!data.isDirect()) {
			throw new IllegalArgumentException("texture upload needs a direct buffer");
		}
		int tightRow = width * bytesPerTexel;
		int paddedRow = (tightRow + ROW_PITCH - 1) / ROW_PITCH * ROW_PITCH;
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment destination = com.periut.webgpu.WGPUTexelCopyTextureInfo.allocate(tmp);
			com.periut.webgpu.WGPUTexelCopyTextureInfo.texture(destination, texture);
			com.periut.webgpu.WGPUTexelCopyTextureInfo.mipLevel(destination, 0);
			com.periut.webgpu.WGPUTexelCopyTextureInfo.aspect(destination, WGPUTextureAspect_All());
			MemorySegment origin = com.periut.webgpu.WGPUTexelCopyTextureInfo.origin(destination);
			com.periut.webgpu.WGPUOrigin3D.x(origin, 0);
			com.periut.webgpu.WGPUOrigin3D.y(origin, 0);
			com.periut.webgpu.WGPUOrigin3D.z(origin, 0);

			MemorySegment layout = com.periut.webgpu.WGPUTexelCopyBufferLayout.allocate(tmp);
			com.periut.webgpu.WGPUTexelCopyBufferLayout.offset(layout, 0);
			com.periut.webgpu.WGPUTexelCopyBufferLayout.bytesPerRow(layout, paddedRow);
			com.periut.webgpu.WGPUTexelCopyBufferLayout.rowsPerImage(layout, height);

			MemorySegment extent = com.periut.webgpu.WGPUExtent3D.allocate(tmp);
			com.periut.webgpu.WGPUExtent3D.width(extent, width);
			com.periut.webgpu.WGPUExtent3D.height(extent, height);
			com.periut.webgpu.WGPUExtent3D.depthOrArrayLayers(extent, depth);

			MemorySegment segment;
			long bytes;
			if (paddedRow == tightRow) {
				segment = MemorySegment.ofBuffer(data);
				bytes = (long) tightRow * height * depth;
			} else {
				bytes = (long) paddedRow * height * depth;
				segment = tmp.allocate(bytes);
				MemorySegment source = MemorySegment.ofBuffer(data);
				int rows = height * depth;
				for (int row = 0; row < rows; row++) {
					MemorySegment.copy(source, (long) row * tightRow, segment,
						(long) row * paddedRow, tightRow);
				}
			}
			wgpuQueueWriteTexture(ctx.queue(), destination, segment, bytes, layout, extent);
		}
	}

	/** A view onto a 2D texture, for a format whose default view the caller wants named explicitly. */
	public static MemorySegment view2d(MemorySegment texture) {
		MemorySegment view = wgpuTextureCreateView(texture, MemorySegment.NULL);
		if (view.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("texture view creation failed");
		}
		return view;
	}

	/** A view onto a 3D texture, explicitly dimensioned -- the default view of a volume is 3D already,
	 *  but saying so keeps the binding's declared dimension and the view's agreed. */
	public static MemorySegment view3d(MemorySegment volume) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = com.periut.webgpu.WGPUTextureViewDescriptor.allocate(tmp);
			com.periut.webgpu.WGPUTextureViewDescriptor.dimension(desc, WGPUTextureViewDimension_3D());
			com.periut.webgpu.WGPUTextureViewDescriptor.mipLevelCount(desc, 1);
			com.periut.webgpu.WGPUTextureViewDescriptor.arrayLayerCount(desc, 1);
			com.periut.webgpu.WGPUTextureViewDescriptor.aspect(desc, WGPUTextureAspect_All());
			MemorySegment view = wgpuTextureCreateView(volume, desc);
			if (view.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("volume view creation failed");
			}
			return view;
		}
	}

	private static MemorySegment createDepth(WebGPUContext ctx, int width, int height) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = com.periut.webgpu.WGPUTextureDescriptor.allocate(tmp);
			Shaders.stringView(tmp, com.periut.webgpu.WGPUTextureDescriptor.label(desc),
				"retrodragon-placeholder-depth");
			com.periut.webgpu.WGPUTextureDescriptor.usage(desc,
				Flags.TEXTURE_USAGE_TEXTURE_BINDING | Flags.TEXTURE_USAGE_RENDER_ATTACHMENT);
			com.periut.webgpu.WGPUTextureDescriptor.dimension(desc, WGPUTextureDimension_2D());
			com.periut.webgpu.WGPUTextureDescriptor.format(desc, WGPUTextureFormat_Depth32Float());
			com.periut.webgpu.WGPUTextureDescriptor.mipLevelCount(desc, 1);
			com.periut.webgpu.WGPUTextureDescriptor.sampleCount(desc, 1);
			MemorySegment size = com.periut.webgpu.WGPUTextureDescriptor.size(desc);
			com.periut.webgpu.WGPUExtent3D.width(size, width);
			com.periut.webgpu.WGPUExtent3D.height(size, height);
			com.periut.webgpu.WGPUExtent3D.depthOrArrayLayers(size, 1);
			MemorySegment texture = wgpuDeviceCreateTexture(ctx.device(), desc);
			if (texture.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("placeholder depth creation failed");
			}
			return texture;
		}
	}

	/**
	 * The comparison sampler {@code textureSampleCompare} needs.
	 *
	 * <p>Linear, which for a comparison sampler does NOT blur depth values -- it compares four texels
	 * against the reference and blends the four binary results. That is the difference between a
	 * shadow edge one pixel wide and a hard staircase, and it costs one tap.
	 */
	private static MemorySegment comparisonSampler(WebGPUContext ctx) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment desc = com.periut.webgpu.WGPUSamplerDescriptor.allocate(tmp);
			Shaders.stringView(tmp, com.periut.webgpu.WGPUSamplerDescriptor.label(desc),
				"retrodragon-shadow-compare");
			int clamp = WGPUAddressMode_ClampToEdge();
			com.periut.webgpu.WGPUSamplerDescriptor.addressModeU(desc, clamp);
			com.periut.webgpu.WGPUSamplerDescriptor.addressModeV(desc, clamp);
			com.periut.webgpu.WGPUSamplerDescriptor.addressModeW(desc, clamp);
			com.periut.webgpu.WGPUSamplerDescriptor.magFilter(desc, WGPUFilterMode_Linear());
			com.periut.webgpu.WGPUSamplerDescriptor.minFilter(desc, WGPUFilterMode_Linear());
			com.periut.webgpu.WGPUSamplerDescriptor.mipmapFilter(desc, WGPUMipmapFilterMode_Nearest());
			com.periut.webgpu.WGPUSamplerDescriptor.lodMinClamp(desc, 0.0F);
			com.periut.webgpu.WGPUSamplerDescriptor.lodMaxClamp(desc, 0.0F);
			com.periut.webgpu.WGPUSamplerDescriptor.compare(desc, WGPUCompareFunction_LessEqual());
			com.periut.webgpu.WGPUSamplerDescriptor.maxAnisotropy(desc, (short) 1);
			MemorySegment sampler = wgpuDeviceCreateSampler(ctx.device(), desc);
			if (sampler.equals(MemorySegment.NULL)) {
				throw new IllegalStateException("comparison sampler creation failed");
			}
			return sampler;
		}
	}

	@Override
	public void close() {
		if (!group.equals(MemorySegment.NULL)) {
			wgpuBindGroupRelease(group);
			group = MemorySegment.NULL;
		}
		if (!emptyGroup.equals(MemorySegment.NULL)) {
			wgpuBindGroupRelease(emptyGroup);
			emptyGroup = MemorySegment.NULL;
		}
		if (!emptyLayout.equals(MemorySegment.NULL)) {
			wgpuBindGroupLayoutRelease(emptyLayout);
			emptyLayout = MemorySegment.NULL;
		}
		wgpuBindGroupLayoutRelease(layout);
		wgpuSamplerRelease(linearSampler);
		wgpuSamplerRelease(nearestSampler);
		wgpuSamplerRelease(repeatSampler);
		wgpuSamplerRelease(shadowSampler);
		wgpuTextureViewRelease(placeholderVolumeView);
		wgpuTextureRelease(placeholderVolume);
		wgpuTextureViewRelease(placeholderUintVolumeView);
		wgpuTextureRelease(placeholderUintVolume);
		wgpuTextureViewRelease(placeholderDepthView);
		wgpuTextureRelease(placeholderDepth);
		placeholder.close();
		uniforms.close();
		arena.close();
	}
}
