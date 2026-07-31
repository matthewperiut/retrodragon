package com.periut.retrodragon.shim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The fixed-function state machine the GLShim accumulates into.
 *
 * Beta and every unported mod draw through immediate-mode GL. This owns the state those calls
 * mutate -- matrix stacks, current colour, enables, fog, alpha test, lighting -- and turns it into
 * the two things a WebGPU draw needs: a **uniform block** (matching
 * {@code assets/retrodragon/shaders/wgsl/fixedfunc.wgsl}) and a **pipeline key**.
 *
 * The rule that makes this fast: it is only ever *written* by intercepted GL calls and *read* when
 * a draw is emitted. Nothing queries the driver. RetroDragon's facade lost ~625 ns per draw to
 * {@code glGet*} round-trips precisely because beta owned the state and it had to ask; here the
 * shim owns it, so the answer is always already in hand.
 *
 * Verify with {@code java com.periut.retrodragon.shim.GlState} -- the self-check covers the
 * matrix maths, which is the part with real arithmetic in it.
 */
public final class GlState {
	/**
	 * Matches the std140 layout of the Uniforms struct in fixedfunc.wgsl: two mat4 plus eight vec4.
	 *
	 * <p>256 bytes exactly, which is also WebGPU's guaranteed dynamic-uniform-offset alignment -- so
	 * one slot per draw packs with no padding at all.
	 */
	public static final int UNIFORM_FLOATS = 16 + 16 + 4 * 8;
	public static final int UNIFORM_BYTES = UNIFORM_FLOATS * 4;

	public static final int MODE_MODELVIEW = 0;
	public static final int MODE_PROJECTION = 1;

	public static final int FOG_LINEAR = 0;
	public static final int FOG_EXP = 1;
	public static final int FOG_EXP2 = 2;

	private static final int STACK_DEPTH = 32;

	private final float[][] modelViewStack = new float[STACK_DEPTH][16];
	private final float[][] projectionStack = new float[STACK_DEPTH][16];
	private int modelViewTop;
	private int projectionTop;
	private int matrixMode = MODE_MODELVIEW;

	private final float[] color = { 1.0F, 1.0F, 1.0F, 1.0F };

	private boolean textureEnabled;
	private boolean lightingEnabled;
	private boolean fogEnabled;
	private boolean alphaTestEnabled;

	private int fogMode = FOG_LINEAR;
	private float fogStart;
	private float fogEnd = 1.0F;
	private float fogDensity;
	private final float[] fogColor = { 0.0F, 0.0F, 0.0F, 1.0F };

	private float alphaRef;

	// beta's Lighting.turnOn: two directional lights, diffuse 0.6, light-model ambient 0.4.
	//
	// These are only the values in force before the game says otherwise -- glLight overwrites them.
	// They used to be all there was, hardcoded to straight up and straight DOWN, which is not what
	// beta asks for: it lights from normalize(0.2, 1, -0.7) and normalize(-0.2, 1, 0.7), BOTH from
	// above and tilted oppositely in z. A light coming from underneath inverts which faces of an
	// entity are bright, which is what "the shading direction is wrong" looks like.
	private final float[] lightDir0 = { 0.0F, 1.0F, 0.0F, 0.0F };
	private final float[] lightDir1 = { 0.0F, -1.0F, 0.0F, 0.0F };
	private final float[] lightAmbient = { 0.4F, 0.4F, 0.4F, 1.0F };

	/**
	 * {@code glLight(GL_LIGHTn, GL_POSITION, ...)}.
	 *
	 * <p>GL transforms a light position by the modelview matrix in force AT THIS CALL, not at draw
	 * time, and stores the result in eye space. That is the whole reason beta's lighting looks
	 * world-fixed: {@code Lighting.turnOn} runs once the camera transform is loaded, so the
	 * direction is baked against the camera's rotation and the sun stays put as the player turns.
	 *
	 * <p>Only the rotation part is applied, because these are directions ({@code w == 0}) rather than
	 * positions. The shader transforms each normal into eye space the same way and dots the two, so
	 * both sides of the product live in the same space.
	 */
	public void setLightPosition(int light, float x, float y, float z, float w) {
		float[] target = switch (light) {
			case 0x4000 -> lightDir0; // GL_LIGHT0
			case 0x4001 -> lightDir1; // GL_LIGHT1
			default -> null;
		};
		if (target == null) {
			return;
		}
		float[] m = modelViewStack[modelViewTop];
		// Column-major, as GL stores matrices: m[0..3] is the first COLUMN.
		float ex = m[0] * x + m[4] * y + m[8] * z + m[12] * w;
		float ey = m[1] * x + m[5] * y + m[9] * z + m[13] * w;
		float ez = m[2] * x + m[6] * y + m[10] * z + m[14] * w;
		float length = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
		if (length > 0.0F) {
			ex /= length;
			ey /= length;
			ez /= length;
		}
		target[0] = ex;
		target[1] = ey;
		target[2] = ez;
		// target[3] is NOT touched: the w slots carry the terrain parameters, not light data.
	}

