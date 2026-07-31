package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.GpuTexture;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GlState;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Replays a frame's captured {@link DrawList} into a render pass.
 *
 * <h2>Three uploads per frame, not three per draw</h2>
 *
 * All the frame's vertices go into one buffer and all its uniform blocks into another, both written
 * once. A draw then costs a dynamic uniform offset and a vertex-range -- no buffer creation, no bind
 * group creation, no map/unmap. That is the whole reason the capture accumulates a frame before
 * submitting anything: beta issues hundreds of small draws, and per-draw allocation is what makes
 * naive translation layers slower than the API they replace.
 *
 * <p>The index buffers are static. Quads and fans both use patterns that are the same for every
 * batch when expressed relative to zero, so one buffer of each is bound once and every draw offsets
 * into the vertex buffer with {@code baseVertex} instead of needing its own indices.
 *
 * <p>Bind groups are cached per texture. A bind group is (uniform buffer, sampler, texture view);
 * only the texture varies between draws, since the uniform offset is dynamic -- so the cache is
 * small and stable, and is rebuilt only when the uniform buffer is reallocated underneath it.
 */
public final class ImmediateRenderer implements AutoCloseable {
	/**
	 * The largest quad run one indexed draw covers, and therefore the size of the shared index
	 * buffer: 65536 quads is 1.5 MB of 32-bit indices.
	 *
	 * <p>Without a bound the buffer would scale with the world. Terrain draws quads now, and the
	 * arena merges adjacent sections into single batches, so one batch can carry every visible quad
	 * -- at the arena's 128 MB cap that is over a million of them, which would want tens of megabytes
	 * of indices describing a pattern 24 bytes long.
	 *
	 * <p>Splitting is free because the pattern is periodic: quad k of a batch is
	 * {@code 4k + {0,1,2,0,2,3}}, so a run starting at quad k draws the identical indices with the
	 * base vertex advanced by {@code 4k}. A frame that needs the split pays one extra draw call per
	 * 65536 quads, against a frame that today issues a handful in total.
	 */
	private static final int MAX_INDEXED_QUADS = 1 << 16;

	private final WebGPUContext ctx;
	private final Arena arena = Arena.ofShared();
	private final GpuBuffer vertices;
	private final GpuBuffer uniforms;
	private final GpuBuffer quadIndices;
	private final GpuBuffer fanIndices;
	/** Indexed by the bit pattern linear|clamp|mipmap, built on demand. */
	private final Map<Integer, MemorySegment> samplers = new HashMap<>();

	private final Map<Integer, MemorySegment> bindGroups = new HashMap<>();

	private int quadCapacity;
	private int fanCapacity;
	private int drawsLastFrame;
	private int batchesLastFrame;
	/** Bind-group changes issued this frame; the count that per-section terrain would otherwise blow up. */
	private int binds;
	/**
	 * Batches skipped this frame because their buffer was released after being captured.
	 *
	 * <p>A handful during a world change is normal -- the meshes really were freed. A steady count in
	 * ordinary play means geometry is being dropped every frame, and the free is happening too early.
	 */
	private int dropped;

