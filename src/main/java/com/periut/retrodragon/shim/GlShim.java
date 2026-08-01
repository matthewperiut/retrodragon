package com.periut.retrodragon.shim;

import java.nio.ByteBuffer;

/**
 * The GL entry points beta and mods call, translated into {@link GlState} plus a
 * {@link PipelineKey}.
 *
 * This is the seam. Under GL, an unported path still renders because the driver handles it; under
 * WebGPU nothing renders unless it passes through here. So the surface has to cover what beta
 * actually calls rather than growing incrementally -- the same scoping exercise that shrank the SDL3
 * window work from 837 lines to ~30 methods.
 *
 * GL enums are mapped to small dense indices at the boundary. Two reasons: the pipeline key packs
 * into a long, and the raw GL constants are sparse 16-bit values that would waste most of it.
 *
 * Nothing here reads back from a driver. The shim is the authority on this state, which is what
 * makes a draw cost a lookup instead of a round-trip.
 */
public final class GlShim {
	private final GlState state = new GlState();

	// Pipeline-defining state, kept as dense indices ready for the key.
	private int blendSrc;
	private int blendDst;
	private int depthFunc = DEPTH_LEQUAL;
	private boolean depthWrite = true;
	private boolean depthTest;
	private boolean blend;
	/**
	 * Culling, as GL actually models it: TWO independent pieces of state.
	 *
	 * <p>{@code glEnable(GL_CULL_FACE)} decides whether culling happens at all; {@code glCullFace}
	 * decides which side is discarded, and is remembered whether or not culling is currently on. WebGPU
	 * has a single {@code cullMode} covering both, and collapsing them into one field here meant each
	 * call clobbered the other's half:
	 *
	 * <ul>
	 *   <li>{@code glCullFace(GL_BACK)} with culling DISABLED turned culling on -- geometry GL draws
	 *       vanished. Beta sets the face and the enable separately, and not always in that order.</li>
	 *   <li>{@code glEnable(GL_CULL_FACE)} after {@code glCullFace(GL_FRONT)} reverted to back-culling,
	 *       so the wrong side was discarded.</li>
	 * </ul>
	 *
	 * <p>Both directions matter for anything built from coplanar back-to-back faces -- beta's extruded
	 * held items are two oppositely-facing walls at every texel boundary, each textured with its own
	 * side's texel, so culling the wrong one shows the neighbouring texel's colour and culling neither
	 * lets the two z-fight.
	 */
	private boolean cullEnabled;

	/** GL's initial {@code glCullFace} value is {@code GL_BACK}, whether or not culling is enabled. */
	private int cullFace = PipelineKey.CULL_BACK;

	private int topology = PipelineKey.TOPOLOGY_TRIANGLES;
	private int program = PipelineKey.PROGRAM_FIXED_FUNCTION;

	// Dense blend factors, covering what beta uses.
	public static final int BLEND_ZERO = 0;
	public static final int BLEND_ONE = 1;
	public static final int BLEND_SRC_ALPHA = 2;
	public static final int BLEND_ONE_MINUS_SRC_ALPHA = 3;
	public static final int BLEND_DST_COLOR = 4;
	public static final int BLEND_SRC_COLOR = 5;
	public static final int BLEND_ONE_MINUS_DST_COLOR = 6;
	public static final int BLEND_ONE_MINUS_SRC_COLOR = 7;

	// Dense depth functions.
	public static final int DEPTH_NEVER = 0;
	public static final int DEPTH_LESS = 1;
	public static final int DEPTH_EQUAL = 2;
	public static final int DEPTH_LEQUAL = 3;
	public static final int DEPTH_GREATER = 4;
	public static final int DEPTH_NOTEQUAL = 5;
	public static final int DEPTH_GEQUAL = 6;
	public static final int DEPTH_ALWAYS = 7;

	public GlState state() {
		return state;
	}

	// --- matrix ---------------------------------------------------------------------------------

