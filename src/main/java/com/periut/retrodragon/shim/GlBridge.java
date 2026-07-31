package com.periut.retrodragon.shim;

import com.periut.retrodragon.render.AnimatedMipmaps;
import com.periut.retrodragon.render.Primitives;
import com.periut.retrodragon.render.WebGpuFrame;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The implementations {@code GL11}'s entry points are rewritten to call under the WebGPU backend.
 *
 * <h2>Why a rewrite and not an interception</h2>
 *
 * LWJGL 3.4 declares 274 of {@code GL11}'s methods -- essentially the whole fixed-function surface,
 * including {@code glPushMatrix} and {@code glBegin} -- as {@code native}. A native method has no
 * bytecode, so Mixin cannot inject into it at all: the injector fails with a null instruction node
 * rather than a missing target. Observation-style hooks are simply not available here.
 *
 * <p>So under WebGPU the methods are replaced outright: {@code GlPlugin} strips {@code ACC_NATIVE}
 * and gives each one a body that forwards to the matching method below. Anything without a match
 * becomes an empty body, which is the correct behaviour for a process with no GL context -- a call
 * that reached the driver would not draw the wrong thing, it would crash.
 *
 * <p>The GL backend is untouched by all of this. The rewrite only happens when the renderer has
 * already taken the window, so a GL run loads the same {@code GL11} it always did.
 *
 * <p>Every method here has to match a {@code GL11} signature exactly, including the ones that look
 * redundant ({@code glColor3f} as well as {@code glColor4f}) -- the match is by name and descriptor.
 */
public final class GlBridge {
	private GlBridge() {
	}

	private static GlShim shim() {
		return ShimTracker.shim();
	}

	// --- matrix stack -----------------------------------------------------------------------------

	public static void glPushMatrix() {
		shim().glPushMatrix();
	}

	public static void glPopMatrix() {
		shim().glPopMatrix();
	}

	public static void glLoadIdentity() {
		shim().glLoadIdentity();
	}

	public static void glMatrixMode(int mode) {
		shim().glMatrixMode(mode);
	}

	public static void glTranslatef(float x, float y, float z) {
		shim().glTranslatef(x, y, z);
	}

	public static void glTranslated(double x, double y, double z) {
		shim().glTranslatef((float) x, (float) y, (float) z);
	}

	public static void glRotatef(float angle, float x, float y, float z) {
		shim().glRotatef(angle, x, y, z);
	}

	public static void glRotated(double angle, double x, double y, double z) {
		shim().glRotatef((float) angle, (float) x, (float) y, (float) z);
	}

	public static void glScalef(float x, float y, float z) {
		shim().glScalef(x, y, z);
	}

	public static void glScaled(double x, double y, double z) {
		shim().glScalef((float) x, (float) y, (float) z);
	}

	public static void glOrtho(double left, double right, double bottom, double top,
			double near, double far) {
		shim().glOrtho(left, right, bottom, top, near, far);
	}

	public static void glFrustum(double left, double right, double bottom, double top,
			double near, double far) {
		shim().glFrustum(left, right, bottom, top, near, far);
	}

	public static void glMultMatrixf(FloatBuffer m) {
		shim().glMultMatrix(m);
	}

	public static void glLoadMatrixf(FloatBuffer m) {
		shim().glLoadMatrix(m);
	}

	// --- colour and per-vertex state ---------------------------------------------------------------

	public static void glColor4f(float r, float g, float b, float a) {
		shim().glColor4f(r, g, b, a);
	}

	public static void glColor3f(float r, float g, float b) {
		shim().glColor3f(r, g, b);
	}

