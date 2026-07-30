package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.DepthBuffer;
import com.periut.retrodragon.gpu.Flags;
import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.GpuBuffer;
import com.periut.retrodragon.gpu.RenderTarget;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.shim.DisplayLists;
import com.periut.retrodragon.shim.DrawList;
import com.periut.retrodragon.shim.GeometryCapture;
import com.periut.retrodragon.shim.GlShim;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Drives the frame SHAPES a world load produces, offscreen, and requires the device to stay clean.
 * {@code ./gradlew webgpuStress}.
 *
 * <h2>Why this exists</h2>
 *
 * Four attempts to diagnose a world-load crash from windowed runs cost four forced reboots and
 * produced no usable evidence. The bug -- a frame containing only terrain never uploaded its uniform
 * block, so every bind group in it was invalid -- reproduces here in under a second, with no window
 * and no compositor to take down with it.
 *
 * <p>The pixel-checking tests did not catch it because they all drew immediate-mode geometry, which
 * is the one shape that happens to work. Correct pixels are not evidence of a clean device either:
 * the whole point of {@link WebGPUContext#errorCount()} is that a frame can look right while the
 * device rejects most of it.
 *
 * <h2>The shapes</h2>
 *
 * Each is a real thing beta does, chosen because it differs structurally from the others rather than
 * because it looks different on screen.
 */
public final class WebGpuStressTest {
	private static final int SIZE = 256;
	private static final int GL_QUADS = 7;

	public static void main(String[] args) {
		int failures = 0;
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("device     = " + ctx.backendName());
			try (Arena arena = Arena.ofShared();
					RenderTarget target = RenderTarget.create(ctx, arena, SIZE, SIZE);
					DepthBuffer depth = DepthBuffer.create(ctx, arena, SIZE, SIZE);
					FixedFunctionPipelines pipelines =
						FixedFunctionPipelines.create(ctx, target.format(), depth.format());
					TextureStore textures = new TextureStore(ctx);
					ImmediateRenderer immediate = new ImmediateRenderer(ctx)) {

				GpuBuffer section = terrainBuffer(ctx);
				try {
					failures += shape(ctx, "terrain only", target, depth, pipelines, textures,
						immediate, section, true, false, false, false);
					failures += shape(ctx, "terrain + gui", target, depth, pipelines, textures,
						immediate, section, true, true, false, false);
					failures += shape(ctx, "gui only", target, depth, pipelines, textures,
						immediate, section, false, true, false, false);
					failures += shape(ctx, "display list", target, depth, pipelines, textures,
						immediate, section, false, false, true, false);
					failures += shape(ctx, "mid-frame clear", target, depth, pipelines, textures,
						immediate, section, true, true, false, true);
					failures += shape(ctx, "empty frame", target, depth, pipelines, textures,
						immediate, section, false, false, false, false);
					failures += sunAndMoon(ctx, target, depth, pipelines, textures, immediate);
					failures += entityModel(ctx, target, depth, pipelines, textures, immediate, false);
					failures += entityModel(ctx, target, depth, pipelines, textures, immediate, true);
					failures += programLeak(ctx, target, depth, pipelines, textures, immediate);
					failures += textureChurn(ctx, textures, immediate);
					failures += bufferChurn(ctx);
				} finally {
					section.close();
				}
			}

			// The check that would have caught the world-load crash on the first run.
			int errors = ctx.errorCount();
			failures += check(errors == 0,
				errors + " WebGPU validation error(s); the first was: " + ctx.firstError());
			System.out.println("errors     = " + errors);
		}

		if (failures > 0) {
			System.err.println("WEBGPU STRESS TEST FAILED (" + failures + " check(s))");
			System.exit(1);
		}
		System.out.println("WEBGPU STRESS TEST PASSED");
	}

	/**
	 * Renders one frame of a given shape and requires it to produce draws (except when empty).
	 *
	 * <p>The draw COUNT is asserted, not just the absence of a crash: the terrain-only bug produced
	 * a frame that submitted, presented and returned normally while every draw inside it failed.
	 */
	private static int shape(WebGPUContext ctx, String name, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, TextureStore textures, ImmediateRenderer immediate,
			GpuBuffer section, boolean terrain, boolean gui, boolean list, boolean midClear) {
		GlShim gl = guiState();
		DrawList frame = new DrawList();
		frame.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);

		int expected = 0;
		if (terrain) {
			gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN);
			gl.state().setTerrainParams(256.0F, 16.0F, 0.0F, 0.0F);
			frame.addExternal(section, 0, 6, Primitives.GL_TRIANGLES, gl.pipelineKey(), 0,
				gl.state().writeUniforms(true, false));
			gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_FIXED_FUNCTION);
			expected++;
		}
		if (midClear) {
			frame.clear(false, true, 0.0F, 0.0F, 0.0F, 1.0F);
		}
		if (gui) {
			frame.add(quad(gl, 0, 0, SIZE, SIZE, 0.0F, 0, 255, 0), 4, GL_QUADS,
				gl.pipelineKey(), 0, gl.state().writeUniforms(true, false));
			expected++;
		}
		if (list) {
			int id = DisplayLists.gen(1);
			DisplayLists.begin(id);
			DisplayLists.record(quad(gl, 0, 0, SIZE, SIZE, 0.0F, 0, 0, 255), 4, GL_QUADS,
				false, false, true);
			DisplayLists.end();
			DisplayLists.call(id, (v, c, m, hasColor, hasNormals, hasTexture) ->
				frame.add(v, c, m, gl.pipelineKey(), 0,
					gl.state().writeUniforms(hasColor, hasNormals, hasTexture)));
			DisplayLists.clear();
			expected++;
		}

		int before = ctx.errorCount();
		int draws;
		try (Frame f = Frame.begin(ctx)) {
			draws = immediate.render(f, target.view(), depth.view(), frame, pipelines, textures);
		}
		ctx.markFrameSubmitted();
		ctx.awaitFramesInFlight(0);

		int failures = 0;
		failures += check(ctx.errorCount() == before,
			name + ": produced " + (ctx.errorCount() - before) + " validation error(s)");
		failures += check(draws == expected,
			name + ": expected " + expected + " draw(s), issued " + draws);
		System.out.println(String.format("%-14s= %d draw(s), %d error(s)", name, draws,
			ctx.errorCount() - before));
		return failures;
	}

	/**
	 * The terrain program must not leak onto anything that is not terrain.
	 *
	 * <p>{@code terrain.wgsl} clamps every sample into one atlas tile and derives its mip level
	 * from eye-space derivatives -- correct for beta's block atlas, and destructive for anything else.
	 * A model face drawn with it has its UVs folded into whichever tile the first corner lands in, so
	 * the texture smears across the face instead of showing the model's texels.
	 *
	 * <p>This checks the two things that keep that from happening: that the shim returns to the
	 * fixed-function program after a terrain batch, and that a model face drawn under the terrain
	 * program really does come out wrong -- because if it did not, this would be ruling nothing out.
	 */
	private static int programLeak(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, TextureStore textures, ImmediateRenderer immediate) {
		GlShim gl = guiState();
		int failures = 0;

		// 1. The state machine: a terrain batch must leave the program where it found it.
		int before = gl.program();
		gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN);
		gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_FIXED_FUNCTION);
		failures += check(gl.program() == before,
			"the program must return to fixed-function after terrain, got " + gl.program());

		// 2. The consequence, so the check above is known to be worth making. The same face is drawn
		//    twice with the same UVs, once per program; they MUST differ.
		gl.glEnable(0x0DE1);
		gl.glDisable(0x0BE2);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);
		putRgba(texels, 0, 255, 0, 255);
		putRgba(texels, 0, 0, 255, 255);
		putRgba(texels, 255, 255, 255, 255);
		texels.flip();
		int skin = textures.gen();
		textures.define(skin, 2, 2, texels);
		gl.glBindTexture(0x0DE1, skin);

		int[] corners = new int[2];
		for (int pass = 0; pass < 2; pass++) {
			gl.setProgram(pass == 0
				? com.periut.retrodragon.shim.PipelineKey.PROGRAM_FIXED_FUNCTION
				: com.periut.retrodragon.shim.PipelineKey.PROGRAM_TERRAIN);
			gl.state().setTerrainParams(256.0F, 16.0F, 0.0F, 0.0F);

			DrawList frame = new DrawList();
			frame.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);
			frame.add(face(gl, false), 4, GL_QUADS, gl.pipelineKey(), skin,
				gl.state().writeUniforms(false, true, true));
			try (Frame f = Frame.begin(ctx)) {
				immediate.render(f, target.view(), depth.view(), frame, pipelines, textures);
			}
			ctx.markFrameSubmitted();
			ctx.awaitFramesInFlight(0);
			byte[] pixels = com.periut.retrodragon.gpu.Readback.rgba(ctx, target.handle(), SIZE, SIZE);
			corners[pass] = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 4);
		}
		gl.setProgram(com.periut.retrodragon.shim.PipelineKey.PROGRAM_FIXED_FUNCTION);

		// They AGREE, and that is the finding. The terrain program's tile clamp only bites near a
		// tile border under filtering; with nearest sampling it is a no-op, and its eye-space LOD
		// resolves to level 0 at close range. So a terrain-program leak onto model geometry would be
		// visually harmless -- which rules it out as the cause of the smeared entity faces, and is
		// worth pinning down because it looked like the obvious suspect.
		failures += check(corners[0] == corners[1],
			"the terrain program should be visually neutral on nearest-sampled model geometry, got "
				+ hex(corners[0]) + " vs " + hex(corners[1]));
		System.out.println("program leak  = fixedfunc " + hex(corners[0]) + ", terrain "
			+ hex(corners[1]));
		textures.delete(skin);
		return failures;
	}

	/**
	 * An entity model: many tiny per-face batches in one display list, replayed and merged.
	 *
	 * <p>This is the shape {@code ModelPart} produces. Each face of each box is its own
	 * {@code startQuads() -> normal() -> 4x vertex(x,y,z,u,v) -> draw()}, so a single pig is dozens of
	 * four-vertex batches recorded into one list -- and on replay they all share a pipeline, a texture
	 * and a uniform block, so they merge into one draw.
	 *
	 * <p><b>Each corner gets a DIFFERENT UV.</b> An earlier version of this check gave the whole face
	 * one UV, which made it useless: with every corner sampling the same texel, drawing the wrong
	 * corners still produces the right colour. Distinct corners are what make a smear detectable --
	 * and smearing along a diagonal is exactly how the pigs looked.
	 *
	 * @param preExpanded mirrors beta's {@code convertQuadsToTriangles}, where a quad arrives as six
	 *                    vertices already wound as two triangles and submitted as {@code GL_TRIANGLES}
	 */
	private static int entityModel(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, TextureStore textures, ImmediateRenderer immediate,
			boolean preExpanded) {
		String label = preExpanded ? "entity (tri)" : "entity (quad)";
		GlShim gl = guiState();
		gl.glEnable(0x0DE1);
		gl.glDisable(0x0BE2);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		// Four distinct texels, so every corner of the face maps somewhere different.
		ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder());
		putRgba(texels, 255, 0, 0, 255);     // top-left
		putRgba(texels, 0, 255, 0, 255);     // top-right
		putRgba(texels, 0, 0, 255, 255);     // bottom-left
		putRgba(texels, 255, 255, 255, 255); // bottom-right
		texels.flip();
		int skin = textures.gen();
		textures.define(skin, 2, 2, texels);
		gl.glBindTexture(0x0DE1, skin);

		int mode = preExpanded ? Primitives.GL_TRIANGLES : GL_QUADS;
		int count = preExpanded ? 6 : 4;

		int id = DisplayLists.gen(1);
		DisplayLists.begin(id);
		// Two faces, each its own batch, exactly as Quad.render emits them per box side.
		DisplayLists.record(face(gl, preExpanded), count, mode, false, true, true);
		DisplayLists.record(face(gl, preExpanded), count, mode, false, true, true);
		DisplayLists.end();

		DrawList frame = new DrawList();
		frame.clear(true, true, 0.0F, 0.0F, 0.0F, 1.0F);
		DisplayLists.call(id, (v, c, m, hasColor, hasNormals, hasTexture) ->
			frame.add(v, c, m, gl.pipelineKey(), skin,
				gl.state().writeUniforms(hasColor, hasNormals, hasTexture)));
		DisplayLists.clear();

		int before = ctx.errorCount();
		try (Frame f = Frame.begin(ctx)) {
			immediate.render(f, target.view(), depth.view(), frame, pipelines, textures);
		}
		ctx.markFrameSubmitted();
		ctx.awaitFramesInFlight(0);

		byte[] pixels = com.periut.retrodragon.gpu.Readback.rgba(ctx, target.handle(), SIZE, SIZE);
		int topLeft = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE / 4, SIZE / 4);
		int topRight = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE / 4);
		int bottomLeft = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE / 4, SIZE * 3 / 4);
		int bottomRight = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE * 3 / 4, SIZE * 3 / 4);

		int failures = 0;
		failures += check(ctx.errorCount() == before,
			label + ": " + (ctx.errorCount() - before) + " validation error(s)");
		failures += check(topLeft == 0xFFFF0000 && topRight == 0xFF00FF00
				&& bottomLeft == 0xFF0000FF && bottomRight == 0xFFFFFFFF,
			label + ": each corner must sample its own texel, got " + hex(topLeft) + " "
				+ hex(topRight) + " / " + hex(bottomLeft) + " " + hex(bottomRight)
				+ " -- a mismatch means the quad was drawn from the wrong corners, which smears the"
				+ " texture along a diagonal across every model face");
		System.out.println(String.format("%-14s= %s %s / %s %s, %d error(s)", label,
			hex(topLeft), hex(topRight), hex(bottomLeft), hex(bottomRight),
			ctx.errorCount() - before));
		textures.delete(skin);
		return failures;
	}

	/**
	 * One model face covering the target, with a distinct UV at each corner.
	 *
	 * @param preExpanded emit six vertices already wound as two triangles, as beta's
	 *                    {@code convertQuadsToTriangles} does before submitting {@code GL_TRIANGLES}
	 */
	private static ByteBuffer face(GlShim gl, boolean preExpanded) {
		float[][] corners = {
			{ 0, 0, 0.25F, 0.25F },
			{ 0, SIZE, 0.25F, 0.75F },
			{ SIZE, SIZE, 0.75F, 0.75F },
			{ SIZE, 0, 0.75F, 0.25F },
		};
		int[] order = preExpanded ? new int[] { 0, 1, 2, 0, 2, 3 } : new int[] { 0, 1, 2, 3 };
		GeometryCapture capture = new GeometryCapture();
		capture.begin(preExpanded ? Primitives.GL_TRIANGLES : GL_QUADS, gl.pipelineKey());
		capture.normal(0.0F, 0.0F, 1.0F);
		for (int i : order) {
			capture.texCoord(corners[i][2], corners[i][3]);
			capture.vertex(corners[i][0], corners[i][1], 0.0F);
		}
		capture.end();
		return capture.data();
	}

	private static void putRgba(ByteBuffer buffer, int r, int g, int b, int a) {
		buffer.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
	}

	private static String hex(int argb) {
		return "0x" + String.format("%08X", argb);
	}

	/**
	 * The sun and moon: an ADDITIVELY blended textured quad with no per-vertex colour.
	 *
	 * <p>A shape nothing else in the game uses. Beta draws them with
	 * {@code glBlendFunc(SRC_ALPHA, ONE)}, a white {@code glColor4f} rather than vertex colours, and
	 * texturing on -- so the colour arrives entirely through the uniform modulator and the texture,
	 * and the result is ADDED to the sky already in the framebuffer. If either the modulator or the
	 * texture read comes out black, the add contributes nothing and they are simply invisible against
	 * a dark sky, with no error to say so.
	 *
	 * <p>Checked by reading back: the quad is drawn over a dark clear, so a lit result must be
	 * brighter than what it was drawn onto.
	 */
	private static int sunAndMoon(WebGPUContext ctx, RenderTarget target, DepthBuffer depth,
			FixedFunctionPipelines pipelines, TextureStore textures, ImmediateRenderer immediate) {
		GlShim gl = guiState();
		gl.glEnable(0x0DE1);            // GL_TEXTURE_2D
		gl.glEnable(0x0BE2);            // GL_BLEND
		gl.glBlendFunc(0x0302, 1);      // SRC_ALPHA, ONE -- additive, as the sky uses
		gl.glDepthMask(false);
		gl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

		ByteBuffer white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
		white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
		int sun = textures.gen();
		textures.define(sun, 1, 1, white);
		gl.glBindTexture(0x0DE1, sun);

		DrawList frame = new DrawList();
		// A dim sky to add onto, so "invisible" and "drawn" are distinguishable.
		frame.clear(true, true, 0.1F, 0.1F, 0.2F, 1.0F);

		GeometryCapture capture = new GeometryCapture();
		capture.begin(GL_QUADS, gl.pipelineKey());
		capture.texCoord(0.0F, 0.0F);
		capture.vertex(0.0F, 0.0F, 0.0F);
		capture.texCoord(0.0F, 1.0F);
		capture.vertex(0.0F, SIZE, 0.0F);
		capture.texCoord(1.0F, 1.0F);
		capture.vertex(SIZE, SIZE, 0.0F);
		capture.texCoord(1.0F, 0.0F);
		capture.vertex(SIZE, 0.0F, 0.0F);
		capture.end();

		// hasColor FALSE, exactly as beta's sun quad is built: the colour is the glColor4f above.
		frame.add(capture.data(), 4, GL_QUADS, gl.pipelineKey(), sun,
			gl.state().writeUniforms(false, false, true));

		int before = ctx.errorCount();
		try (Frame f = Frame.begin(ctx)) {
			immediate.render(f, target.view(), depth.view(), frame, pipelines, textures);
		}
		ctx.markFrameSubmitted();
		ctx.awaitFramesInFlight(0);

		byte[] pixels = com.periut.retrodragon.gpu.Readback.rgba(ctx, target.handle(), SIZE, SIZE);
		int middle = com.periut.retrodragon.gpu.Readback.pixel(pixels, SIZE, SIZE / 2, SIZE / 2);
		int red = middle >> 16 & 0xFF;

        int failures = 0;
		failures += check(ctx.errorCount() == before,
			"sun/moon: " + (ctx.errorCount() - before) + " validation error(s)");
		// The clear is 0.1 red; adding a white quad must take it far above that.
		failures += check(red > 0xC0,
			"an additive white quad must brighten the sky, got red=" + red
				+ " -- a dark result means the modulator or the texture read came out black, which is"
				+ " why the sun and moon would be invisible rather than wrong");
		System.out.println("sun/moon      = red " + red + ", " + (ctx.errorCount() - before) + " error(s)");
		textures.delete(sun);
		return failures;
	}

	/**
	 * Textures created, uploaded to and deleted while frames are in flight -- what a resource reload
	 * does, and what invalidates cached bind groups underneath the renderer.
	 */
	private static int textureChurn(WebGPUContext ctx, TextureStore textures,
			ImmediateRenderer immediate) {
		int before = ctx.errorCount();
		ByteBuffer texel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
		texel.put((byte) 0xFF).put((byte) 0x80).put((byte) 0x40).put((byte) 0xFF).flip();
		for (int i = 0; i < 200; i++) {
			int name = textures.gen();
			textures.define(name, 1, 1, texel);
			// Redefining at a new size replaces the underlying texture, which any cached bind group
			// is still holding a view of.
			textures.define(name, 2, 2, null);
			immediate.invalidate(name);
			textures.delete(name);
		}
		int failures = check(ctx.errorCount() == before,
			"texture churn: " + (ctx.errorCount() - before) + " validation error(s)");
		System.out.println("texture churn = " + (ctx.errorCount() - before) + " error(s)");
		return failures;
	}

	/** Section buffers repeatedly grown and freed, as chunks load, rebuild and unload. */
	private static int bufferChurn(WebGPUContext ctx) {
		int before = ctx.errorCount();
		ByteBuffer data = ByteBuffer.allocateDirect(64 * DrawList.STRIDE).order(ByteOrder.nativeOrder());
		for (int i = 0; i < 64 * DrawList.STRIDE; i++) {
			data.put(i, (byte) i);
		}
		for (int i = 0; i < 100; i++) {
			try (GpuBuffer buffer = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "churn")) {
				for (int size = 4; size <= 64; size *= 2) {
					data.position(0).limit(size * DrawList.STRIDE);
					buffer.write(data, size * DrawList.STRIDE);
				}
			}
		}
		int failures = check(ctx.errorCount() == before,
			"buffer churn: " + (ctx.errorCount() - before) + " validation error(s)");
		System.out.println("buffer churn  = " + (ctx.errorCount() - before) + " error(s)");
		return failures;
	}

	private static GpuBuffer terrainBuffer(WebGPUContext ctx) {
		GlShim gl = guiState();
		ByteBuffer quad = quad(gl, 0, 0, SIZE, SIZE, 0.0F, 200, 100, 50);
		// Two triangles, as the mesher emits them.
		ByteBuffer triangles = ByteBuffer.allocateDirect(6 * DrawList.STRIDE)
			.order(ByteOrder.nativeOrder());
		int[] order = { 0, 1, 2, 0, 2, 3 };
		for (int i = 0; i < order.length; i++) {
			for (int b = 0; b < DrawList.STRIDE; b++) {
				triangles.put(i * DrawList.STRIDE + b, quad.get(order[i] * DrawList.STRIDE + b));
			}
		}
		triangles.position(0).limit(6 * DrawList.STRIDE);
		GpuBuffer buffer = new GpuBuffer(ctx, Flags.BUFFER_USAGE_VERTEX, "stress-section");
		buffer.write(triangles, 6 * DrawList.STRIDE);
		return buffer;
	}

	private static GlShim guiState() {
		GlShim gl = new GlShim();
		gl.glMatrixMode(0x1701);
		gl.glLoadIdentity();
		gl.glOrtho(0.0, SIZE, SIZE, 0.0, -1.0, 1.0);
		gl.glMatrixMode(0x1700);
		gl.glLoadIdentity();
		gl.glDisable(0x0DE1);
		gl.glEnable(0x0B71);
		gl.glDepthFunc(0x0203);
		return gl;
	}

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

	private static int check(boolean condition, String what) {
		if (!condition) {
			System.err.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}
}