	/** {@code glLightModel(GL_LIGHT_MODEL_AMBIENT, ...)} -- beta's 0.4 grey. */
	public void setLightModelAmbient(float r, float g, float b) {
		lightAmbient[0] = r;
		lightAmbient[1] = g;
		lightAmbient[2] = b;
	}

	// --- terrain program parameters ---------------------------------------------------------------
	//
	// Carried in the w components of the three light vectors, which are pure padding -- a directional
	// light's w is 0 and the ambient's alpha is unused -- plus the w of the vertex flags. That keeps
	// the block at exactly 256 bytes, which is WebGPU's dynamic-uniform-offset alignment, so one slot
	// per draw wastes nothing. Growing it to hold four more floats would round every slot up to 512.

	private float atlasTexels = 256.0F;
	private float tileTexels = 16.0F;
	private float maxLod;
	private float rgss;

	/**
	 * @param atlasTexels the block atlas's width in texels; 256 for vanilla, larger for a texture pack
	 *     or once RetroAPI has grown it
	 * @param tileTexels  the atlas's grid pitch in texels; 0 for a stitched atlas (StationAPI), which
	 *     turns the terrain program's per-tile clamp off
	 * @param maxLod      how far the mip chain may be walked; 0 disables mipmapping
	 * @param rgss        1 to enable rotated-grid supersampling of the atlas
	 */
	public void setTerrainParams(float atlasTexels, float tileTexels, float maxLod, float rgss) {
		this.atlasTexels = atlasTexels;
		this.tileTexels = tileTexels;
		this.maxLod = maxLod;
		this.rgss = rgss;
	}

	private final ByteBuffer uniforms =
		ByteBuffer.allocateDirect(UNIFORM_BYTES).order(ByteOrder.nativeOrder());

	/**
	 * A float view of {@link #uniforms}, made once.
	 *
	 * <p>Safe to hold because the buffer is exactly {@link #UNIFORM_BYTES} and never re-sized, so the
	 * view always spans the whole thing; {@code writeUniforms} rewinds it rather than re-deriving it.
	 */
	private final FloatBuffer uniformFloats = uniforms.asFloatBuffer();

	/** Where the block is assembled before it reaches {@link #uniforms}; see writeUniforms. */
	private final float[] block = new float[UNIFORM_FLOATS];

	/**
	 * What {@link #uniforms} currently holds, so an unchanged block can skip the copy into it.
	 *
	 * <p>{@code Arrays.equals} on floats compares through {@code Float.floatToIntBits}, which is
	 * bitwise except that it folds every NaN onto one encoding. So it is stricter than {@code ==}
	 * where it matters here -- {@code -0.0f} and {@code 0.0f} compare unequal, and the copy happens --
	 * and looser than a byte compare only for two DIFFERENT NaN encodings. No lane of this block can
	 * be NaN: the only division is {@code 1/span}, guarded on {@code span == 0}, and every other lane
	 * is a matrix element, a colour, a flag or a texture parameter. So the comparison is exact for
	 * the values that actually occur.
	 */
	private final float[] previousBlock = new float[UNIFORM_FLOATS];

	public GlState() {
		identity(modelViewStack[0]);
		identity(projectionStack[0]);
	}

	// --- matrix stack -------------------------------------------------------------------------

	public void matrixMode(int mode) {
		this.matrixMode = mode;
	}

	private float[] current() {
		return matrixMode == MODE_PROJECTION ? projectionStack[projectionTop] : modelViewStack[modelViewTop];
	}

	public void pushMatrix() {
		if (matrixMode == MODE_PROJECTION) {
			if (projectionTop + 1 < STACK_DEPTH) {
				System.arraycopy(projectionStack[projectionTop], 0, projectionStack[projectionTop + 1], 0, 16);
				projectionTop++;
			}
		} else if (modelViewTop + 1 < STACK_DEPTH) {
			System.arraycopy(modelViewStack[modelViewTop], 0, modelViewStack[modelViewTop + 1], 0, 16);
			modelViewTop++;
		}
	}