	public void glMatrixMode(int mode) {
		// GL_MODELVIEW 0x1700, GL_PROJECTION 0x1701.
		state.matrixMode(mode == 0x1701 ? GlState.MODE_PROJECTION : GlState.MODE_MODELVIEW);
	}

	public void glPushMatrix() {
		state.pushMatrix();
	}

	public void glPopMatrix() {
		state.popMatrix();
	}

	public void glLoadIdentity() {
		state.loadIdentity();
	}

	public void glTranslatef(float x, float y, float z) {
		state.translate(x, y, z);
	}

	public void glRotatef(float angle, float x, float y, float z) {
		state.rotate(angle, x, y, z);
	}

	public void glScalef(float x, float y, float z) {
		state.scale(x, y, z);
	}

	public void glMultMatrixf(float[] m) {
		state.multMatrix(m);
	}

	public void glLoadMatrixf(float[] m) {
		state.loadMatrix(m);
	}

	/**
	 * Reads 16 floats from {@code m}'s current position WITHOUT consuming them -- the real GL call
	 * does not advance the buffer either, and beta reuses one buffer across frames.
	 */
	public void glMultMatrix(java.nio.FloatBuffer m) {
		state.multMatrix(read16(m));
	}

	public void glLoadMatrix(java.nio.FloatBuffer m) {
		state.loadMatrix(read16(m));
	}

	private final float[] matrixScratch = new float[16];

	private float[] read16(java.nio.FloatBuffer m) {
		int base = m.position();
		for (int i = 0; i < 16; i++) {
			matrixScratch[i] = m.get(base + i);
		}
		return matrixScratch;
	}

	public void glOrtho(double left, double right, double bottom, double top, double near, double far) {
		state.ortho(left, right, bottom, top, near, far);
	}

	public void glFrustum(double left, double right, double bottom, double top, double near, double far) {
		state.frustum(left, right, bottom, top, near, far);
	}

	// --- viewport / scissor ------------------------------------------------------------------------

	private int viewportX;
	private int viewportY;
	private int viewportWidth;
	private int viewportHeight;

	/**
	 * GL's viewport origin is the BOTTOM-left of the framebuffer; WebGPU's is the top-left. The
	 * conversion needs the framebuffer height, which the shim does not know, so the raw GL values
	 * are stored and flipped where the pass is recorded.
	 */
	public void glViewport(int x, int y, int width, int height) {
		viewportX = x;
		viewportY = y;
		viewportWidth = width;
		viewportHeight = height;
	}

	public int viewportX() {
		return viewportX;
	}

	public int viewportY() {
		return viewportY;
	}

	public int viewportWidth() {
		return viewportWidth;
	}

	public int viewportHeight() {
		return viewportHeight;
	}

	// --- clear state -------------------------------------------------------------------------------
	//
	// GL keeps the clear colour as state and applies it whenever glClear is called. WebGPU has no
	// clear command at all -- a clear is a render pass LOAD OP -- so the colour is held here and read
	// when the pass that clears is opened.

	private float clearRed;
	private float clearGreen;
	private float clearBlue;
	private float clearAlpha = 1.0F;

	public void glClearColor(float r, float g, float b, float a) {
		clearRed = r;
		clearGreen = g;
		clearBlue = b;
		clearAlpha = a;
	}

	public float clearRed() {
		return clearRed;
	}

	public float clearGreen() {
		return clearGreen;
	}

	public float clearBlue() {
		return clearBlue;
	}

	public float clearAlpha() {
		return clearAlpha;
	}

	// --- colour mask -------------------------------------------------------------------------------

	private boolean colorMaskRed = true;
	private boolean colorMaskGreen = true;
	private boolean colorMaskBlue = true;
	private boolean colorMaskAlpha = true;