	public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
		shim().glColorMask(r, g, b, a);
	}

	// --- enables and pipeline state ----------------------------------------------------------------

	public static void glEnable(int cap) {
		shim().glEnable(cap);
	}

	public static void glDisable(int cap) {
		shim().glDisable(cap);
	}

	public static void glBlendFunc(int src, int dst) {
		shim().glBlendFunc(src, dst);
	}

	public static void glDepthMask(boolean write) {
		shim().glDepthMask(write);
	}

	public static void glDepthFunc(int func) {
		shim().glDepthFunc(func);
	}

	public static void glCullFace(int face) {
		shim().glCullFace(face);
	}

	public static void glAlphaFunc(int func, float ref) {
		shim().glAlphaFunc(func, ref);
	}

	public static void glViewport(int x, int y, int width, int height) {
		shim().glViewport(x, y, width, height);
	}

	// --- fog ---------------------------------------------------------------------------------------

	public static void glFogf(int name, float value) {
		shim().glFogf(name, value);
	}

	public static void glFogi(int name, int value) {
		shim().glFogi(name, value);
	}

	public static void glFogfv(int name, FloatBuffer params) {
		if (name == 0x0B66 && params != null && params.remaining() >= 4) { // GL_FOG_COLOR
			int base = params.position();
			shim().glFogColor(params.get(base), params.get(base + 1),
				params.get(base + 2), params.get(base + 3));
		}
	}

	// --- clears ------------------------------------------------------------------------------------

	public static void glClearColor(float r, float g, float b, float a) {
		shim().glClearColor(r, g, b, a);
	}

	public static void glClear(int mask) {
		GlShim gl = shim();
		WebGpuFrame.clear(mask, gl.clearRed(), gl.clearGreen(), gl.clearBlue(), gl.clearAlpha());
	}

	// --- textures ----------------------------------------------------------------------------------

	/**
	 * Screenshots. beta reads GL_RGB, tightly packed, from the framebuffer after the swap.
	 *
	 * <p>Nothing is written when there is no frame to read, so beta saves whatever it had rather
	 * than a buffer this method half-filled.
	 */
	public static void glReadPixels(int x, int y, int width, int height, int format, int type,
			java.nio.ByteBuffer pixels) {
		WebGpuFrame.readPixels(x, y, width, height, format, pixels);
	}

	/**
	 * LWJGL 2 spellings of entry points beta calls by that name.
	 *
	 * <p>Same order hazard as the lighting pair below. retrowindow synthesises these as aliases over
	 * LWJGL 3's {@code glGetFloatv}/{@code glFogfv}, and whether the alias exists yet when GlPlugin
	 * sweeps GL11 decides whether it gets forwarded or NEUTRALISED. Registering both names removes
	 * the dependency on that order.
	 *
	 * <p>Worth the belt and braces: {@code glGetFloat} is how {@code Frustum} reads the projection
	 * and modelview back to build the culling frustum, so a stubbed one does not look like a missing
	 * entry point -- it looks like geometry disappearing at the edges of the screen.
	 */
	public static void glGetFloat(int name, FloatBuffer params) {
		shim().glGetFloatv(name, params);
	}

	public static void glFog(int name, FloatBuffer params) {
		glFogfv(name, params);
	}

	// Lighting. BOTH spellings are needed: LWJGL 3 names these glLightfv/glLightModelfv, retrowindow
	// adds the LWJGL 2 names beta actually calls as aliases, and which of the two the rewrite sees
	// depends on whether that alias exists yet when GlPlugin runs. Covering both is order-proof.

	public static void glLightfv(int light, int parameter, FloatBuffer params) {
		shim().glLight(light, parameter, params);
	}

	public static void glLight(int light, int parameter, FloatBuffer params) {
		shim().glLight(light, parameter, params);
	}

	public static void glLightModelfv(int parameter, FloatBuffer params) {
		shim().glLightModel(parameter, params);
	}

	public static void glLightModel(int parameter, FloatBuffer params) {
		shim().glLightModel(parameter, params);
	}

	/** Consumed by {@code LineExpander}; WebGPU itself cannot draw a line wider than one pixel. */
	public static void glLineWidth(float width) {
		shim().glLineWidth(width);
	}

	/** See {@code GlShim.glPolygonOffset}: the block-breaking overlay's depth offset. */
	public static void glPolygonOffset(float factor, float units) {
		shim().glPolygonOffset(factor, units);
	}

	public static void glBindTexture(int target, int texture) {
		shim().glBindTexture(target, texture);
	}

	/**
	 * Sampler state, taken from where GL states it rather than guessed from the texture's path.
	 *
	 * <p>beta encodes two sampler hints in a texture path -- {@code %blur%} for linear filtering,
	 * {@code %clamp%} for clamp-to-edge -- but the path is only where it WRITES them.
	 * {@code TextureManager.load(BufferedImage,int)} is where it says what it means, and it is the
	 * one point every upload passes through: {@code getTextureId} AND {@code reload}, which
	 * re-uploads every texture whenever a texture pack changes or {@code GameOptions.setInt} runs.
	 * Reading the path at {@code getTextureId} caught only the first of those.
	 *
	 * <p>The entity shadow is what makes it visible. It is a quad per ground block whose UVs run
	 * outside 0..1 as they fall away from the entity, relying on clamping to fade the blob out; with
	 * repeat addressing those UVs wrap and the shadow tiles across every surrounding block.
	 *
	 * <p>MAG_FILTER, not MIN_FILTER, decides "linear". beta sets both together from {@code %blur%},
	 * but a mipmapped texture's MIN_FILTER also encodes mip behaviour -- terrain asks for
	 * {@code GL_NEAREST_MIPMAP_LINEAR}, which is linear BETWEEN levels and nearest within one, and
	 * reading that as "linear" would soften every block face.
	 *
	 * <p>Unset parameters keep this renderer's nearest/repeat default rather than GL's own
	 * (repeat, but MAG linear). Deliberate: GL's default would soften every texture uploaded without
	 * an explicit filter, which in a game drawn from 16x16 pixel art is the one thing that must not
	 * happen.
	 */
	public static void glTexParameteri(int target, int parameter, int value) {
		WebGpuFrame.textures().parameter(shim().boundTexture(), parameter, value);
	}

	/** Same state, float spelling; beta uses the int one, but LWJGL exposes both. */
	public static void glTexParameterf(int target, int parameter, float value) {
		glTexParameteri(target, parameter, (int) value);
	}

	public static void glGenTextures(IntBuffer names) {
		if (names == null) {
			return;
		}
		int base = names.position();
		for (int i = 0; i < names.remaining(); i++) {
			names.put(base + i, WebGpuFrame.textures().gen());
		}
	}

	public static int glGenTextures() {
		return WebGpuFrame.textures().gen();
	}

	public static void glDeleteTextures(IntBuffer names) {
		if (names == null) {
			return;
		}
		int base = names.position();
		for (int i = 0; i < names.remaining(); i++) {
			glDeleteTextures(names.get(base + i));
		}
	}

	public static void glDeleteTextures(int name) {
		WebGpuFrame.immediate().invalidate(name);
		WebGpuFrame.textures().delete(name);
	}

	/**
	 * Whatever the caller's pixels are, in whatever layout, as the RGBA8 the store takes.
	 *
	 * <p>{@code format}, {@code type} and the unpack state used to be dropped on the floor, on the
	 * true-but-narrow reasoning that beta only ever uploads tightly packed RGBA bytes. StationAPI
	 * uploads neither: see {@link PixelStore}.
	 */
	public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
			int border, int format, int type, ByteBuffer pixels) {
		texImage(target, level, width, height, PixelStore.rgba(format, type, width, height, pixels));
	}

	/**
	 * The {@code IntBuffer} spelling, and the one the reported atlas bug was.
	 *
	 * <p>{@code GlPlugin} matches by name AND descriptor, so this overload's absence did not fall back
	 * to the {@code ByteBuffer} one -- it became an empty method body. StationAPI allocates every
	 * texture it owns through {@code TextureUtil.prepareImage}, which calls exactly this, so its
	 * stitched block atlas was never created at all: every later {@code glTexSubImage2D} of a sprite
	 * found no texture under that name, and the store's 1x1 white stand-in was drawn instead. Hence
	 * white blocks, with grass and leaves green from the biome tint the vertex colour still carried.
	 */
	public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
			int border, int format, int type, IntBuffer pixels) {
		texImage(target, level, width, height, PixelStore.rgba(format, type, width, height, pixels));
	}

	private static void texImage(int target, int level, int width, int height, ByteBuffer rgba) {
		if (target == GL_PROXY_TEXTURE_2D) {
			// Not an upload: a capability probe. StationAPI sizes its stitched atlas by asking for a
			// 32768-square proxy and halving until one is accepted, reading the answer back with
			// glGetTexLevelParameteri -- so answer from the device's real limit and allocate nothing.
			int max = maxTextureSize();
			boolean fits = width <= max && height <= max;
			proxyWidth = fits ? width : 0;
			proxyHeight = fits ? height : 0;
			return;
		}
		if (target != GL_TEXTURE_2D) {
			// 1D, 3D and the cube map faces. Nothing in beta or either content API uses them, and
			// treating one as a 2D upload would put the wrong image under the bound name.
			return;
		}
		int name = shim().boundTexture();
		if (level == 0) {
			WebGpuFrame.textures().define(name, width, height, rgba);
			// A replaced texture leaves the cached bind group holding a view of the old one.
			WebGpuFrame.immediate().invalidate(name);
		} else {
			WebGpuFrame.textures().defineLevel(name, level, width, height, rgba);
		}
	}

	/**
	 * The animated-texture seam. Every animation source reaches the atlas through here.
	 *
	 * <p>{@code level} used to be DROPPED, so a caller uploading a mip level wrote it over level 0 and
	 * corrupted the sheet. Nothing hit that before -- beta's own per-level loop is behind a flag that
	 * is false in its static initialiser and that the game never writes -- but it is what made the
	 * animated tiles unfixable rather than merely stale.
	 */
	public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height,
			int format, int type, ByteBuffer pixels) {
		texSubImage(target, level, x, y, width, height,
			PixelStore.rgba(format, type, width, height, pixels));
	}

	/**
	 * The {@code IntBuffer} spelling. beta's own LWJGL3 compat {@code TextureUtil} uploads through
	 * this one, in {@code GL_BGRA} rows of ARGB ints.
	 */
	public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height,
			int format, int type, IntBuffer pixels) {
		texSubImage(target, level, x, y, width, height,
			PixelStore.rgba(format, type, width, height, pixels));
	}

	private static void texSubImage(int target, int level, int x, int y, int width, int height,
			ByteBuffer rgba) {
		if (target != GL_TEXTURE_2D || rgba == null) {
			return;
		}
		int name = shim().boundTexture();
		WebGpuFrame.textures().update(name, level, x, y, width, height, rgba);
		if (level == 0) {
			// Level 0 landed; regenerate the levels underneath it for exactly this region. Doing it
			// here rather than at any particular animator covers beta's binders, RetroAPI's mcmeta
			// animations and any mod, because all of them arrive as this one call.
			AnimatedMipmaps.regenerate(name, x, y, width, height, rgba);
		}
	}

	// --- texture queries ----------------------------------------------------------------------------
	//
	// Both of these used to return 0, which is not a neutral answer: it is "this device cannot do
	// that". StationAPI reads its atlas budget from them, and a zero there caps the stitched sheet at
	// 1024 texels -- enough to lose sprites outright once a few content mods are installed.

	private static final int GL_TEXTURE_2D = 0x0DE1;
	private static final int GL_PROXY_TEXTURE_2D = 0x8064;
	private static final int GL_TEXTURE_WIDTH = 0x1000;
	private static final int GL_TEXTURE_HEIGHT = 0x1001;
	private static final int GL_MAX_TEXTURE_SIZE = 0x0D33;

	/** The last GL_PROXY_TEXTURE_2D request, or 0 when the device refused it. */
	private static volatile int proxyWidth;
	private static volatile int proxyHeight;
	private static volatile int maxTextureSize;

	public static int glGetInteger(int name) {
		// Only the one the shim can answer truthfully. Everything else keeps GL's "unknown" rather
		// than inventing a number the renderer would then be held to.
		return name == GL_MAX_TEXTURE_SIZE ? maxTextureSize() : 0;
	}

	public static int glGetTexLevelParameteri(int target, int level, int parameter) {
		if (target == GL_PROXY_TEXTURE_2D) {
			return level != 0 ? 0
				: parameter == GL_TEXTURE_WIDTH ? proxyWidth
				: parameter == GL_TEXTURE_HEIGHT ? proxyHeight : 0;
		}
		if (target != GL_TEXTURE_2D) {
			return 0;
		}
		com.periut.retrodragon.render.TextureStore textures = WebGpuFrame.textures();
		int name = shim().boundTexture();
		if (!textures.has(name)) {
			return 0;
		}
		com.periut.retrodragon.gpu.GpuTexture texture = textures.get(name);
		return switch (parameter) {
			case GL_TEXTURE_WIDTH -> Math.max(1, texture.width() >> level);
			case GL_TEXTURE_HEIGHT -> Math.max(1, texture.height() >> level);
			default -> 0;
		};
	}

	/** The device's real 2D limit, asked for once. */
	private static int maxTextureSize() {
		if (maxTextureSize == 0) {
			// The probe can arrive before anything has drawn; the device has to exist to be asked.
			WebGpuFrame.active();
			maxTextureSize = com.periut.retrodragon.gpu.GpuTexture.maxDimension(
				com.periut.retrodragon.render.GpuBackend.context());
		}
		return maxTextureSize;
	}

	/**
	 * Pixel-store state, which decides how the next upload's buffer is read.
	 *
	 * <p>Was excused as a no-op on the grounds that beta never sets it. StationAPI sets it around
	 * every single upload -- it is how {@code NativeImage} addresses one animation frame inside a
	 * sprite sheet -- so ignoring it uploaded the wrong rectangle. See {@link PixelStore}.
	 */
	public static void glPixelStorei(int parameter, int value) {
		PixelStore.store(parameter, value);
	}

	// --- immediate mode ----------------------------------------------------------------------------
	//
	// beta itself never uses these -- its Tessellator submits through vertex arrays, which is why the
	// capture lives at Tessellator.draw -- but mods do, and under WebGPU there is no driver to fall
	// back on. Routing them through GeometryCapture means an unported mod still draws.

	public static void glBegin(int mode) {
		GlShim gl = shim();
		gl.setTopology(Primitives.topology(mode));
		ShimTracker.geometry().begin(mode, gl.pipelineKey());
	}

	public static void glEnd() {
		GeometryCapture geometry = ShimTracker.geometry();
		int vertices = geometry.end();
		if (vertices > 0) {
			WebGpuFrame.capture(geometry.data(), vertices, geometry.topology(), true, true, true);
		}
		// One batch per begin/end pair, so the buffer is recycled immediately rather than
		// accumulating across a frame the way the Tessellator's does.
		geometry.reset();
	}

	public static void glVertex3f(float x, float y, float z) {
		ShimTracker.geometry().vertex(x, y, z);
	}

	public static void glVertex3d(double x, double y, double z) {
		ShimTracker.geometry().vertex((float) x, (float) y, (float) z);
	}

	public static void glVertex2f(float x, float y) {
		ShimTracker.geometry().vertex(x, y, 0.0F);
	}

	public static void glVertex2d(double x, double y) {
		ShimTracker.geometry().vertex((float) x, (float) y, 0.0F);
	}

	public static void glTexCoord2f(float s, float t) {
		ShimTracker.geometry().texCoord(s, t);
	}

	public static void glTexCoord2d(double s, double t) {
		ShimTracker.geometry().texCoord((float) s, (float) t);
	}

	public static void glNormal3f(float x, float y, float z) {
		ShimTracker.geometry().normal(x, y, z);
	}

	// --- readback ----------------------------------------------------------------------------------

	public static int glGetError() {
		// GL_NO_ERROR. Dawn reports its own problems through the device's uncaptured-error callback,
		// which says what was wrong rather than only that something was.
		return 0;
	}

	public static String glGetString(int name) {
		return switch (name) {
			case 0x1F00 -> "RetroDragon";                                    // GL_VENDOR
			case 0x1F01 -> "WebGPU (" + com.periut.retrodragon.render.GpuBackend.status() + ")";
			case 0x1F02 -> "4.6 (RetroDragon WebGPU/Dawn)";                  // GL_VERSION
			case 0x1F03 -> "";                                             // GL_EXTENSIONS
			default -> "";
		};
	}

	public static void glGetFloatv(int name, FloatBuffer params) {
		shim().glGetFloatv(name, params);
	}

	// --- display lists -----------------------------------------------------------------------------

	public static int glGenLists(int count) {
		return DisplayLists.gen(count);
	}

	public static void glNewList(int list, int mode) {
		DisplayLists.begin(list);
	}

	public static void glEndList() {
		DisplayLists.end();
	}

	public static void glCallList(int list) {
		// State comes from the shim as it is NOW, not as it was when the list was compiled: GL
		// applies the modelview in effect at the call, and beta depends on that -- the same chunk
		// list is replayed from a different camera position every frame.
		DisplayLists.call(list, (vertices, count, mode, hasColor, hasNormals, hasTexture) ->
			WebGpuFrame.capture(vertices, count, mode, hasColor, hasNormals, hasTexture));
	}

	public static void glCallLists(IntBuffer lists) {
		if (lists == null) {
			return;
		}
		// Each id in the buffer is called in order, exactly as GL does. Beta uses this for text --
		// which the batched TextRenderer replaces -- and for chunk display lists, which still arrive
		// here when RetroDragon's own terrain path is disabled.
		int base = lists.position();
		for (int i = 0; i < lists.remaining(); i++) {
			glCallList(lists.get(base + i));
		}
	}

	public static void glDeleteLists(int list, int count) {
		DisplayLists.delete(list, count);
	}
}
