package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.Bindings;
import com.periut.retrodragon.gpu.DepthBuffer;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.GpuTexture;
import com.periut.retrodragon.gpu.Readback;
import com.periut.retrodragon.gpu.RenderTarget;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.shim.DisplayLists;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GeometryCapture;
import com.periut.retrodragon.shim.GlShim;
import com.periut.retrodragon.shim.GlState;
import com.periut.retrodragon.shim.PipelineKey;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Renders beta-format geometry through the fixed-function WGSL pipeline and checks the pixels.
 * {@code ./gradlew webgpuDraw}.
 *
 * <p>Offscreen on purpose: this asserts what was drawn, and a window can only be looked at. It is
 * the first test in the project that proves something other than "Dawn raised no validation errors"
 * -- the whole chain, from a {@code glBegin}/{@code glVertex} sequence through the shim's pipeline
 * key and uniform block to a texel of the right colour in the right place.
 */
public final class WebGpuDrawTest {
	private static final int SIZE = 64;
	/** GL_QUADS, which is what beta's Tessellator emits for essentially everything. */
	private static final int GL_QUADS = 7;

	public static void main(String[] args) {
		int failures = 0;
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("device     = ok (" + ctx.backendName() + ")");
			try (Arena arena = Arena.ofShared();
					RenderTarget target = RenderTarget.create(ctx, arena, SIZE, SIZE);
					DepthBuffer depth = DepthBuffer.create(ctx, arena, SIZE, SIZE);
					FixedFunctionPipelines pipelines =
						FixedFunctionPipelines.create(ctx, target.format(), depth.format())) {

				failures += permutations(pipelines);
				failures += drawQuad(ctx, arena, target, depth, pipelines);
				failures += drawTextured(ctx, arena, target, depth, pipelines);
				failures += replayFrame(ctx, target, depth, pipelines);
				failures += winding(ctx, arena, target, depth, pipelines);
				failures += arenaGrowth(ctx);
				failures += displayListAttributes(ctx, target, depth, pipelines);
				failures += missingTexCoords(ctx, arena, target, depth, pipelines);
				failures += clampAddressing(ctx, arena, target, depth, pipelines);
				failures += lineStrip(ctx, arena, target, depth, pipelines);
				failures += polygonOffset(ctx, arena, target, depth, pipelines);
				failures += wideLine(ctx, arena, target, depth, pipelines, 1.0F, 2);
				failures += wideLine(ctx, arena, target, depth, pipelines, 2.0F, 4);
				failures += terrainLayouts(ctx, target, depth);
			}
		}