	/**
	 * Part of the pipeline key -- see {@link PipelineKey#withColorMask}.
	 *
	 * <p>It used to be tracked and dropped, on the reasoning that only anaglyph 3D masks colour. That
	 * was wrong: fancy graphics (the default) masks all four channels to draw the translucent layer
	 * as a depth-only pre-pass before drawing it again in colour, and so do fancy clouds. Dropping
	 * the mask blended both of them twice, which is why water was near opaque.
	 *
	 * <p>Costs nothing while the mask is all-on, which is every other draw in the game: that state
	 * encodes as zero and keys identically to before.
	 */
	public void glColorMask(boolean r, boolean g, boolean b, boolean a) {
		colorMaskRed = r;
		colorMaskGreen = g;
		colorMaskBlue = b;
		colorMaskAlpha = a;
	}

	public boolean colorMaskAll() {
		return colorMaskRed && colorMaskGreen && colorMaskBlue && colorMaskAlpha;
	}

	// --- readback ----------------------------------------------------------------------------------

	/**
	 * Answers {@code glGetFloatv} from the shim's own state rather than a driver.
	 *
	 * <p>beta reads the modelview and projection matrices back to build its picking ray. There is no
	 * driver to ask under WebGPU, and the shim is the authority anyway.
	 */
	public void glGetFloatv(int name, java.nio.FloatBuffer params) {
		float[] source = switch (name) {
			case 0x0BA6 -> state.modelView();      // GL_MODELVIEW_MATRIX
			case 0x0BA7 -> state.projection();     // GL_PROJECTION_MATRIX
			default -> null;
		};
		if (source == null) {
			return;
		}
		int base = params.position();
		for (int i = 0; i < 16 && base + i < params.limit(); i++) {
			params.put(base + i, source[i]);
		}
	}

	// --- texture binding ---------------------------------------------------------------------------

	private float polygonOffsetFactor;
	private float polygonOffsetUnits;
	/** GL_POLYGON_OFFSET_FILL; GL ignores the offset entirely while this is off. */
	private boolean polygonOffsetFill;

	private int boundTexture;

	/** GL texture NAME, not a WebGPU handle; the backend maps names to textures it owns. */
	public void glBindTexture(int target, int texture) {
		if (target == 0x0DE1) { // GL_TEXTURE_2D
			boundTexture = texture;
		}
	}

	public int boundTexture() {
		return boundTexture;
	}

	// --- colour ---------------------------------------------------------------------------------

	public void glColor4f(float r, float g, float b, float a) {
		state.color(r, g, b, a);
	}

	public void glColor3f(float r, float g, float b) {
		state.color(r, g, b, 1.0F);
	}

	// --- enable / disable -------------------------------------------------------------------------

	public void glEnable(int cap) {
		setCap(cap, true);
	}

	public void glDisable(int cap) {
		setCap(cap, false);
	}

	private void setCap(int cap, boolean on) {
		switch (cap) {
			case 0x0BE2 -> blend = on;             // GL_BLEND
			case 0x0B71 -> depthTest = on;         // GL_DEPTH_TEST
			case 0x0B44 -> cullEnabled = on;               // GL_CULL_FACE
			case 0x0DE1 -> state.setTextureEnabled(on);   // GL_TEXTURE_2D
			case 0x0B50 -> state.setLightingEnabled(on);  // GL_LIGHTING
			case 0x8037 -> polygonOffsetFill = on;        // GL_POLYGON_OFFSET_FILL
			case 0x0B60 -> state.setFogEnabled(on);       // GL_FOG
			case 0x0BC0 -> state.setAlphaTestEnabled(on); // GL_ALPHA_TEST
			default -> {
				// Caps that do not affect this pipeline are intentionally ignored.
			}
		}
	}

	// --- depth / blend / cull ---------------------------------------------------------------------

	public void glDepthMask(boolean write) {
		depthWrite = write;
	}

	public void glDepthFunc(int func) {
		// GL depth funcs are GL_NEVER 0x0200 .. GL_ALWAYS 0x0207, contiguous.
		depthFunc = func - 0x0200 & 0x7;
	}

	public void glBlendFunc(int src, int dst) {
		blendSrc = blendFactor(src);
		blendDst = blendFactor(dst);
	}