	public ImmediateRenderer(WebGPUContext ctx) {
		this.ctx = ctx;
		this.vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "retrodragon-vertices");
		this.uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "retrodragon-uniforms");
		this.quadIndices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "retrodragon-quad-indices");
		this.fanIndices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "retrodragon-fan-indices");

	}

	/**
	 * Uploads the frame and records it as one render pass per segment.
	 *
	 * <p>Opening and closing the passes here rather than in the caller is what makes beta's
	 * mid-frame {@code glClear} calls work: a WebGPU clear is a pass load op, so "clear the depth
	 * buffer now" can only mean "end this pass and start another".
	 *
	 * @return the number of draw calls issued
	 */
	public int render(Frame frame, MemorySegment colorView, MemorySegment depthView,
			DrawList list, FixedFunctionPipelines pipelines, TextureStore textures) {
		upload(list);
		return render(frame, colorView, NO_AUX, depthView, list, 0, list.batchCount(), pipelines,
			textures, MemorySegment.NULL);
	}

	private static final MemorySegment[] NO_AUX = new MemorySegment[0];

	/**
	 * Moves the frame's vertices, uniforms and indices to the GPU.
	 *
	 * <p>Separate from {@link #render} because a frame is now replayed in SEVERAL ranges -- world,
	 * then GUI, with a shader extension's hooks between them -- and all of them draw from the same
	 * three buffers. Uploading per range would re-send the whole frame once per range.
	 *
	 * <p>Resets the per-frame counters, so it must be called before the first range and not between.
	 */
	public void upload(DrawList list) {
		batchesLastFrame = list.batchCount();
		drawsLastFrame = 0;
		binds = 0;
		dropped = 0;

		if (list.batchCount() > 0) {
			boolean moved = false;
			// Uniforms are uploaded for EVERY batch, including ones drawing from a buffer of their
			// own. Gating this on vertexCount was a serious bug: terrain batches are external, so
			// they contribute nothing to the shared vertex buffer, and a frame made only of terrain
			// -- which is what world load looks like -- left the uniform buffer at capacity zero with
			// a NULL handle. Every bind group built from it was invalid, so every draw in the frame
			// failed, and each failure emitted several multi-line validation messages.
			ByteBuffer uniformData = list.uniformData();
			moved |= uniforms.write(uniformData, uniformData.remaining());
			if (list.vertexCount() > 0) {
				ByteBuffer vertexData = list.vertexData();
				moved |= vertices.write(vertexData, vertexData.remaining());
			}
			if (moved) {
				// A reallocated uniform buffer leaves every cached bind group pointing at a freed
				// buffer. Dawn rejects the draw rather than corrupting anything, so the symptom is
				// a frame that silently disappears.
				invalidateBindGroups();
			}
			ensureIndices(list);
		}
	}

	/**
	 * Records one range of the frame into passes over the given attachments.
	 *
	 * <p>Opening and closing the passes here rather than in the caller is what makes beta's
	 * mid-frame {@code glClear} calls work: a WebGPU clear is a pass load op, so "clear the depth
	 * buffer now" can only mean "end this pass and start another".
	 *
	 * @param rangeFirst first batch to draw, inclusive
	 * @param rangeEnd   last batch, exclusive. Segments are clipped to the range, so a clear that
	 *                   falls inside it still starts a new pass and one outside it is skipped.
	 * @param shaderGroup a shader extension's {@code @group(1)}, or {@link MemorySegment#NULL}
	 * @return the number of draw calls issued, cumulative across ranges since {@link #upload}
	 */
	public int render(Frame frame, MemorySegment colorView, MemorySegment[] aux,
			MemorySegment depthView, DrawList list, int rangeFirst, int rangeEnd,
			FixedFunctionPipelines pipelines, TextureStore textures, MemorySegment shaderGroup) {
		return render(frame, colorView, aux, depthView, list, rangeFirst, rangeEnd, pipelines,
			textures, shaderGroup, false);
	}

	/**
	 * @param suppressColorClear true when a shader extension has already painted every pixel -- see
	 *                           {@link com.periut.retrodragon.api.ShaderApi#claimWorldColorClear}.
	 *                           The DEPTH clear is unaffected: the world needs it either way.
	 */
	public int render(Frame frame, MemorySegment colorView, MemorySegment[] aux,
			MemorySegment depthView, DrawList list, int rangeFirst, int rangeEnd,
			FixedFunctionPipelines pipelines, TextureStore textures, MemorySegment shaderGroup,
			boolean suppressColorClear) {
		try (Arena frameArena = Arena.ofConfined()) {
			for (int segment = 0; segment < list.segmentCount(); segment++) {
				int segmentFirst = list.segmentFirstBatch(segment);
				int segmentEnd = list.segmentEndBatch(segment);
				// A clear belongs to the range that CONTAINS its boundary. Applying it to every range
				// the segment overlaps would clear the world target again halfway through the frame.
				boolean ownsClear = segmentFirst >= rangeFirst && segmentFirst < rangeEnd
					|| segmentFirst < rangeFirst && segment == 0 && rangeFirst == 0;
				int first = Math.max(segmentFirst, rangeFirst);
				int end = Math.min(segmentEnd, rangeEnd);
				if (first >= end && !ownsClear) {
					continue;
				}
				boolean clearsColor = ownsClear && !suppressColorClear
					&& list.segmentClearsColor(segment);
				boolean clearsDepth = ownsClear && list.segmentClearsDepth(segment);
				// An empty segment that clears nothing has no effect at all; skipping it avoids a
				// pass whose only job is to load and store the attachments unchanged.
				if (first >= end && !clearsColor && !clearsDepth) {
					continue;
				}
				MemorySegment pass = frame.beginPass(frameArena, colorView, aux, clearsColor,
					list.segmentClear(segment, 0), list.segmentClear(segment, 1),
					list.segmentClear(segment, 2), list.segmentClear(segment, 3),
					depthView, clearsDepth);
				drawSegment(pass, frameArena, list, first, Math.max(first, end), pipelines, textures,
					shaderGroup);
				frame.endPass();
			}
		}
		if (dropped > 0) {
			com.periut.retrodragon.RetroDragon.detail(
				"{} batches dropped: their buffer was released after being captured", dropped);
			dropped = 0;
		}
		return drawsLastFrame;
	}

	private void drawSegment(MemorySegment pass, Arena frameArena, DrawList list, int first, int end,
			FixedFunctionPipelines pipelines, TextureStore textures, MemorySegment shaderGroup) {
		if (first == end) {
			return;
		}
		boolean hasShaderGroup = !shaderGroup.equals(MemorySegment.NULL);
		// Whether group 1 is currently bound AND still compatible with the pipeline in force.
		// Setting a pipeline whose layout does not include group 1 unbinds it, per WebGPU's
		// bind-group compatibility rules -- so the flag has to be cleared there, not merely on a
		// pass boundary, or the first extension draw after a stock one would run with nothing bound.
		boolean groupBound = false;
		MemorySegment offsets = frameArena.allocate(ValueLayout.JAVA_INT, 1);
		long previousKey = -1L;
		boolean quadsBound = false;
		boolean fansBound = false;
		Object boundBuffer = this;
		// Sentinels that cannot equal a real value, so the first batch always binds.
		int previousTexture = Integer.MIN_VALUE;
		int previousUniformOffset = -1;

		for (int batch = first; batch < end; batch++) {
			int count = list.count(batch);
			// Caster-only geometry is recorded for the shadow map and must never reach the screen;
			// see DrawPhase.CASTER_ONLY.
			if (count <= 0
					|| list.phase(batch) == com.periut.retrodragon.api.DrawPhase.CASTER_ONLY) {
				continue;
			}
			int glMode = list.glMode(batch);
			long key = list.pipelineKey(batch);

			// Terrain sections keep their geometry in buffers of their own; everything else was
			// copied into this frame's shared one. Rebinding only on a change keeps a run of
			// sections from re-issuing the same call.
			Object source = list.buffer(batch);
			GpuBuffer buffer = source == null ? vertices : (GpuBuffer) source;
			// A section's buffer can be released between the capture and this pass -- the game frees
			// meshes mid-frame as sections are recycled, and world unload frees every one of them. The
			// batch describes geometry that no longer exists, so it is dropped. Binding it instead
			// would UNSET slot 0 (a null buffer is not an error to WebGPU) and the next draw would
			// fail validation, taking the whole command buffer and therefore the whole frame with it.
			if (!buffer.valid()) {
				dropped++;
				// Force the next batch to rebind: the slot now holds whatever the last valid batch set.
				boundBuffer = this;
				continue;
			}
			if (source != boundBuffer) {
				boundBuffer = source;
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, buffer.handle(), 0, buffer.capacity());
			}

			// Redundant state changes are the cheapest thing to skip and the most common: beta
			// draws long runs of GUI quads that differ only in their uniform block.
			if (key != previousKey) {
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(key));
				previousKey = key;
				groupBound = false;
			}
			// Group 1 is per-FRAME: one bind for every extension draw in the pass, versus group 0's
			// bind per state change. That asymmetry is the reason the two are separate groups.
			if (hasShaderGroup && !groupBound && usesShaderGroup(key)) {
				wgpuRenderPassEncoderSetBindGroup(pass, 1, shaderGroup, 0, MemorySegment.NULL);
				groupBound = true;
			}
			// The bind group and its dynamic uniform offset are one call, so a batch that changes
			// neither can skip it. Terrain makes this worth doing: every section in a 1024-block
			// cell shares one modelview and therefore one uniform slot, so a run of several hundred
			// sections needs a single bind.
			int texture = list.texture(batch);
			int uniformOffset = list.uniformOffset(batch);
			if (texture != previousTexture || uniformOffset != previousUniformOffset) {
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup(texture, textures, pipelines), 1,
					writeOffset(offsets, uniformOffset));
				previousTexture = texture;
				previousUniformOffset = uniformOffset;
				binds++;
			}

			switch (Primitives.indexing(glMode)) {
				case QUADS -> {
					if (!quadsBound) {
						wgpuRenderPassEncoderSetIndexBuffer(pass, quadIndices.handle(),
							WGPUIndexFormat_Uint32(), 0, quadIndices.capacity());
						quadsBound = true;
						fansBound = false;
					}
					// Split at MAX_INDEXED_QUADS. Terrain merges hundreds of sections into one
					// batch, so a batch can hold more quads than it is worth keeping indices for;
					// see MAX_INDEXED_QUADS. The pattern repeats every four vertices, so a run
					// starting at quad k is the same indices with the base vertex moved on by 4k --
					// which makes splitting exact rather than approximate.
					int quads = count / QuadIndices.VERTICES_PER_QUAD;
					int baseVertex = list.firstVertex(batch);
					for (int done = 0; done < quads; done += MAX_INDEXED_QUADS) {
						int chunk = Math.min(MAX_INDEXED_QUADS, quads - done);
						wgpuRenderPassEncoderDrawIndexed(pass,
							chunk * QuadIndices.INDICES_PER_QUAD, 1, 0,
							baseVertex + done * QuadIndices.VERTICES_PER_QUAD, 0);
						if (done > 0) {
							drawsLastFrame++;
						}
					}
				}
				case FAN -> {
					if (!fansBound) {
						wgpuRenderPassEncoderSetIndexBuffer(pass, fanIndices.handle(),
							WGPUIndexFormat_Uint32(), 0, fanIndices.capacity());
						fansBound = true;
						quadsBound = false;
					}
					wgpuRenderPassEncoderDrawIndexed(pass, Primitives.indexCount(glMode, count), 1,
						0, list.firstVertex(batch), 0);
				}
				case DIRECT ->
					wgpuRenderPassEncoderDraw(pass, count, 1, list.firstVertex(batch), 0);
			}
			drawsLastFrame++;
		}
	}

	/**
	 * Replays this frame's shadow-casting batches into a depth-only pass.
	 *
	 * <p>The same vertices, the same per-draw uniform blocks and the same bind groups as the visible
	 * frame -- only the PROGRAM changes. The shadow program reads the light's matrices from the
	 * extension's {@code @group(1)} and combines them with the per-draw modelview already in group 0,
	 * so nothing has to be re-uploaded and no second uniform buffer exists.
	 *
	 * <p>This is the same frame the camera is about to see, not the previous one. Every
	 * immediate-mode shadow implementation on this game has had to live with a frame of staleness,
	 * because under GL the world had already been submitted by the time a shadow pass could run.
	 * Here the whole frame is recorded before any of it executes, so the shadow map can simply be
	 * recorded first.
	 *
	 * @param rangeEnd exclusive end of the world's batches -- the GUI must not cast shadows
	 * @param program  the caster program, from the extension's {@link
	 *                 com.periut.retrodragon.api.ShaderExtension.ShadowRequest}
	 * @return how many batches were drawn into the map
	 */
	public int renderShadow(Frame frame, MemorySegment depthView, DrawList list, int rangeEnd,
			FixedFunctionPipelines pipelines, TextureStore textures, MemorySegment shaderGroup,
			int program, int terrainProgram) {
		int drawn = 0;
		try (Arena frameArena = Arena.ofConfined()) {
			MemorySegment pass = frame.beginPass(frameArena, MemorySegment.NULL, NO_AUX, false,
				0.0F, 0.0F, 0.0F, 0.0F, depthView, true);
			MemorySegment offsets = frameArena.allocate(ValueLayout.JAVA_INT, 1);
			long previousKey = -1L;
			boolean quadsBound = false;
			boolean fansBound = false;
			boolean groupBound = false;
			Object boundBuffer = this;
			int previousTexture = Integer.MIN_VALUE;
			int previousUniformOffset = -1;

			for (int batch = 0; batch < rangeEnd; batch++) {
				int count = list.count(batch);
				if (count <= 0 || !com.periut.retrodragon.api.DrawPhase.castsShadow(list.phase(batch))) {
					continue;
				}
				Object source = list.buffer(batch);
				GpuBuffer buffer = source == null ? vertices : (GpuBuffer) source;
				if (!buffer.valid()) {
					boundBuffer = this;
					continue;
				}
				if (source != boundBuffer) {
					boundBuffer = source;
					wgpuRenderPassEncoderSetVertexBuffer(pass, 0, buffer.handle(), 0, buffer.capacity());
				}
				// Which caster: the one matching the layout this batch was captured in. A terrain
				// batch drawn through the fixed-function caster would fetch a normal that its buffer
				// does not contain, and read the next vertex's position as one.
				long original = list.pipelineKey(batch);
				long key = com.periut.retrodragon.shim.PipelineKey.forShadow(original,
					isTerrainLayout(com.periut.retrodragon.shim.PipelineKey.program(original))
						? terrainProgram : program);
				if (key != previousKey) {
					wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(key));
					previousKey = key;
					groupBound = false;
				}
				if (!groupBound && !shaderGroup.equals(MemorySegment.NULL)) {
					wgpuRenderPassEncoderSetBindGroup(pass, 1, shaderGroup, 0, MemorySegment.NULL);
					groupBound = true;
				}
				// The texture still matters: the caster program alpha-tests, or leaves and tall grass
				// cast solid rectangles.
				int texture = list.texture(batch);
				int uniformOffset = list.uniformOffset(batch);
				if (texture != previousTexture || uniformOffset != previousUniformOffset) {
					wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup(texture, textures, pipelines),
						1, writeOffset(offsets, uniformOffset));
					previousTexture = texture;
					previousUniformOffset = uniformOffset;
				}
				int glMode = list.glMode(batch);
				switch (Primitives.indexing(glMode)) {
					case QUADS -> {
						if (!quadsBound) {
							wgpuRenderPassEncoderSetIndexBuffer(pass, quadIndices.handle(),
								WGPUIndexFormat_Uint32(), 0, quadIndices.capacity());
							quadsBound = true;
							fansBound = false;
						}
						int quads = count / QuadIndices.VERTICES_PER_QUAD;
						int baseVertex = list.firstVertex(batch);
						for (int done = 0; done < quads; done += MAX_INDEXED_QUADS) {
							int chunk = Math.min(MAX_INDEXED_QUADS, quads - done);
							wgpuRenderPassEncoderDrawIndexed(pass,
								chunk * QuadIndices.INDICES_PER_QUAD, 1, 0,
								baseVertex + done * QuadIndices.VERTICES_PER_QUAD, 0);
						}
					}
					case FAN -> {
						if (!fansBound) {
							wgpuRenderPassEncoderSetIndexBuffer(pass, fanIndices.handle(),
								WGPUIndexFormat_Uint32(), 0, fanIndices.capacity());
							fansBound = true;
							quadsBound = false;
						}
						wgpuRenderPassEncoderDrawIndexed(pass, Primitives.indexCount(glMode, count), 1,
							0, list.firstVertex(batch), 0);
					}
					case DIRECT ->
						wgpuRenderPassEncoderDraw(pass, count, 1, list.firstVertex(batch), 0);
				}
				drawn++;
			}
			frame.endPass();
		}
		return drawn;
	}

	/** Whether a program reads the engine's packed terrain stream rather than beta's own vertex. */
	private static boolean isTerrainLayout(int program) {
		com.periut.retrodragon.api.ProgramSpec spec =
			com.periut.retrodragon.api.ShaderApi.program(program);
		if (spec != null) {
			return spec.layout() == com.periut.retrodragon.api.ProgramSpec.VertexLayout.TERRAIN;
		}
		return program == com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN
			|| program == com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN_OPAQUE;
	}

	/** Whether the key's program declared {@code @group(1)}; built-ins never do. */
	private static boolean usesShaderGroup(long key) {
		com.periut.retrodragon.api.ProgramSpec spec =
			com.periut.retrodragon.api.ShaderApi.program(
				com.periut.retrodragon.shim.PipelineKey.program(key));
		return spec != null && spec.shaderGroup();
	}

	private static MemorySegment writeOffset(MemorySegment offsets, int value) {
		offsets.setAtIndex(ValueLayout.JAVA_INT, 0, value);
		return offsets;
	}

	/**
	 * Grows the shared index buffers to cover the largest quad and fan batch in this frame.
	 *
	 * <p>32-bit indices throughout. A terrain section easily exceeds 65536 vertices, and switching
	 * index width per batch would mean two buffers and a per-draw decision to save two bytes per
	 * index on the batches that are small enough not to matter.
	 */
	private void ensureIndices(DrawList list) {
		int maxQuads = 0;
		int maxFan = 0;
		for (int batch = 0; batch < list.batchCount(); batch++) {
			int count = list.count(batch);
			switch (Primitives.indexing(list.glMode(batch))) {
				case QUADS -> maxQuads = Math.max(maxQuads,
					Math.min(MAX_INDEXED_QUADS, count / QuadIndices.VERTICES_PER_QUAD));
				case FAN -> maxFan = Math.max(maxFan, count);
				case DIRECT -> {
					// nothing to index
				}
			}
		}
		if (maxQuads > quadCapacity) {
			quadCapacity = Math.max(maxQuads * 2, 4096);
			ByteBuffer data = QuadIndices.forQuads32(quadCapacity);
			quadIndices.write(data, data.remaining());
		}
		if (maxFan > fanCapacity) {
			fanCapacity = Math.max(maxFan * 2, 256);
			ByteBuffer data = QuadIndices.forFan32(fanCapacity);
			fanIndices.write(data, data.remaining());
		}
	}

	/** Samplers are cached by their state; beta uses only a handful of combinations. */
	private MemorySegment sampler(boolean linear, boolean clamp, boolean mipmap) {
		int key = (linear ? 1 : 0) | (clamp ? 2 : 0) | (mipmap ? 4 : 0);
		return samplers.computeIfAbsent(key,
			k -> GpuTexture.sampler(ctx, linear, clamp, mipmap));
	}

	private MemorySegment bindGroup(int textureName, TextureStore textures,
			FixedFunctionPipelines pipelines) {
		MemorySegment cached = bindGroups.get(textureName);
		if (cached != null) {
			return cached;
		}
		// The uniform buffer must exist before anything binds it. A GpuBuffer has no handle until its
		// first write, and a bind group built over a NULL handle is rejected wholesale -- taking every
		// draw in the frame with it.
		uniforms.ensure(GlState.UNIFORM_BYTES);
		GpuTexture texture = textures.get(textureName);
		MemorySegment group = Bindings.fixedFunctionGroup(ctx, arena,
			// One layout for every pipeline, which is exactly what makes this cache reusable across
			// blend and depth changes instead of per-pipeline.
			pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
			sampler(textures.isLinear(textureName), textures.isClamped(textureName),
				texture.mipLevels() > 1),
			texture.view());
		bindGroups.put(textureName, group);
		return group;
	}

	/** Call when a texture is replaced or deleted; its bind group still references the old view. */
	public void invalidate(int textureName) {
		MemorySegment group = bindGroups.remove(textureName);
		if (group != null) {
			wgpuBindGroupRelease(group);
		}
	}

	private void invalidateBindGroups() {
		for (MemorySegment group : bindGroups.values()) {
			wgpuBindGroupRelease(group);
		}
		bindGroups.clear();
	}

	public int drawsLastFrame() {
		return drawsLastFrame;
	}

	public int batchesLastFrame() {
		return batchesLastFrame;
	}

	public int bindsLastFrame() {
		return binds;
	}

	/** See {@link #dropped}. */
	public int droppedLastFrame() {
		return dropped;
	}

	@Override
	public void close() {
		invalidateBindGroups();
		for (MemorySegment sampler : samplers.values()) {
			wgpuSamplerRelease(sampler);
		}
		samplers.clear();
		vertices.close();
		uniforms.close();
		quadIndices.close();
		fanIndices.close();
		arena.close();
	}
}