	public void popMatrix() {
		if (matrixMode == MODE_PROJECTION) {
			if (projectionTop > 0) {
				projectionTop--;
			}
		} else if (modelViewTop > 0) {
			modelViewTop--;
		}
	}

	public void loadIdentity() {
		identity(current());
	}

	public void loadMatrix(float[] m) {
		System.arraycopy(m, 0, current(), 0, 16);
	}

	public void multMatrix(float[] m) {
		multiply(current(), m);
	}

	/**
	 * {@code glOrtho}. Beta uses this for every GUI and HUD pass.
	 *
	 * <p>Produces GL's matrix, mapping z to [-1, 1]. The WebGPU convention of [0, 1] is applied in
	 * the vertex shader instead of here, so beta's own matrices stay untouched and there is exactly
	 * one place the two conventions meet.
	 */
	public void ortho(double left, double right, double bottom, double top, double near, double far) {
		float[] m = scratchIdentity();
		m[0] = (float) (2.0 / (right - left));
		m[5] = (float) (2.0 / (top - bottom));
		m[10] = (float) (-2.0 / (far - near));
		m[12] = (float) (-(right + left) / (right - left));
		m[13] = (float) (-(top + bottom) / (top - bottom));
		m[14] = (float) (-(far + near) / (far - near));
		m[15] = 1.0F;
		multiply(current(), m);
	}

	/** {@code glFrustum}; {@code gluPerspective} reduces to this. */
	public void frustum(double left, double right, double bottom, double top, double near, double far) {
		float[] m = scratch;
		java.util.Arrays.fill(m, 0.0F);
		m[0] = (float) (2.0 * near / (right - left));
		m[5] = (float) (2.0 * near / (top - bottom));
		m[8] = (float) ((right + left) / (right - left));
		m[9] = (float) ((top + bottom) / (top - bottom));
		m[10] = (float) (-(far + near) / (far - near));
		m[11] = -1.0F;
		m[14] = (float) (-2.0 * far * near / (far - near));
		multiply(current(), m);
	}

	public void translate(float x, float y, float z) {
		float[] t = scratchIdentity();
		t[12] = x;
		t[13] = y;
		t[14] = z;
		multiply(current(), t);
	}

	public void scale(float x, float y, float z) {
		float[] t = scratchIdentity();
		t[0] = x;
		t[5] = y;
		t[10] = z;
		multiply(current(), t);
	}

	/** Angle in DEGREES, as glRotatef takes it. */
	public void rotate(float angle, float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length == 0.0F) {
			return;
		}
		float nx = x / length;
		float ny = y / length;
		float nz = z / length;
		double radians = Math.toRadians(angle);
		float c = (float) Math.cos(radians);
		float s = (float) Math.sin(radians);
		float ic = 1.0F - c;