	public void glCullFace(int face) {
		// GL_FRONT 0x0404, GL_BACK 0x0405, GL_FRONT_AND_BACK 0x0408. Records the face only -- whether
		// it is acted on is glEnable(GL_CULL_FACE)'s business, and conflating the two is the bug this
		// pair of fields exists to prevent.
		//
		// GL_FRONT_AND_BACK has no WebGPU equivalent (it discards every triangle) and beta never sets
		// it, so it falls in with GL_BACK rather than growing a mode the pipeline key cannot encode.
		cullFace = face == 0x0404 ? PipelineKey.CULL_FRONT : PipelineKey.CULL_BACK;
	}

	/** The two halves of GL's cull state, resolved into the single mode a pipeline can carry. */
	private int cull() {
		return cullEnabled ? cullFace : PipelineKey.CULL_NONE;
	}

	private static int blendFactor(int glFactor) {
		return switch (glFactor) {
			case 0 -> BLEND_ZERO;
			case 1 -> BLEND_ONE;
			case 0x0300 -> BLEND_SRC_COLOR;
			case 0x0301 -> BLEND_ONE_MINUS_SRC_COLOR;
			case 0x0302 -> BLEND_SRC_ALPHA;
			case 0x0303 -> BLEND_ONE_MINUS_SRC_ALPHA;
			case 0x0306 -> BLEND_DST_COLOR;
			case 0x0307 -> BLEND_ONE_MINUS_DST_COLOR;
			default -> BLEND_ONE;
		};
	}

	// --- alpha test / fog -------------------------------------------------------------------------

	public void glAlphaFunc(int func, float ref) {
		// Only GL_GREATER is used by beta, and the shader's discard implements exactly that.
		state.setAlphaRef(ref);
	}

	public void glFogi(int name, int value) {
		if (name == 0x0B65) { // GL_FOG_MODE
			// Must update the shim's own copy too: glFogf re-sends all four parameters, so a stale
			// fogMode here silently reverts EXP back to LINEAR on the next glFogf call. That is the
			// underwater/lava fog defect, and the self-check caught it.
			fogMode = switch (value) {
				case 0x0800 -> GlState.FOG_EXP;   // GL_EXP
				case 0x0801 -> GlState.FOG_EXP2;  // GL_EXP2
				default -> GlState.FOG_LINEAR;    // GL_LINEAR 0x2601
			};
			state.setFog(fogMode, fogStart, fogEnd, fogDensity);
		}
	}

	private float fogStart;
	private float fogEnd = 1.0F;
	private float fogDensity;
	private int fogMode = GlState.FOG_LINEAR;

	public void glFogf(int name, float value) {
		switch (name) {
			case 0x0B63 -> fogStart = value;   // GL_FOG_START
			case 0x0B64 -> fogEnd = value;     // GL_FOG_END
			case 0x0B62 -> fogDensity = value; // GL_FOG_DENSITY
			default -> {
				// not a fog parameter we translate
			}
		}
		state.setFog(fogMode, fogStart, fogEnd, fogDensity);
	}

	public void glFogColor(float r, float g, float b, float a) {
		state.setFogColor(r, g, b, a);
	}

	// --- draw emission ----------------------------------------------------------------------------

	public void setTopology(int topology) {
		this.topology = topology;
	}

	/**
	 * Selects the shader program for the next draws; one of {@code PipelineKey.PROGRAM_*}.
	 *
	 * <p>Terrain uses its own, because tile-clamped LOD selection and rotated-grid supersampling are
	 * meaningless for a GUI quad and cost real fragment work.
	 */
	public void setProgram(int program) {
		this.program = program;
	}

	public int program() {
		return program;
	}