		if (failures > 0) {
			System.err.println("WEBGPU DRAW TEST FAILED (" + failures + " check(s))");
			System.exit(1);
		}
		System.out.println("WEBGPU DRAW TEST PASSED");
	}

	/**
	 * Every pipeline state beta can reach has to actually build -- a key Dawn rejects is a draw that
	 * silently disappears at runtime.
	 *
	 * <p>Driven through {@link GlShim} rather than {@link PipelineKey} directly, so it measures what
	 * the game can actually produce. That matters: the raw key space is 36864 states, but the shim
	 * normalises away the ones that cannot differ, and this is the check that the normalisation
	 * holds.
	 */
	private static int permutations(FixedFunctionPipelines pipelines) {
		int failures = 0;
		int count = 0;
		// The blend equations beta uses: standard alpha, additive, multiply, and inverse-source
		// (the last is the block-break overlay).
		int[][] blends = {
			{ 0x0302, 0x0303 }, // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
			{ 1, 1 },           // ONE, ONE
			{ 0x0306, 0 },      // DST_COLOR, ZERO
			{ 0x0302, 1 },      // SRC_ALPHA, ONE
			{ 0, 0x0301 },      // ZERO, ONE_MINUS_SRC_COLOR
			{ 1, 0 },           // ONE, ZERO
		};
		int[] depthFuncs = { 0x0201, 0x0202, 0x0203, 0x0207 }; // LESS, EQUAL, LEQUAL, ALWAYS
		int[] topologies = { PipelineKey.TOPOLOGY_TRIANGLES, PipelineKey.TOPOLOGY_LINES,
			PipelineKey.TOPOLOGY_POINTS };

		try {
			GlShim gl = new GlShim();
			for (int[] blend : blends) {
				for (int depthFunc : depthFuncs) {
					for (int cullFace = 0; cullFace < 3; cullFace++) {
						for (int topology : topologies) {
							for (int flags = 0; flags < 8; flags++) {
								gl.glBlendFunc(blend[0], blend[1]);
								gl.glDepthFunc(depthFunc);
								if (cullFace == 0) {
									gl.glDisable(0x0B44);
								} else {
									gl.glEnable(0x0B44);
									gl.glCullFace(cullFace == 1 ? 0x0405 : 0x0404);
								}
								gl.setTopology(topology);
								gl.glDepthMask((flags & 1) != 0);
								setEnabled(gl, 0x0B71, (flags & 2) != 0); // GL_DEPTH_TEST
								setEnabled(gl, 0x0BE2, (flags & 4) != 0); // GL_BLEND
								pipelines.get(gl.pipelineKey());
								count++;
							}
						}
					}
				}
			}
		} catch (RuntimeException e) {
			System.err.println("permutation failed: " + e.getMessage());
			failures++;
		}
		System.out.println("pipelines  = " + pipelines.builtCount() + " distinct, "
			+ count + " states requested");
		// The enumeration deliberately visits states beta would never combine, so the absolute count
		// is not the signal. The COLLAPSE is: if normalisation regressed, every requested state
		// would build its own pipeline and Metal would start warning about its compiled-variant
		// footprint long before it started failing.
		failures += check(pipelines.builtCount() < count / 2,
			"normalisation should collapse the key space, got " + pipelines.builtCount()
				+ " pipelines for " + count + " states");
		failures += normalisation();
		return failures;
	}

	/**
	 * The normalisation itself, checked directly rather than inferred from a pipeline count. Each of
	 * these is a state change beta makes constantly and that cannot affect a single pixel.
	 */
	private static int normalisation() {
		int failures = 0;
		GlShim gl = new GlShim();

		gl.glDisable(0x0BE2); // GL_BLEND off
		gl.glBlendFunc(0x0302, 0x0303);
		long a = gl.pipelineKey();
		gl.glBlendFunc(1, 1);
		failures += check(gl.pipelineKey() == a, "blend factors must not matter while blending is off");

		gl.glDisable(0x0B71); // GL_DEPTH_TEST off
		gl.glDepthFunc(0x0203);
		long b = gl.pipelineKey();
		gl.glDepthFunc(0x0201);
		failures += check(gl.pipelineKey() == b, "depth func must not matter while depth test is off");
		gl.glDepthMask(true);
		failures += check(gl.pipelineKey() == b,
			"depth writes must stay off while depth test is off, as GL does");

		// ...and the states that DO differ still must not collide.
		gl.glEnable(0x0B71);
		failures += check(gl.pipelineKey() != b, "enabling depth test must change the pipeline");
		gl.glEnable(0x0BE2);
		long c = gl.pipelineKey();
		gl.glBlendFunc(0x0302, 0x0303);
		failures += check(gl.pipelineKey() != c, "blend factors must matter while blending is on");
		return failures;
	}

	private static void setEnabled(GlShim gl, int cap, boolean on) {
		if (on) {
			gl.glEnable(cap);
		} else {
			gl.glDisable(cap);
		}
	}

	/**
	 * A quad covering the left half of the target, in beta's own vertex format, drawn through the
	 * shim's state -- then read back and checked pixel by pixel.
	 */
	private static int drawQuad(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		// Beta's GUI setup, near enough: an orthographic projection in pixels, depth test on,
		// no blending, no culling, no texture.
		gl.glMatrixMode(0x1701); // GL_PROJECTION
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0); // y down, as beta's GUI uses
		gl.glMatrixMode(0x1700); // GL_MODELVIEW
		gl.glLoadIdentity();
		gl.glEnable(0x0B71); // GL_DEPTH_TEST
		gl.glDepthFunc(0x0203); // GL_LEQUAL
		gl.glDisable(0x0DE1); // GL_TEXTURE_2D -- colour only, so the expected value is exact

		GeometryCapture capture = new GeometryCapture();
		capture.begin(GL_QUADS, gl.pipelineKey());
		capture.color(255, 64, 32, 255);
		// Left half, wound counter-clockwise in a y-down space (which is clockwise on screen);
		// culling is off, so winding does not matter here and is checked separately.
		capture.vertex(0.0F, 0.0F, 0.0F);
		capture.vertex(0.0F, SIZE, 0.0F);
		capture.vertex(SIZE / 2.0F, SIZE, 0.0F);
		capture.vertex(SIZE / 2.0F, 0.0F, 0.0F);
		capture.end();

		try (GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "test-vertices");
				GpuBuffer indices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "test-indices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "test-uniforms");
				GpuTexture white = GpuTexture.white(ctx)) {

			vertices.write(capture.data(), capture.vertexCount() * GeometryCapture.STRIDE_BYTES);

			ByteBuffer quadIndices = QuadIndices.forQuads(1);
			indices.write(quadIndices, quadIndices.remaining());

			// One 256-byte slot, which is the dynamic-offset alignment even though the block is 240.
			ByteBuffer block = gl.uniforms(true);
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			uniforms.write(block, GlState.UNIFORM_BYTES);

			MemorySegment sampler = GpuTexture.nearestSampler(ctx, false, 1);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, white.view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(gl.pipelineKey()));
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0,
					(long) capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
				wgpuRenderPassEncoderSetIndexBuffer(pass, indices.handle(),
					WGPUIndexFormat_Uint16(), 0, quadIndices.remaining());
				wgpuRenderPassEncoderDrawIndexed(pass, 6, 1, 0, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);

			int inside = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE / 2);
			int outside = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 2);
			int failures = 0;
			failures += check(inside == 0xFFFF4020,
				"quad interior should be the vertex colour, got " + hex(inside));
			failures += check(outside == 0xFF000000,
				"outside the quad should be the clear colour, got " + hex(outside));
			System.out.println("draw       = quad rendered, interior " + hex(inside)
				+ ", background " + hex(outside));
			return failures;
		}
	}

	/**
	 * {@code glLineWidth(2)} actually covers two pixels, and HiDPI doubles it exactly.
	 *
	 * <p>WebGPU draws every line one pixel wide, so the block outline's width has to become geometry
	 * -- {@link LineExpander} does that after the projection divide, since two pixels means two
	 * pixels whatever the distance. This renders the result and counts the rows it covers, which is
	 * the only claim that matters.
	 *
	 * <p>Run at both scales. The 2x case asserts {@code expected} physical rows for the same
	 * requested width, which is the HiDPI property in full: the line occupies the same fraction of
	 * the screen at either setting, so toggling HiDPI changes how sharp everything else is without
	 * changing how thick the outline looks. Counting rows also catches a soft edge -- a line that
	 * bled across a boundary would light three rows, two of them dim.
	 */
	private static int wideLine(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines, float pixelScale, int expected) {

		GlShim gl = new GlShim();
		// No ortho: LineExpander emits NDC, which is what identity matrices expect.
		gl.glDisable(0x0B71);
		gl.glDisable(0x0DE1);

		ByteBuffer line = ByteBuffer.allocateDirect(2 * LineExpander.STRIDE_BYTES)
			.order(java.nio.ByteOrder.nativeOrder());
		putNdcVertex(line, -0.75F, 0.0F);
		putNdcVertex(line, 0.75F, 0.0F);
		line.position(0);

		float[] identity = new float[16];
		identity[0] = identity[5] = identity[10] = identity[15] = 1.0F;
		LineExpander expander = new LineExpander();
		// The viewport is PHYSICAL pixels and stays the size of the target; the scale says how many
		// logical pixels that is. A 64-pixel drawable at 2x is 32 logical pixels, so a 2-logical-pixel
		// line covers 4 physical rows -- which is the whole point of doubling it on HiDPI.
		int failures = check(expander.expand(line, 2, Primitives.GL_LINES, identity, identity,
			SIZE, SIZE, 2.0F, pixelScale), "the segment must expand");

		try (GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "wide-vertices");
				GpuBuffer indices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "wide-indices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "wide-uniforms");
				GpuTexture white = GpuTexture.white(ctx)) {

			ByteBuffer quads = expander.data();
			vertices.write(quads, quads.remaining());
			ByteBuffer quadIndices = QuadIndices.forQuads(expander.vertexCount() / 4);
			indices.write(quadIndices, quadIndices.remaining());
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			ByteBuffer block = gl.state().writeUniformsEye(true, false, false);
			uniforms.write(block, GlState.UNIFORM_BYTES);

			MemorySegment sampler = GpuTexture.nearestSampler(ctx, false, 1);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, white.view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(gl.pipelineKey()));
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0, quads.limit());
				wgpuRenderPassEncoderSetIndexBuffer(pass, indices.handle(),
					WGPUIndexFormat_Uint16(), 0, quadIndices.remaining());
				wgpuRenderPassEncoderDrawIndexed(pass, expander.vertexCount() / 4 * 6, 1, 0, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);

			int lit = 0;
			int partial = 0;
			for (int y = 0; y < SIZE; y++) {
				int p = Readback.pixel(pixels, SIZE, SIZE / 2, y);
				if (p == 0xFFFFFFFF) {
					lit++;
				} else if (p != 0xFF000000) {
					partial++;
				}
			}
			failures += check(lit == expected,
				"width 2 at " + pixelScale + "x should cover " + expected + " rows, got " + lit);
			failures += check(partial == 0,
				"a snapped line must have no partially covered row, got " + partial);
			System.out.println("wide line  = " + lit + " rows at " + pixelScale
				+ "x (" + partial + " partial)");
			return failures;
		}
	}

	private static void putNdcVertex(ByteBuffer b, float x, float y) {
		int o = b.position();
		b.putFloat(o, x);
		b.putFloat(o + 4, y);
		b.putFloat(o + 8, 0.0F);
		b.putFloat(o + 12, 0.0F);
		b.putFloat(o + 16, 0.0F);
		b.putInt(o + 20, 0xFFFFFFFF);
		b.putInt(o + 24, 0);
		b.putInt(o + 28, 0);
		b.position(o + LineExpander.STRIDE_BYTES);
	}

	/**
	 * A {@code GL_LINE_STRIP} batch draws connected segments, not a filled triangle.
	 *
	 * <p>This is the block-selection outline. {@code WorldRenderer.renderOutline} draws the box as
	 * two five-vertex {@code GL_LINE_STRIP} loops (top and bottom faces) plus one {@code GL_LINES}
	 * batch for the four vertical edges. The strip topology was missing from the pipeline's switch
	 * and fell through to a triangle list, so both loops became a single stray triangle and only the
	 * verticals survived.
	 *
	 * <p>Three vertices in an L, chosen so the two interpretations disagree on a specific pixel: as
	 * a strip they are two thin segments, as a triangle they fill the whole corner. The probe sits
	 * inside that triangle and off both segments, so it is background if and only if the topology is
	 * right -- and a point on the first segment is asserted lit, so "drew nothing at all" cannot pass
	 * either.
	 */
	private static int lineStrip(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0B71);
		gl.glDisable(0x0DE1);

		// The shim carries the topology into the pipeline key; without this the batch would be
		// recorded as a strip but drawn by a triangle pipeline, which is the bug under test.
		gl.setTopology(com.periut.retrodragon.shim.PipelineKey.TOPOLOGY_LINE_STRIP);

		final int near = SIZE / 8;          // 32
		final int far = SIZE * 3 / 4;       // 192
		GeometryCapture capture = new GeometryCapture();
		capture.begin(Primitives.GL_LINE_STRIP, gl.pipelineKey());
		capture.color(255, 255, 255, 255);
		capture.vertex(near + 0.5F, near + 0.5F, 0.0F);
		capture.vertex(far + 0.5F, near + 0.5F, 0.0F);
		capture.vertex(far + 0.5F, far + 0.5F, 0.0F);
		capture.end();

		try (GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "line-vertices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "line-uniforms");
				GpuTexture white = GpuTexture.white(ctx)) {

			vertices.write(capture.data(), capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			uniforms.write(gl.uniforms(true), GlState.UNIFORM_BYTES);

			MemorySegment sampler = GpuTexture.nearestSampler(ctx, false, 1);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, white.view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(gl.pipelineKey()));
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0,
					(long) capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
				wgpuRenderPassEncoderDraw(pass, capture.vertexCount(), 1, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);

			int onSegment = Readback.pixel(pixels, SIZE, (near + far) / 2, near);
			// Inside triangle (near,near)-(far,near)-(far,far), clear of both segments.
			int interior = Readback.pixel(pixels, SIZE, far - 24, near + 12);

			int failures = 0;
			failures += check(onSegment == 0xFFFFFFFF,
				"the strip's first segment should be drawn, got " + hex(onSegment));
			failures += check(interior == 0xFF000000,
				"a line strip must not fill its interior, got " + hex(interior)
					+ " (that is the triangle-list fallback)");
			System.out.println("line strip = segment " + hex(onSegment) + ", interior "
				+ hex(interior) + " (not filled)");
			return failures;
		}
	}

	/**
	 * {@code glPolygonOffset} reaches the pipeline as depth bias.
	 *
	 * <p>Beta lifts the block-breaking overlay off the face it is drawn on with
	 * {@code glPolygonOffset(-3, -3)}; without it the two are exactly coplanar and depth rounding
	 * decides per pixel which one shows, which is the tearing in the crack texture.
	 *
	 * <p>Made unambiguous by using a STRICT depth compare. Two quads at identical depth: the second
	 * uses {@code GL_LESS}, so with no offset it cannot pass anywhere, and it passes everywhere only
	 * if a negative bias genuinely moved it. Both directions are asserted, so neither "bias ignored"
	 * nor "bias always applied" can pass.
	 */
	private static int polygonOffset(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		int failures = 0;
		int without = coplanarOverlay(ctx, arena, target, depth, pipelines, false);
		int with = coplanarOverlay(ctx, arena, target, depth, pipelines, true);

		failures += check(without == 0xFFFF0000,
			"with no polygon offset a GL_LESS overlay must be rejected, got " + hex(without));
		failures += check(with == 0xFF00FF00,
			"glPolygonOffset(-3,-3) must let the coplanar overlay win, got " + hex(with)
				+ " (depth bias is not reaching the pipeline)");
		System.out.println("poly offset= without " + hex(without) + ", with " + hex(with));
		return failures;
	}

	/** Draws a red base quad then a coplanar green one under GL_LESS; returns the centre pixel. */
	private static int coplanarOverlay(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines, boolean offset) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0B71);   // GL_DEPTH_TEST
		gl.glDepthFunc(0x0203); // GL_LEQUAL for the base
		gl.glDisable(0x0DE1);

		GeometryCapture base = new GeometryCapture();
		base.begin(GL_QUADS, gl.pipelineKey());
		base.color(255, 0, 0, 255);
		quad(base);
		base.end();
		long baseKey = gl.pipelineKey();

		gl.glDepthFunc(0x0201); // GL_LESS -- coplanar geometry cannot pass without a bias
		if (offset) {
			gl.glPolygonOffset(-3.0F, -3.0F);
			gl.glEnable(0x8037); // GL_POLYGON_OFFSET_FILL
		}
		GeometryCapture over = new GeometryCapture();
		over.begin(GL_QUADS, gl.pipelineKey());
		over.color(0, 255, 0, 255);
		quad(over);
		over.end();
		long overKey = gl.pipelineKey();

		try (GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "off-vertices");
				GpuBuffer indices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "off-indices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "off-uniforms");
				GpuTexture white = GpuTexture.white(ctx)) {

			int stride = GeometryCapture.STRIDE_BYTES;
			vertices.ensure((long) (base.vertexCount() + over.vertexCount()) * stride);
			vertices.writeAt(0, base.data(), base.vertexCount() * stride);
			vertices.writeAt((long) base.vertexCount() * stride, over.data(),
				over.vertexCount() * stride);

			ByteBuffer quadIndices = QuadIndices.forQuads(1);
			indices.write(quadIndices, quadIndices.remaining());
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			uniforms.write(gl.uniforms(true), GlState.UNIFORM_BYTES);

			MemorySegment sampler = GpuTexture.nearestSampler(ctx, false, 1);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, white.view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetIndexBuffer(pass, indices.handle(),
					WGPUIndexFormat_Uint16(), 0, quadIndices.remaining());

				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(baseKey));
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0,
					(long) base.vertexCount() * stride);
				wgpuRenderPassEncoderDrawIndexed(pass, 6, 1, 0, 0, 0);

				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(overKey));
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(),
					(long) base.vertexCount() * stride, (long) over.vertexCount() * stride);
				wgpuRenderPassEncoderDrawIndexed(pass, 6, 1, 0, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);
			return Readback.pixel(pixels, SIZE, SIZE / 2, SIZE / 2);
		}
	}

	/** A full-target quad at a fixed depth, so two of them are exactly coplanar. */
	private static void quad(GeometryCapture capture) {
		final float z = 0.5F;
		capture.vertex(0.0F, 0.0F, z);
		capture.vertex(0.0F, SIZE, z);
		capture.vertex(SIZE, SIZE, z);
		capture.vertex(SIZE, 0.0F, z);
	}

	/**
	 * {@code glTexParameteri(GL_TEXTURE_WRAP_S, GL_CLAMP)} actually reaches the sampler.
	 *
	 * <p>This is the entity shadow, reduced. beta draws the shadow as a quad per ground block whose
	 * UVs run outside 0..1 as they fall away from the entity, and relies on clamp-to-edge to fade
	 * the blob out; with repeat addressing those UVs wrap and the shadow tiles across the ground.
	 *
	 * <p>Built so the two addressing modes cannot be confused. The quad samples 1.25..1.75, exactly
	 * one wrap beyond the texture:
	 *
	 * <ul>
	 *   <li>REPEAT folds that back to 0.25..0.75 -- one distinct texel per quadrant, four colours.</li>
	 *   <li>CLAMP pins every coordinate to the far edge -- the bottom-right texel, four times.</li>
	 * </ul>
	 *
	 * <p>So a regression cannot leave the frame merely "a bit off": it changes every asserted pixel.
	 * Verified by breaking it -- asking for {@code GL_REPEAT} instead turns all four back into the
	 * four distinct colours and the test fails.
	 */
	private static int clampAddressing(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0DE1);
		gl.glDisable(0x0B71);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(java.nio.ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 0, 255, 255);
		putRgba(texels, 255, 255, 255, 255);
		texels.flip();

		try (TextureStore store = new TextureStore(ctx);
				GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "clamp-vertices");
				GpuBuffer indices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "clamp-indices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "clamp-uniforms")) {

			int name = store.gen();
			store.define(name, 2, 2, texels);
			gl.glBindTexture(0x0DE1, name);
			// Exactly what beta emits from TextureManager.load for a %clamp% texture.
			store.parameter(name, 0x2802, 0x2900); // GL_TEXTURE_WRAP_S, GL_CLAMP
			store.parameter(name, 0x2803, 0x2900); // GL_TEXTURE_WRAP_T, GL_CLAMP

			int failures = check(store.isClamped(name),
				"glTexParameteri(GL_TEXTURE_WRAP_S, GL_CLAMP) must mark the texture clamped");

			GeometryCapture capture = new GeometryCapture();
			capture.begin(GL_QUADS, gl.pipelineKey());
			capture.color(255, 255, 255, 255);
			capture.texCoord(1.25F, 1.25F);
			capture.vertex(0.0F, 0.0F, 0.0F);
			capture.texCoord(1.25F, 1.75F);
			capture.vertex(0.0F, SIZE, 0.0F);
			capture.texCoord(1.75F, 1.75F);
			capture.vertex(SIZE, SIZE, 0.0F);
			capture.texCoord(1.75F, 1.25F);
			capture.vertex(SIZE, 0.0F, 0.0F);
			capture.end();

			vertices.write(capture.data(), capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
			ByteBuffer quadIndices = QuadIndices.forQuads(1);
			indices.write(quadIndices, quadIndices.remaining());
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			uniforms.write(gl.uniforms(true), GlState.UNIFORM_BYTES);

			// Through the store's own answers, so this exercises the path the renderer uses rather
			// than a hardcoded "clamp" that would pass even with parameter() broken.
			MemorySegment sampler = GpuTexture.sampler(ctx, store.isLinear(name),
				store.isClamped(name), false);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, store.get(name).view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(gl.pipelineKey()));
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0,
					(long) capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
				wgpuRenderPassEncoderSetIndexBuffer(pass, indices.handle(),
					WGPUIndexFormat_Uint16(), 0, quadIndices.remaining());
				wgpuRenderPassEncoderDrawIndexed(pass, 6, 1, 0, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);

			int topLeft = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE / 4);
			int topRight = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 4);
			int bottomLeft = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE * 3 / 4);
			int bottomRight = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE * 3 / 4);

			// Every quadrant is the far-edge texel. Under REPEAT these are red/green/blue/white.
			failures += check(topLeft == 0xFFFFFFFF,
				"clamped UVs must pin to the edge texel, got " + hex(topLeft) + " (repeat gives red)");
			failures += check(topRight == 0xFFFFFFFF,
				"clamped UVs must pin to the edge texel, got " + hex(topRight) + " (repeat gives green)");
			failures += check(bottomLeft == 0xFFFFFFFF,
				"clamped UVs must pin to the edge texel, got " + hex(bottomLeft) + " (repeat gives blue)");
			failures += check(bottomRight == 0xFFFFFFFF,
				"clamped UVs must pin to the edge texel, got " + hex(bottomRight));

			// And the reverse direction, which the old path-based hints could not express at all:
			// a texture re-uploaded without %clamp% has to stop clamping.
			store.parameter(name, 0x2802, 0x2901); // GL_REPEAT
			store.parameter(name, 0x2803, 0x2901);
			failures += check(!store.isClamped(name),
				"GL_REPEAT must clear the clamp flag, not leave the texture clamped for the run");

			System.out.println("clamp wrap = " + hex(topLeft) + " " + hex(topRight)
				+ " / " + hex(bottomLeft) + " " + hex(bottomRight) + " (edge texel, not tiled)");
			return failures;
		}
	}

	/**
	 * A full-target quad sampling a 2x2 texture, one texel per quadrant.
	 *
	 * <p>This is what "textures work" has to mean concretely: the right texel in the right corner,
	 * not merely a non-black frame. It catches the two mistakes that otherwise survive every other
	 * check -- a V axis flipped between GL and WebGPU conventions, and RGBA/BGRA byte order.
	 */
	private static int drawTextured(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0DE1); // GL_TEXTURE_2D
		gl.glDisable(0x0B71);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		// Distinct, saturated, and NOT symmetric under any flip: red top-left, green top-right,
		// blue bottom-left, white bottom-right.
		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(java.nio.ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 0, 255, 255);
		putRgba(texels, 255, 255, 255, 255);
		texels.flip();

		try (TextureStore store = new TextureStore(ctx);
				GpuBuffer vertices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "tex-vertices");
				GpuBuffer indices = new GpuBuffer(ctx, Flags.BUFFER_USAGE_INDEX, "tex-indices");
				GpuBuffer uniforms = new GpuBuffer(ctx, Flags.BUFFER_USAGE_UNIFORM, "tex-uniforms")) {

			int name = store.gen();
			store.define(name, 2, 2, texels);
			gl.glBindTexture(0x0DE1, name);

			GeometryCapture capture = new GeometryCapture();
			capture.begin(GL_QUADS, gl.pipelineKey());
			capture.color(255, 255, 255, 255);
			// UVs pushed a quarter texel in from each corner so every vertex lands squarely inside
			// its texel; sampling exactly on the boundary is a coin toss between neighbours.
			capture.texCoord(0.25F, 0.25F);
			capture.vertex(0.0F, 0.0F, 0.0F);
			capture.texCoord(0.25F, 0.75F);
			capture.vertex(0.0F, SIZE, 0.0F);
			capture.texCoord(0.75F, 0.75F);
			capture.vertex(SIZE, SIZE, 0.0F);
			capture.texCoord(0.75F, 0.25F);
			capture.vertex(SIZE, 0.0F, 0.0F);
			capture.end();

			vertices.write(capture.data(), capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
			ByteBuffer quadIndices = QuadIndices.forQuads(1);
			indices.write(quadIndices, quadIndices.remaining());
			uniforms.ensure(Bindings.UNIFORM_ALIGNMENT);
			uniforms.write(gl.uniforms(true), GlState.UNIFORM_BYTES);

			MemorySegment sampler = GpuTexture.nearestSampler(ctx, false, 1);
			MemorySegment bindGroup = Bindings.fixedFunctionGroup(ctx, arena,
				pipelines.bindGroupLayout(), uniforms.handle(), GlState.UNIFORM_BYTES,
				sampler, store.get(name).view());

			try (Frame frame = Frame.begin(ctx); Arena passArena = Arena.ofConfined()) {
				MemorySegment pass = frame.beginPass(passArena, target.view(), true,
					0.0F, 0.0F, 0.0F, 1.0F, depth.view(), true);
				wgpuRenderPassEncoderSetPipeline(pass, pipelines.get(gl.pipelineKey()));
				MemorySegment offsets = passArena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, 1);
				offsets.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 0);
				wgpuRenderPassEncoderSetBindGroup(pass, 0, bindGroup, 1, offsets);
				wgpuRenderPassEncoderSetVertexBuffer(pass, 0, vertices.handle(), 0,
					(long) capture.vertexCount() * GeometryCapture.STRIDE_BYTES);
				wgpuRenderPassEncoderSetIndexBuffer(pass, indices.handle(),
					WGPUIndexFormat_Uint16(), 0, quadIndices.remaining());
				wgpuRenderPassEncoderDrawIndexed(pass, 6, 1, 0, 0, 0);
				frame.submit();
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			wgpuBindGroupRelease(bindGroup);
			wgpuSamplerRelease(sampler);

			int topLeft = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE / 4);
			int topRight = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 4);
			int bottomLeft = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE * 3 / 4);
			int bottomRight = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE * 3 / 4);

			int failures = 0;
			failures += check(topLeft == 0xFFFF0000, "top-left texel should be red, got " + hex(topLeft));
			failures += check(topRight == 0xFF00FF00, "top-right texel should be green, got " + hex(topRight));
			failures += check(bottomLeft == 0xFF0000FF, "bottom-left texel should be blue, got " + hex(bottomLeft));
			failures += check(bottomRight == 0xFFFFFFFF, "bottom-right texel should be white, got " + hex(bottomRight));
			System.out.println("textured   = " + hex(topLeft) + " " + hex(topRight)
				+ " / " + hex(bottomLeft) + " " + hex(bottomRight));
			return failures;
		}
	}

	/**
	 * A whole frame the way the game produces one: a clear, several batches under different state,
	 * and a mid-frame depth clear that splits the frame into two render passes.
	 *
	 * <p>This is the piece that only exists when everything else is already right -- {@link DrawList}
	 * recording, the segment/pass split, dynamic uniform offsets, the shared quad index buffer, and
	 * the pipeline cache all have to agree for a single pixel to land correctly.
	 */
	private static int replayFrame(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0DE1);
		gl.glEnable(0x0B71);
		gl.glDepthFunc(0x0203);

		DrawList list = new DrawList();
		list.clear(true, true, 0.0F, 0.0F, 0.25F, 1.0F);

		// Under this projection LARGER z is NEARER: glOrtho negates eye z, so a GUI element at a
		// higher z draws in front. That is how beta layers its GUI (it uses the same trick with
		// near=1000/far=3000 and a -2000 translate), and it is the opposite of what the depth VALUE
		// does, so it is worth being explicit about.
		list.add(quad(gl, 0, 0, SIZE / 2, SIZE, 0.5F, 255, 0, 0), 4, GL_QUADS,
			gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));

		// A green quad over the whole target but BEHIND the red one -- depth-tested away where they
		// overlap, which is the check that per-batch depth state actually reached the GPU.
		list.add(quad(gl, 0, 0, SIZE, SIZE, 0.0F, 0, 255, 0), 4, GL_QUADS,
			gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));

		// A depth clear splits the frame. This batch is the farthest of the three and must still
		// win, because the depth buffer it tests against was just reset.
		list.clear(false, true, 0.0F, 0.0F, 0.0F, 1.0F);
		list.add(quad(gl, SIZE * 3 / 4, 0, SIZE, SIZE, -0.9F, 0, 0, 255), 4, GL_QUADS,
			gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));

		try (TextureStore textures = new TextureStore(ctx);
				ImmediateRenderer immediate = new ImmediateRenderer(ctx)) {
			int draws;
			try (Frame frame = Frame.begin(ctx)) {
				draws = immediate.render(frame, target.view(), depth.view(), list, pipelines, textures);
			}

			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			int left = Readback.pixel(pixels, SIZE, SIZE / 8, SIZE / 2);
			int middle = Readback.pixel(pixels, SIZE, SIZE * 5 / 8, SIZE / 2);
			int right = Readback.pixel(pixels, SIZE, SIZE * 7 / 8, SIZE / 2);

			int failures = 0;
			failures += check(list.segmentCount() == 2,
				"a mid-frame depth clear should split the frame into two passes, got "
					+ list.segmentCount());
			// Three batches were submitted but only two draws issued: the first two share a
			// pipeline, a texture and a byte-identical uniform block, so they merge. The third
			// cannot, because a pass boundary sits between them. Checking the pixels below is what
			// makes this a merge rather than a dropped draw.
			failures += check(list.mergedCount() == 1,
				"same-state batches should merge, got " + list.mergedCount() + " merges");
			failures += check(draws == 2,
				"three batches minus one merge should be two draws, got " + draws);
			failures += check(left == 0xFFFF0000,
				"the nearer red quad should survive the farther green one, got " + hex(left));
			failures += check(middle == 0xFF00FF00,
				"green should cover where red is absent, got " + hex(middle));
			failures += check(right == 0xFF0000FF,
				"blue drawn after a depth clear should win despite being farthest, got " + hex(right));
			System.out.println("frame      = " + draws + " draws (" + list.mergedCount()
				+ " merged) in " + list.segmentCount() + " passes, "
				+ hex(left) + " " + hex(middle) + " " + hex(right));
			return failures;
		}
	}

	/**
	 * Back-face culling must cull the faces GL would, and no others.
	 *
	 * <p>This is the check that was missing when the world rendered inside-out -- you could see
	 * through the terrain into the caves below, because the outward faces were being culled and the
	 * inward ones kept. Facing is decided from a triangle's signed area in framebuffer coordinates,
	 * and GL measures y upward from the bottom while WebGPU measures it downward from the top; that
	 * reflection reverses orientation, so GL's CCW front face is WebGPU's CW.
	 *
	 * <p>It never showed up before because every earlier test drew with culling disabled -- as the
	 * whole GUI does. Only the world enables it.
	 */
	private static int winding(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0DE1);
		gl.glDisable(0x0B71);
		gl.glEnable(0x0B44);      // GL_CULL_FACE
		gl.glCullFace(0x0405);    // GL_BACK

		DrawList list = new DrawList();
		list.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);

		// Two quads, identical except for vertex ORDER. The left is wound the way beta's Tessellator
		// winds a front face; the right is the same quad reversed. Under back-face culling exactly
		// one may survive, and which one is the answer -- asserting on both sides is what makes this
		// a measurement rather than a restatement of an assumption.
		list.add(quad(gl, 0, 0, SIZE / 2, SIZE, 0.0F, 0, 255, 0), 4, GL_QUADS,
			gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));
		list.add(reversedQuad(gl, SIZE / 2, 0, SIZE, SIZE, 0.0F, 255, 0, 0), 4, GL_QUADS,
			gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));

		try (TextureStore textures = new TextureStore(ctx);
				ImmediateRenderer immediate = new ImmediateRenderer(ctx)) {
			try (Frame frame = Frame.begin(ctx)) {
				immediate.render(frame, target.view(), depth.view(), list, pipelines, textures);
			}
			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			int betaWound = Readback.pixel(pixels, SIZE, SIZE / 4, SIZE / 2);
			int reversed = Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 2);

			int failures = 0;
			failures += check(betaWound == 0xFF00FF00,
				"beta's winding must survive back-face culling, got " + hex(betaWound)
					+ " -- the clear colour here means front faces are being culled, which renders the"
					+ " world inside out: you see through the terrain into the caves below");
			failures += check(reversed == 0xFF000000,
				"the reversed winding must be culled, got " + hex(reversed)
					+ " -- if both survive, culling is not actually enabled and this test proves"
					+ " nothing");
			System.out.println("winding    = beta " + hex(betaWound) + ", reversed " + hex(reversed));
			return failures;
		}
	}

	/**
	 * Growing the terrain arena must not disturb what is already in it.
	 *
	 * <p>Sections hold vertex offsets into the arena and re-upload only when their blocks change, so
	 * a growth that dropped the old contents leaves every offset valid and every byte behind it
	 * wrong. That renders as a world with no surface and cave interiors hanging in the air -- and
	 * because nothing errors, the only symptom is the picture.
	 */
	private static int arenaGrowth(WebGPUContext ctx) {
		int bytes = 4096;
		try (GpuBuffer buffer = GpuBuffer.sized(ctx,
				Flags.BUFFER_USAGE_VERTEX | Flags.BUFFER_USAGE_COPY_SRC, "growth-test", bytes)) {
			ByteBuffer pattern = ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
			for (int i = 0; i < bytes; i++) {
				pattern.put(i, (byte) (i * 31 + 7));
			}
			buffer.writeAt(0, pattern, bytes);

			boolean grew = buffer.growPreserving(bytes * 4L);
			byte[] after = Readback.buffer(ctx, buffer.handle(), bytes);

			int mismatches = 0;
			for (int i = 0; i < bytes; i++) {
				if (after[i] != (byte) (i * 31 + 7)) {
					mismatches++;
				}
			}
			int failures = 0;
			failures += check(grew, "the buffer should have grown");
			failures += check(buffer.capacity() >= bytes * 4L,
				"capacity should cover the request, got " + buffer.capacity());
			failures += check(mismatches == 0,
				mismatches + " of " + bytes + " bytes were lost when the arena grew");
			System.out.println("arena      = grew to " + buffer.capacity()
				+ " bytes with " + (bytes - mismatches) + "/" + bytes + " preserved");
			return failures;
		}
	}

	/**
	 * A batch that wrote no texture coordinates must sample at the origin, not at stale UVs.
	 *
	 * <p>Entity shadows are the case that made this visible. They enable texturing, bind the shadow
	 * blob, and write no UVs -- GL then uses the current texcoord for every vertex, which beta leaves
	 * at (0,0). Reading the leftover UVs instead makes them sample the block atlas at arbitrary
	 * coordinates, which paints a repeat of the terrain texture on the ground under every entity.
	 *
	 * <p>The check puts a deliberately wrong UV in the vertices and a two-colour texture in the
	 * sampler, so sampling at the origin and sampling at the stale coordinate give different colours.
	 */
	private static int missingTexCoords(WebGPUContext ctx, Arena arena, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0DE1);
		gl.glDisable(0x0B71);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		// Red at the origin, green everywhere else.
		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(java.nio.ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		texels.flip();

		try (TextureStore store = new TextureStore(ctx);
				ImmediateRenderer immediate = new ImmediateRenderer(ctx)) {
			int name = store.gen();
			store.define(name, 2, 2, texels);
			gl.glBindTexture(0x0DE1, name);

			// Vertices carrying UVs that point at the GREEN half. With the flag clear they must be
			// ignored in favour of the origin, which is RED.
			GeometryCapture capture = new GeometryCapture();
			capture.begin(GL_QUADS, gl.pipelineKey());
			capture.color(255, 255, 255, 255);
			capture.texCoord(0.75F, 0.75F);
			capture.vertex(0.0F, 0.0F, 0.0F);
			capture.vertex(0.0F, SIZE, 0.0F);
			capture.vertex(SIZE, SIZE, 0.0F);
			capture.vertex(SIZE, 0.0F, 0.0F);
			capture.end();

			DrawList list = new DrawList();
			list.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);
			list.add(capture.data(), 4, GL_QUADS, gl.pipelineKey(), name,
				gl.state().writeUniforms(true, false, false));

			try (Frame frame = Frame.begin(ctx)) {
				immediate.render(frame, target.view(), depth.view(), list, pipelines, store);
			}
			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			int middle = Readback.pixel(pixels, SIZE, SIZE / 2, SIZE / 2);

			int failures = check(middle == 0xFFFF0000,
				"a batch with no texture coordinates must sample at the origin, got " + hex(middle)
					+ " -- green here means the stale UVs in the vertex data were used, which is what"
					+ " made entity shadows sample the terrain atlas");
			System.out.println("no texcoord= sampled at the origin (" + hex(middle) + ")");
			return failures;
		}
	}

	/**
	 * A display list must replay with the attributes it was RECORDED with, not with assumed ones.
	 *
	 * <p>This is the sky. Beta compiles its sky dome and stars into display lists from a Tessellator
	 * that never wrote a per-vertex colour -- the colour comes from a {@code glColor3f} outside the
	 * list -- so the colour slot in those vertices holds whatever the previous batch left there.
	 * Replaying as though every vertex carried a colour makes the shader multiply the sky by that
	 * leftover.
	 *
	 * <p>The check works by putting a colour in the slot that must NOT appear: the recorded vertices
	 * are magenta, the current colour is green, and the list declares it has no vertex colours. Green
	 * means the flags survived; magenta means they were assumed.
	 */
	private static int displayListAttributes(WebGPUContext ctx, RenderTarget target,
			DepthBuffer depth, FixedFunctionPipelines pipelines) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0DE1);
		gl.glDisable(0x0B71);
		gl.glDisable(0x0B44);

		int list = DisplayLists.gen(1);
		DisplayLists.begin(list);
		DisplayLists.record(quad(gl, 0, 0, SIZE, SIZE, 0.0F, 255, 0, 255), 4, GL_QUADS,
			false, false, true);
		DisplayLists.end();

		// The state at CALL time, which is what GL applies -- not the state at record time.
		gl.glColor4f(0.0F, 1.0F, 0.0F, 1.0F);

		DrawList list2 = new DrawList();
		list2.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);
		DisplayLists.call(list, (vertices, count, mode, hasColor, hasNormals, hasTexture) ->
			list2.add(vertices, count, mode, gl.pipelineKey(), 0,
				gl.state().writeUniforms(hasColor, hasNormals, hasTexture)));

		try (TextureStore textures = new TextureStore(ctx);
				ImmediateRenderer immediate = new ImmediateRenderer(ctx)) {
			try (Frame frame = Frame.begin(ctx)) {
				immediate.render(frame, target.view(), depth.view(), list2, pipelines, textures);
			}
			byte[] pixels = Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			int middle = Readback.pixel(pixels, SIZE, SIZE / 2, SIZE / 2);

			int failures = check(middle == 0xFF00FF00,
				"a list recorded without vertex colours must replay using the CURRENT colour, got "
					+ hex(middle) + " -- magenta here means the recorded vertices' stale colour slot"
					+ " was read as data, which is what miscoloured the sky");
			System.out.println("displaylist= replayed with recorded attributes (" + hex(middle) + ")");
			return failures;
		} finally {
			DisplayLists.clear();
		}
	}

	/** One quad in beta's packed vertex layout, ready to hand to {@link DrawList#add}. */
	private static ByteBuffer quad(GlShim gl, float x0, float y0, float x1, float y1, float z,
			int r, int g, int b) {
		GeometryCapture capture = new GeometryCapture();
		capture.begin(GL_QUADS, gl.pipelineKey());
		capture.color(r, g, b, 255);
		capture.vertex(x0, y0, z);
		capture.vertex(x0, y1, z);
		capture.vertex(x1, y1, z);
		capture.vertex(x1, y0, z);
		capture.end();
		return capture.data();
	}

	/** The same quad with its vertex order reversed, so it faces the other way. */
	private static ByteBuffer reversedQuad(GlShim gl, float x0, float y0, float x1, float y1, float z,
			int r, int g, int b) {
		GeometryCapture capture = new GeometryCapture();
		capture.begin(GL_QUADS, gl.pipelineKey());
		capture.color(r, g, b, 255);
		capture.vertex(x1, y0, z);
		capture.vertex(x1, y1, z);
		capture.vertex(x0, y1, z);
		capture.vertex(x0, y0, z);
		capture.end();
		return capture.data();
	}

	/**
	 * The three terrain layouts must produce the SAME IMAGE.
	 *
	 * <p>Two independent changes meet here, and neither is observable from a validation-error count:
	 *
	 * <ul>
	 * <li>Quads stored as four vertices and expanded by the shared index buffer instead of six
	 *     vertices pre-expanded on the CPU ({@link QuadVertices}). Get the base vertex or the
	 *     submitted primitive mode wrong and every quad renders as one sheared triangle -- which is
	 *     exactly what this project shipped once before.
	 * <li>The 20-byte packing ({@link TerrainVertex}). A wrong offset, a wrong vertex format or the
	 *     unorm16 halves swapped all still draw geometry, just with the texture wrong. The texture
	 *     here is four distinct saturated texels with no symmetry, so a swapped u/v, a flipped axis
	 *     and a quantisation error each land on a different colour.
	 * </ul>
	 *
	 * <p>Compared pixel for pixel against beta's own layout rather than against expected colours.
	 * That is the actual claim being made -- these are storage changes, and a storage change that
	 * alters one pixel is a bug regardless of whether the pixel it produces looks reasonable.
	 *
	 * <p>Drawn through {@link ImmediateRenderer} and {@link DrawList}, not by hand, so the index
	 * binding, the base vertex and the arena's adjacency merge are all on the path being checked.
	 * Two adjacent sections are uploaded so the merge actually happens.
	 */
	private static int terrainLayouts(WebGPUContext ctx, RenderTarget target, DepthBuffer depth) {
		int failures = 0;
		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(java.nio.ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 0, 255, 255);
		putRgba(texels, 255, 255, 255, 255);
		texels.flip();

		byte[] reference = null;
		String referenceName = null;
		int mergedDraws = -1;
		for (boolean compact : new boolean[] { false, false, true }) {
			// false twice: first as beta stores quads, then as four-vertex indexed quads. The pair
			// is what isolates the index path from the packing.
			boolean indexed = reference != null;
			String name = (indexed ? "quads" : "tris") + "/" + TerrainVertex.stride(compact) + "B";
			try (FixedFunctionPipelines pipelines =
					FixedFunctionPipelines.create(ctx, target.format(), depth.format(), compact)) {
				Rendered rendered = renderTerrain(ctx, target, depth, pipelines, texels, indexed,
					compact);
				if (reference == null) {
					reference = rendered.pixels();
					referenceName = name;
				} else {
					failures += check(java.util.Arrays.equals(reference, rendered.pixels()),
						name + " must render identically to " + referenceName
							+ ", and does not");
					mergedDraws = rendered.draws();
				}
			}
		}
		// The merge is part of the claim: if the two sections did not collapse, the layouts were
		// compared on a path the game does not take.
		failures += check(mergedDraws == 1,
			"two adjacent arena sections should merge into one draw, got " + mergedDraws);
		if (failures == 0) {
			System.out.println("terrain fmt= tris/32B, quads/32B and quads/20B render identically,"
				+ " merged to " + mergedDraws + " draw");
		}
		return failures;
	}

	private record Rendered(byte[] pixels, int draws) {
	}

	/** Two adjacent sections of textured quads, in whichever layout is asked for. */
	private static Rendered renderTerrain(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, ByteBuffer texels, boolean indexed, boolean compact) {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glEnable(0x0DE1);
		gl.glDisable(0x0B71);
		gl.setProgram(PipelineKey.PROGRAM_TERRAIN);

		int stride = TerrainVertex.stride(compact);
		int perQuad = indexed ? 4 : 6;
		int quadsPerSection = 2;
		int verticesPerSection = quadsPerSection * perQuad;

		try (TextureStore textures = new TextureStore(ctx);
				ImmediateRenderer immediate = new ImmediateRenderer(ctx);
				GpuBuffer arena = GpuBuffer.sized(ctx, Flags.BUFFER_USAGE_VERTEX, "layout-arena",
					(long) 2 * verticesPerSection * stride)) {

			int name = textures.gen();
			textures.define(name, 2, 2, texels);
			gl.glBindTexture(0x0DE1, name);

			ByteBuffer staging = ByteBuffer.allocateDirect(verticesPerSection * stride)
				.order(java.nio.ByteOrder.nativeOrder());
			for (int section = 0; section < 2; section++) {
				staging.clear();
				for (int quad = 0; quad < quadsPerSection; quad++) {
					int index = section * quadsPerSection + quad;
					float x0 = index * (SIZE / 4.0F);
					float x1 = x0 + SIZE / 4.0F;
					// A quarter texel in from each corner, so every vertex lands squarely inside its
					// texel instead of on a boundary the sampler may resolve either way.
					layoutQuad(staging, compact, indexed, x0, x1, 0.25F, 0.75F);
				}
				staging.flip();
				arena.writeAt((long) section * verticesPerSection * stride, staging,
					verticesPerSection * stride);
			}

			DrawList list = new DrawList();
			list.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);
			int glMode = indexed ? GL_QUADS : Primitives.GL_TRIANGLES;
			gl.setTopology(Primitives.topology(glMode));
			ByteBuffer uniforms = gl.state().writeUniforms(true, false);
			for (int section = 0; section < 2; section++) {
				list.addExternal(arena, section * verticesPerSection, verticesPerSection, glMode,
					gl.pipelineKey(), name, uniforms);
			}

			int draws;
			try (Frame frame = Frame.begin(ctx)) {
				draws = immediate.render(frame, target.view(), depth.view(), list, pipelines,
					textures);
			}
			ctx.markFrameSubmitted();
			ctx.awaitFramesInFlight(0);
			return new Rendered(Readback.rgba(ctx, target.handle(), SIZE, SIZE), draws);
		}
	}

	/** One quad spanning {@code x0..x1} and the full height, in the requested layout. */
	private static void layoutQuad(ByteBuffer out, boolean compact, boolean indexed,
			float x0, float x1, float uvLo, float uvHi) {
		if (indexed) {
			layoutVertex(out, compact, x0, 0.0F, uvLo, uvLo);
			layoutVertex(out, compact, x0, SIZE, uvLo, uvHi);
			layoutVertex(out, compact, x1, SIZE, uvHi, uvHi);
			layoutVertex(out, compact, x1, 0.0F, uvHi, uvLo);
		} else {
			// The same four corners in the order the shared index buffer would have referenced
			// them, which is what makes the two comparable at all.
			layoutVertex(out, compact, x0, 0.0F, uvLo, uvLo);
			layoutVertex(out, compact, x0, SIZE, uvLo, uvHi);
			layoutVertex(out, compact, x1, SIZE, uvHi, uvHi);
			layoutVertex(out, compact, x0, 0.0F, uvLo, uvLo);
			layoutVertex(out, compact, x1, SIZE, uvHi, uvHi);
			layoutVertex(out, compact, x1, 0.0F, uvHi, uvLo);
		}
	}

	private static void layoutVertex(ByteBuffer out, boolean compact, float x, float y,
			float u, float v) {
		int base = out.position();
		out.putFloat(base, x);
		out.putFloat(base + 4, y);
		out.putFloat(base + 8, 0.0F);
		if (compact) {
			out.putShort(base + 12, (short) TerrainVertex.packUv(u));
			out.putShort(base + 14, (short) TerrainVertex.packUv(v));
			out.putInt(base + 16, 0xFFFFFFFF);
		} else {
			out.putFloat(base + 12, u);
			out.putFloat(base + 16, v);
			out.putInt(base + 20, 0xFFFFFFFF);
			out.putInt(base + 24, 0);
			out.putInt(base + 28, 0);
		}
		out.position(base + TerrainVertex.stride(compact));
	}

	private static void putRgba(ByteBuffer buffer, int r, int g, int b, int a) {
		buffer.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
	}

	private static int check(boolean condition, String what) {
		if (!condition) {
			System.err.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}

	private static String hex(int argb) {
		return "0x" + String.format("%08X", argb);
	}
}