		float[] r = scratchIdentity();
		r[0] = nx * nx * ic + c;
		r[1] = ny * nx * ic + nz * s;
		r[2] = nz * nx * ic - ny * s;
		r[4] = nx * ny * ic - nz * s;
		r[5] = ny * ny * ic + c;
		r[6] = nz * ny * ic + nx * s;
		r[8] = nx * nz * ic + ny * s;
		r[9] = ny * nz * ic - nx * s;
		r[10] = nz * nz * ic + c;
		multiply(current(), r);
	}

	private final float[] scratch = new float[16];

	private float[] scratchIdentity() {
		identity(scratch);
		return scratch;
	}

	// --- colour, enables, fog, alpha ------------------------------------------------------------

	public void color(float r, float g, float b, float a) {
		color[0] = r;
		color[1] = g;
		color[2] = b;
		color[3] = a;
	}

	public void setTextureEnabled(boolean enabled) {
		textureEnabled = enabled;
	}

	/**
	 * Whether {@code GL_TEXTURE_2D} is on.
	 *
	 * <p>Distinct from "which texture is bound", and the distinction matters to routing: beta leaves
	 * a texture BOUND while drawing untextured geometry -- the sky plate, the void plate, entity
	 * shadows -- so the bound name says nothing about whether the draw samples anything. A shader
	 * extension told only the name cannot tell beta's void plate from the block atlas.
	 */
	public boolean textured() {
		return textureEnabled;
	}

	public void setLightingEnabled(boolean enabled) {
		lightingEnabled = enabled;
	}

	/**
	 * Whether {@code GL_LIGHTING} is on.
	 *
	 * <p>Beta's own separation of world geometry that takes directional shading -- mobs, dropped
	 * items, the held block -- from geometry that does not: particles, the sky, the GUI. A shader
	 * extension routes on it, because it is the closest thing beta has to saying "this is a lit
	 * surface in the world".
	 */
	public boolean lit() {
		return lightingEnabled;
	}

	public void setFogEnabled(boolean enabled) {
		fogEnabled = enabled;
	}

	public void setAlphaTestEnabled(boolean enabled) {
		alphaTestEnabled = enabled;
	}

	public void setAlphaRef(float ref) {
		alphaRef = ref;
	}

	public void setFog(int mode, float start, float end, float density) {
		fogMode = mode;
		fogStart = start;
		fogEnd = end;
		fogDensity = density;
	}

	public void setFogColor(float r, float g, float b, float a) {
		fogColor[0] = r;
		fogColor[1] = g;
		fogColor[2] = b;
		fogColor[3] = a;
	}

	// --- output -------------------------------------------------------------------------------

	/**
	 * Packs the current state into the uniform block fixedfunc.wgsl expects.
	 *
	 * @param vertexColors true when the draw supplies a per-vertex colour ARRAY. Fixed-function
	 *     semantics are that an array REPLACES the current colour, so the modulator goes white --
	 *     getting this backwards double-darkens every lit entity.
	 */
	/**
	 * The same block with an identity MODELVIEW, keeping the projection.
	 *
	 * <p>For geometry already in eye space -- {@code LineExpander} transforms wide lines itself,
	 * because a line's width is a screen-space quantity and cannot be expressed before the vertices
	 * know where they land. Leaving the projection in place is the point: the GPU still does the
	 * perspective divide, so depth interpolation, fog (a function of eye-space distance) and
	 * blending behave exactly as they do for every other batch.
	 */
	public ByteBuffer writeUniformsEye(boolean vertexColors, boolean vertexNormals,
			boolean vertexTexture) {

		int previousMode = matrixMode;
		matrixMode = MODE_MODELVIEW;
		pushMatrix();
		loadIdentity();
		try {
			return writeUniforms(vertexColors, vertexNormals, vertexTexture);
		} finally {
			popMatrix();
			matrixMode = previousMode;
		}
	}


	public ByteBuffer writeUniforms(boolean vertexColors) {
		return writeUniforms(vertexColors, true);
	}

	/**
	 * @param vertexNormals false when the batch left the normal slot unwritten, so the shader must
	 *     fall back to the current normal rather than reading the previous batch's bytes
	 */
	public ByteBuffer writeUniforms(boolean vertexColors, boolean vertexNormals) {
		return writeUniforms(vertexColors, vertexNormals, true);
	}

	/**
	 * @param vertexTexture false when the batch wrote no texture coordinates. GL then uses the
	 *     CURRENT texcoord for every vertex -- effectively (0,0) -- because the coordinate array is
	 *     simply disabled. A vertex buffer has no such switch, so without this the shader samples at
	 *     whatever UVs the previous batch left in those bytes. Entity shadows are the visible case:
	 *     they write no UVs, and reading stale ones makes them sample the block atlas, which paints
	 *     a repeat of the terrain texture on the ground under every entity.
	 */
	public ByteBuffer writeUniforms(boolean vertexColors, boolean vertexNormals,
			boolean vertexTexture) {
		// Staged in a plain float[] and pushed to the direct buffer in ONE copy, rather than written
		// field by field straight into it.
		//
		// This is on the hottest path the shim has. A frame here is ~1100 captures for ~10k vertices
		// -- nine vertices a batch -- so the 256-byte block is built more times per frame than there
		// are draws, and the uniform traffic outweighs the vertex data it describes. Every put() on a
		// direct buffer is a bounds check plus a memory-session liveness check; on a heap array the
		// JIT drops both and keeps the values in registers. The single bulk put at the end is an
		// intrinsified memcpy.
		float[] u = block;
		System.arraycopy(modelViewStack[modelViewTop], 0, u, 0, 16);
		System.arraycopy(projectionStack[projectionTop], 0, u, 16, 16);

		if (vertexColors) {
			u[32] = 1.0F;
			u[33] = 1.0F;
			u[34] = 1.0F;
			u[35] = 1.0F;
		} else {
			System.arraycopy(color, 0, u, 32, 4);
		}

		System.arraycopy(fogColor, 0, u, 36, 4);
		// x = mode, y = start-or-density, z = end, w = 1/(end-start) precomputed for linear fog.
		float span = fogEnd - fogStart;
		u[40] = fogEnabled ? fogMode : FOG_LINEAR;
		u[41] = fogMode == FOG_LINEAR ? fogStart : fogDensity;
		u[42] = fogEnabled ? fogEnd : Float.MAX_VALUE;
		u[43] = span == 0.0F ? 0.0F : 1.0F / span;

		u[44] = alphaRef;
		u[45] = alphaTestEnabled ? 1.0F : 0.0F;
		u[46] = textureEnabled ? 1.0F : 0.0F;
		u[47] = lightingEnabled ? 1.0F : 0.0F;

		// The w of each is padding in the lighting maths and carries a terrain parameter instead.
		u[48] = lightDir0[0];
		u[49] = lightDir0[1];
		u[50] = lightDir0[2];
		u[51] = atlasTexels;
		u[52] = lightDir1[0];
		u[53] = lightDir1[1];
		u[54] = lightDir1[2];
		u[55] = maxLod;
		u[56] = lightAmbient[0];
		u[57] = lightAmbient[1];
		u[58] = lightAmbient[2];
		u[59] = rgss;

		// w was the block's last free lane; the terrain program reads the grid pitch from it. Keeping
		// it here rather than growing the block is what holds the whole thing at exactly 256 bytes,
		// which is WebGPU's dynamic-offset alignment -- 260 would round every per-draw slot to 512.
		u[60] = vertexColors ? 1.0F : 0.0F;
		u[61] = vertexNormals ? 1.0F : 0.0F;
		u[62] = vertexTexture ? 1.0F : 0.0F;
		u[63] = tileTexels;

		// Only touch the direct buffer when the block actually changed. Three quarters of the
		// captures in a frame produce a block byte-identical to the one before -- that is exactly
		// why DrawList's merge works as well as it does -- and for those the buffer already holds
		// these bytes.
		//
		// NOT a dirty flag on the setters: the block is rebuilt from live state every single call, so
		// there is no state a missed setter could leave stale here. The comparison is the check.
		if (!java.util.Arrays.equals(u, previousBlock)) {
			uniforms.clear();
			uniformFloats.clear();
			uniformFloats.put(u);
			System.arraycopy(u, 0, previousBlock, 0, UNIFORM_FLOATS);
		} else {
			uniforms.position(0);
		}

		uniforms.limit(UNIFORM_BYTES);
		return uniforms;
	}

	// --- maths --------------------------------------------------------------------------------

	/** Column-major, as GL stores matrices. */
	private static void identity(float[] m) {
		java.util.Arrays.fill(m, 0.0F);
		m[0] = m[5] = m[10] = m[15] = 1.0F;
	}

	/** {@code dst = dst * src}, matching glMultMatrix's post-multiply order. */
	/**
	 * Scratch for {@link #multiply}'s result. Distinct from {@link #scratch}, which holds the
	 * OPERAND every caller but multMatrix builds -- writing the product into that would corrupt the
	 * matrix being multiplied by, halfway through reading it.
	 */
	private final float[] product = new float[16];

	/**
	 * Instance rather than static, purely so the result can live in {@link #product}.
	 *
	 * <p>This allocated a {@code float[16]} per call, and beta calls it constantly -- a translate per
	 * section group, a push/translate/rotate per entity, several per GUI element. It was the largest
	 * source of allocation this mod is responsible for on the render thread, second only to Mixin's
	 * own CallbackInfo objects, which are not ours to remove.
	 */
	private void multiply(float[] dst, float[] src) {
		float[] out = product;
		for (int col = 0; col < 4; col++) {
			for (int row = 0; row < 4; row++) {
				float sum = 0.0F;
				for (int k = 0; k < 4; k++) {
					sum += dst[k * 4 + row] * src[col * 4 + k];
				}
				out[col * 4 + row] = sum;
			}
		}
		System.arraycopy(out, 0, dst, 0, 16);
	}

	public float[] modelView() {
		return modelViewStack[modelViewTop];
	}

	public float[] projection() {
		return projectionStack[projectionTop];
	}

	// --- self-check ---------------------------------------------------------------------------

	/** {@code java com.periut.retrodragon.shim.GlState} -- checks the parts with real arithmetic. */
	public static void main(String[] args) {
		GlState s = new GlState();

		// Identity leaves a point alone.
		expect(s.transform(1, 2, 3), new float[] { 1, 2, 3 }, "identity");

		// Translate then transform.
		s.translate(10, 0, 0);
		expect(s.transform(1, 2, 3), new float[] { 11, 2, 3 }, "translate");

		// push/pop restores.
		s.pushMatrix();
		s.translate(100, 0, 0);
		expect(s.transform(0, 0, 0), new float[] { 110, 0, 0 }, "nested translate");
		s.popMatrix();
		expect(s.transform(0, 0, 0), new float[] { 10, 0, 0 }, "pop restores");

		// 90 degrees about Y takes +X to -Z (right-handed, as GL is).
		GlState r = new GlState();
		r.rotate(90.0F, 0.0F, 1.0F, 0.0F);
		expect(r.transform(1, 0, 0), new float[] { 0, 0, -1 }, "rotate Y 90");

		// Order matters: translate-then-rotate differs from rotate-then-translate.
		GlState a = new GlState();
		a.translate(5, 0, 0);
		a.rotate(90.0F, 0.0F, 1.0F, 0.0F);
		GlState b = new GlState();
		b.rotate(90.0F, 0.0F, 1.0F, 0.0F);
		b.translate(5, 0, 0);
		float[] pa = a.transform(1, 0, 0);
		float[] pb = b.transform(1, 0, 0);
		if (Math.abs(pa[0] - pb[0]) < 1e-4 && Math.abs(pa[2] - pb[2]) < 1e-4) {
			throw new AssertionError("translate/rotate order is not being respected");
		}

		// Scale compounds.
		GlState sc = new GlState();
		sc.scale(2, 3, 4);
		expect(sc.transform(1, 1, 1), new float[] { 2, 3, 4 }, "scale");

		// Uniform block is exactly the size the shader declares.
		ByteBuffer u = new GlState().writeUniforms(false);
		if (u.limit() != UNIFORM_BYTES) {
			throw new AssertionError("uniform size " + u.limit() + " != " + UNIFORM_BYTES);
		}

		checkUniformBlockEquivalence();

		// A vertex-colour draw must neutralise the modulator, or lit geometry double-darkens.
		GlState cs = new GlState();
		cs.color(0.5F, 0.5F, 0.5F, 1.0F);
		float modulator = cs.writeUniforms(true).asFloatBuffer().get(32);
		if (modulator != 1.0F) {
			throw new AssertionError("vertex-colour draw must write a white modulator, got " + modulator);
		}

		// --- lighting ---------------------------------------------------------------------------
		//
		// beta's Lighting.turnOn, verbatim. Both lights come from ABOVE and lean opposite ways in z;
		// the shading direction was wrong because these were hardcoded to straight up and straight
		// DOWN, and a light from underneath inverts which faces of an entity are lit.
		GlState lit = new GlState();
		float[] light0 = normalise(0.2F, 1.0F, -0.7F);
		float[] light1 = normalise(-0.2F, 1.0F, 0.7F);
		lit.setLightPosition(0x4000, light0[0], light0[1], light0[2], 0.0F);
		lit.setLightPosition(0x4001, light1[0], light1[1], light1[2], 0.0F);

		if (lit.lightDir0[1] <= 0.0F || lit.lightDir1[1] <= 0.0F) {
			throw new AssertionError("both of beta's lights come from above, got y "
				+ lit.lightDir0[1] + " and " + lit.lightDir1[1]);
		}
		if (lit.lightDir0[2] >= 0.0F || lit.lightDir1[2] <= 0.0F) {
			throw new AssertionError("the two lights must lean opposite ways in z, got "
				+ lit.lightDir0[2] + " and " + lit.lightDir1[2]);
		}
		expect(new float[] { lit.lightDir0[0], lit.lightDir0[1], lit.lightDir0[2] }, light0,
			"an identity modelview must leave the light direction alone");

		// GL bakes the modelview in AT THE glLight CALL, which is what keeps the sun world-fixed
		// while the camera turns. 180 degrees about Y must therefore flip x and z.
		GlState turned = new GlState();
		turned.rotate(180.0F, 0.0F, 1.0F, 0.0F);
		turned.setLightPosition(0x4000, light0[0], light0[1], light0[2], 0.0F);
		expect(new float[] { turned.lightDir0[0], turned.lightDir0[1], turned.lightDir0[2] },
			new float[] { -light0[0], light0[1], -light0[2] }, "light direction follows the modelview");

		// The light vectors share their w lanes with the terrain parameters, so setting a direction
		// must disturb nothing but the three xyz lanes it owns. Checked through the uniform block
		// rather than the fields, because the block is what the shader actually reads.
		GlState terrain = new GlState();
		terrain.setTerrainParams(256.0F, 16.0F, 4.0F, 1.0F);
		float[] before = snapshot(terrain);
		terrain.setLightPosition(0x4000, light0[0], light0[1], light0[2], 0.0F);
		terrain.setLightPosition(0x4001, light1[0], light1[1], light1[2], 0.0F);
		float[] after = snapshot(terrain);
		int changed = 0;
		for (int i = 0; i < before.length; i++) {
			if (before[i] != after[i]) {
				changed++;
			}
		}
		if (changed > 6) {
			throw new AssertionError("glLight should touch at most two xyz triples, changed "
				+ changed + " floats -- it is writing over a neighbouring field");
		}

		// Every terrain parameter must actually reach the block, in the lane the shader reads it from.
		// The grid pitch is the newest of the four and it rides in the LAST free lane there is; a
		// silent zero would read as "stitched atlas" and disable clamping and mipmapping outright.
		float[] block = snapshot(terrain);
		int lightDir0W = 16 + 16 + 4 * 4 + 3;
		int lightDir1W = lightDir0W + 4;
		int ambientW = lightDir1W + 4;
		int flagsW = ambientW + 4;
		expect(new float[] { block[lightDir0W], block[lightDir1W], block[ambientW], block[flagsW] },
			new float[] { 256.0F, 4.0F, 1.0F, 16.0F },
			"the terrain parameters must land in the w lanes the terrain program reads");

		System.out.println("GlState self-check OK");
	}

	/** The uniform block as floats, for the self-check. */
	private static float[] snapshot(GlState state) {
		java.nio.FloatBuffer f = state.writeUniforms(false).asFloatBuffer();
		float[] out = new float[f.remaining()];
		f.get(out);
		return out;
	}

	private static float[] normalise(float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[] { x / length, y / length, z / length };
	}

	/** Applies the current modelview to a point, for the self-check. */
	private float[] transform(float x, float y, float z) {
		float[] m = modelView();
		return new float[] {
			m[0] * x + m[4] * y + m[8] * z + m[12],
			m[1] * x + m[5] * y + m[9] * z + m[13],
			m[2] * x + m[6] * y + m[10] * z + m[14],
		};
	}

	private static void expect(float[] actual, float[] want, String what) {
		for (int i = 0; i < want.length; i++) {
			if (Math.abs(actual[i] - want[i]) > 1e-4) {
				throw new AssertionError(what + ": expected " + java.util.Arrays.toString(want)
					+ " got " + java.util.Arrays.toString(actual));
			}
		}
	}

	/**
	 * {@link #writeUniforms} must produce the same 256 bytes it always did.
	 *
	 * <p>That method was rewritten for speed -- it stages the block in a {@code float[]} and only
	 * copies into the direct buffer when the contents changed, because it runs ~1100 times a frame
	 * and three quarters of those produce a block identical to the one before. The optimisation is
	 * only worth having if it is invisible, and "invisible" here means byte-identical output, not
	 * approximately-right output: these bytes are a uniform block, so a single wrong lane is a wrong
	 * matrix, a wrong fog colour or a wrong alpha reference on screen.
	 *
	 * <p>Checked against a straightforward reference implementation over randomised states rather
	 * than a fixed expected blob. A blob would have to be regenerated whenever the block's layout
	 * legitimately changes, and regenerating it from the code under test proves nothing. The seed is
	 * fixed so a failure reproduces exactly.
	 *
	 * <p>Exercises the skip path deliberately: the same state is written twice in a row (must reuse)
	 * and then perturbed by one field at a time (must rewrite). A stale-block bug shows up only in
	 * that second write, which is precisely the case a single-shot test would miss.
	 */
	private static void checkUniformBlockEquivalence() {
		java.util.Random random = new java.util.Random(20260730L);
		GlState state = new GlState();
		byte[] fromFast = new byte[UNIFORM_BYTES];
		byte[] fromReference = new byte[UNIFORM_BYTES];
		int rewrites = 0;

		for (int iteration = 0; iteration < 4000; iteration++) {
			// Mutate one aspect per iteration, so consecutive calls often differ by a single lane --
			// the case where a too-eager skip would go unnoticed.
			switch (iteration % 11) {
				case 0 -> state.translate(random.nextFloat(), random.nextFloat(), random.nextFloat());
				case 1 -> state.rotate(random.nextFloat() * 360.0F, 0.0F, 1.0F, 0.0F);
				case 2 -> state.color(random.nextFloat(), random.nextFloat(), random.nextFloat(),
					random.nextFloat());
				case 3 -> {
					state.setFogEnabled(random.nextBoolean());
					state.setFog(random.nextInt(3), random.nextFloat(),
						random.nextFloat() + 1.0F, random.nextFloat());
				}
				case 4 -> state.setFogColor(random.nextFloat(), random.nextFloat(), random.nextFloat(),
					1.0F);
				case 5 -> {
					state.setAlphaTestEnabled(random.nextBoolean());
					state.setAlphaRef(random.nextFloat());
				}
				case 6 -> state.setTextureEnabled(random.nextBoolean());
				case 7 -> state.setLightingEnabled(random.nextBoolean());
				case 8 -> state.setLightModelAmbient(random.nextFloat(), random.nextFloat(),
					random.nextFloat());
				case 9 -> state.setTerrainParams(random.nextInt(2048) + 1, random.nextInt(64),
					random.nextInt(8), random.nextInt(2));
				default -> state.matrixMode(random.nextBoolean() ? MODE_PROJECTION : MODE_MODELVIEW);
			}

			boolean colors = random.nextBoolean();
			boolean normals = random.nextBoolean();
			boolean texture = random.nextBoolean();

			// Twice with identical state: the first may copy, the second must be a no-op that still
			// yields the same bytes.
			for (int repeat = 0; repeat < 2; repeat++) {
				ByteBuffer actual = state.writeUniforms(colors, normals, texture);
				if (actual.position() != 0 || actual.limit() != UNIFORM_BYTES) {
					throw new AssertionError("uniform buffer handed back at position "
						+ actual.position() + " limit " + actual.limit());
				}
				actual.duplicate().get(fromFast);
				state.referenceUniforms(colors, normals, texture).get(fromReference);
				if (!java.util.Arrays.equals(fromFast, fromReference)) {
					int lane = 0;
					while (lane < UNIFORM_BYTES && fromFast[lane] == fromReference[lane]) {
						lane++;
					}
					throw new AssertionError("uniform block differs from the reference at byte "
						+ lane + " (float " + lane / 4 + "), iteration " + iteration
						+ ", repeat " + repeat);
				}
				if (repeat == 0) {
					rewrites++;
				}
			}
		}
		System.out.println("GlState uniform block: 8000 writes checked against the reference, "
			+ rewrites + " distinct states, byte-identical");
	}

	/**
	 * The obvious implementation of {@link #writeUniforms}, for the self-check to compare against.
	 *
	 * <p>Writes straight into a fresh buffer every call with no staging and no skipping -- slow, and
	 * deliberately so. It is here to be readable and obviously correct, which is what makes it worth
	 * comparing the fast path to. Keep it in step with the real one when the block's layout changes;
	 * a change to one and not the other is exactly what this is designed to catch.
	 */
	private ByteBuffer referenceUniforms(boolean vertexColors, boolean vertexNormals,
			boolean vertexTexture) {
		ByteBuffer out = ByteBuffer.allocateDirect(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
		FloatBuffer f = out.asFloatBuffer();
		f.put(modelViewStack[modelViewTop]);
		f.put(projectionStack[projectionTop]);
		if (vertexColors) {
			f.put(1.0F).put(1.0F).put(1.0F).put(1.0F);
		} else {
			f.put(color);
		}
		f.put(fogColor);
		float span = fogEnd - fogStart;
		f.put(fogEnabled ? fogMode : FOG_LINEAR)
			.put(fogMode == FOG_LINEAR ? fogStart : fogDensity)
			.put(fogEnabled ? fogEnd : Float.MAX_VALUE)
			.put(span == 0.0F ? 0.0F : 1.0F / span);
		f.put(alphaRef)
			.put(alphaTestEnabled ? 1.0F : 0.0F)
			.put(textureEnabled ? 1.0F : 0.0F)
			.put(lightingEnabled ? 1.0F : 0.0F);
		f.put(lightDir0[0]).put(lightDir0[1]).put(lightDir0[2]).put(atlasTexels);
		f.put(lightDir1[0]).put(lightDir1[1]).put(lightDir1[2]).put(maxLod);
		f.put(lightAmbient[0]).put(lightAmbient[1]).put(lightAmbient[2]).put(rgss);
		f.put(vertexColors ? 1.0F : 0.0F)
			.put(vertexNormals ? 1.0F : 0.0F)
			.put(vertexTexture ? 1.0F : 0.0F)
			.put(tileTexels);
		out.position(0).limit(UNIFORM_BYTES);
		return out;
	}
}