	/**
	 * The pipeline this draw needs. Cheap enough to call per draw -- it allocates nothing.
	 *
	 * <p>State that cannot affect the result is normalised away first. Beta reconfigures the blend
	 * factors constantly while {@code GL_BLEND} is disabled, and each distinct combination would
	 * otherwise build another pipeline that rasterises identically: exhaustively enumerating the raw
	 * key space produces 36864 pipelines, enough for Metal to complain about its compiled-variant
	 * footprint. Normalised, the same enumeration collapses to the few dozen that actually differ.
	 *
	 * <p>Depth writes are folded in for correctness rather than economy: GL performs no depth write
	 * when {@code GL_DEPTH_TEST} is disabled, whatever {@code glDepthMask} last said, while WebGPU
	 * would happily honour the write mask against an always-passing compare.
	 */
	public long pipelineKey() {
		int src = blend ? blendSrc : 0;
		int dst = blend ? blendDst : 0;
		int func = depthTest ? depthFunc : 0;
		boolean write = depthTest && depthWrite;
		// Normalised like the rest: GL applies no offset unless GL_POLYGON_OFFSET_FILL is on, so a
		// leftover glPolygonOffset must not split the pipeline space while it is disabled.
		int units = polygonOffsetFill ? Math.round(polygonOffsetUnits) : 0;
		int slope = polygonOffsetFill ? Math.round(polygonOffsetFactor) : 0;
		return withColorMask(PipelineKey.of(src, dst, func, write, depthTest, blend, cull(), topology,
			program, units, slope));
	}

	/** {@link PipelineKey#withColorMask} against the current mask; a no-op while all four are on. */
	private long withColorMask(long key) {
		return PipelineKey.withColorMask(key, colorMaskRed, colorMaskGreen, colorMaskBlue,
			colorMaskAlpha);
	}

	/**
	 * {@code glPolygonOffset} -- how beta stops the block-breaking overlay z-fighting with the face
	 * it is drawn on.
	 *
	 * <p>{@code WorldRenderer.renderMiningProgress} sets {@code (-3, -3)} with
	 * {@code GL_POLYGON_OFFSET_FILL} enabled, draws the crack overlay over the block's own
	 * triangles, then resets to {@code (0, 0)}. Without it the two surfaces are exactly coplanar and
	 * which one wins is decided per pixel by depth rounding, so the crack texture tears apart.
	 *
	 * <p>WebGPU spells the same thing as pipeline state rather than a call, which is why this feeds
	 * {@link #pipelineKey()}: {@code depthBias} is the units term and {@code depthBiasSlopeScale}
	 * the factor, computing GL's {@code factor * dz + units * r}.
	 */
	/**
	 * {@code glLight}. Only GL_POSITION is acted on.
	 *
	 * <p>The per-light GL_DIFFUSE and GL_AMBIENT terms are read and ignored on purpose: beta sets
	 * diffuse to 0.6 on both lights and per-light ambient to zero every time, and the shader applies
	 * exactly that. Carrying them would cost two more floats in a uniform block that is already
	 * exactly one 256-byte dynamic-offset slot.
	 */
	public void glLight(int light, int parameter, java.nio.FloatBuffer params) {
		if (params == null || parameter != 0x1203 || params.remaining() < 4) { // GL_POSITION
			return;
		}
		int base = params.position();
		state.setLightPosition(light, params.get(base), params.get(base + 1),
			params.get(base + 2), params.get(base + 3));
	}

	/** {@code glLightModel}; GL_LIGHT_MODEL_AMBIENT is the one beta sets. */
	public void glLightModel(int parameter, java.nio.FloatBuffer params) {
		if (params == null || parameter != 0x0B53 || params.remaining() < 3) {
			return;
		}
		int base = params.position();
		state.setLightModelAmbient(params.get(base), params.get(base + 1), params.get(base + 2));
	}

	/**
	 * The current pipeline key with a depth bias forced on.
	 *
	 * <p>For the expanded block outline. The outline traces a block's edges, which are shared with
	 * its neighbours, so at exactly equal depth it is a coin toss per pixel whether the line or the
	 * adjacent block wins -- the edges facing away from the camera flicker or vanish. A small pull
	 * towards the viewer settles it.
	 *
	 * <p>Deliberately small, and NOT a disabled depth test: edges genuinely behind other geometry
	 * have to stay hidden, which is what makes the outline read as wrapping the block.
	 *
	 * <p>Separate from {@link #glPolygonOffset} so the game's own offset state is untouched -- beta
	 * uses that for the block-breaking overlay and would otherwise see it change under it.
	 */
	public long pipelineKeyWithDepthBias(int units, int slope) {
		int src = blend ? blendSrc : 0;
		int dst = blend ? blendDst : 0;
		int func = depthTest ? depthFunc : 0;
		boolean write = depthTest && depthWrite;
		return withColorMask(PipelineKey.of(src, dst, func, write, depthTest, blend, cull(), topology,
			program, units, slope));
	}

	/**
	 * {@code glLineWidth}. Kept as plain state rather than pipeline state: WebGPU has no line width
	 * at all, so this is consumed by {@code LineExpander}, which turns wide lines into quads.
	 */
	private float lineWidth = 1.0F;

	public void glLineWidth(float width) {
		lineWidth = width;
	}

	public float lineWidth() {
		return lineWidth;
	}

	public void glPolygonOffset(float factor, float units) {
		polygonOffsetFactor = factor;
		polygonOffsetUnits = units;
	}

	public ByteBuffer uniforms(boolean vertexColors) {
		return state.writeUniforms(vertexColors);
	}

	/** {@code java com.periut.retrodragon.shim.GlShim} */
	public static void main(String[] args) {
		GlShim gl = new GlShim();

		// A sequence beta actually performs: set up a translucent, depth-tested, textured draw.
		gl.glEnable(0x0DE1);              // GL_TEXTURE_2D
        gl.glEnable(0x0B71);              // GL_DEPTH_TEST
		gl.glEnable(0x0BE2);              // GL_BLEND
		gl.glBlendFunc(0x0302, 0x0303);   // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
		gl.glDepthFunc(0x0203);           // GL_LEQUAL
		gl.glDepthMask(false);

		long key = gl.pipelineKey();
		check(PipelineKey.blend(key), "blend enabled reaches the key");
		check(PipelineKey.depthTest(key), "depth test reaches the key");
		check(!PipelineKey.depthWrite(key), "depth mask false reaches the key");
		check(PipelineKey.blendSrc(key) == BLEND_SRC_ALPHA, "src factor mapped");
		check(PipelineKey.blendDst(key) == BLEND_ONE_MINUS_SRC_ALPHA, "dst factor mapped");
		check(PipelineKey.depthFunc(key) == DEPTH_LEQUAL, "GL_LEQUAL mapped");

		// Same state must produce the same key -- otherwise the cache never hits.
		check(gl.pipelineKey() == key, "key is stable for unchanged state");

		// Toggling one thing must change the key, or draws share a wrong pipeline.
		gl.glDisable(0x0BE2);
		check(gl.pipelineKey() != key, "disabling blend must change the key");
		gl.glEnable(0x0BE2);
		check(gl.pipelineKey() == key, "re-enabling restores the same key");

		// Fog mode round-trips through the uniform block.
		gl.glFogi(0x0B65, 0x0800);        // GL_FOG_MODE = GL_EXP
		gl.glFogf(0x0B62, 0.1F);          // GL_FOG_DENSITY (beta's underwater value)
		gl.glEnable(0x0B60);              // GL_FOG
		// Uniform layout: modelView 0-15, projection 16-31, colorModulator 32-35, fogColor 36-39,
		// fogParams 40-43. The mode is fogParams.x, i.e. float 40.
		float mode = gl.uniforms(false).asFloatBuffer().get(40);
		check(mode == GlState.FOG_EXP, "EXP fog reaches the uniform block, got " + mode);

		// The matrix stack is driven through the same entry points.
		gl.glPushMatrix();
		gl.glTranslatef(1.0F, 2.0F, 3.0F);
		float tx = gl.uniforms(false).asFloatBuffer().get(12);
		check(Math.abs(tx - 1.0F) < 1e-5, "translate reaches the modelview uniform, got " + tx);
		gl.glPopMatrix();
		tx = gl.uniforms(false).asFloatBuffer().get(12);
		check(Math.abs(tx) < 1e-5, "pop restores the modelview, got " + tx);

		System.out.println("GlShim self-check OK");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
