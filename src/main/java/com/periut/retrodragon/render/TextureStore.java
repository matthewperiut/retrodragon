package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.GpuTexture;
import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.Config;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps beta's GL texture NAMES onto WebGPU textures.
 *
 * <p>Beta identifies every texture by the integer {@code glGenTextures} handed it, keeps those in
 * its own maps, and binds them by name for the rest of the run. Under WebGPU there is no GL to hand
 * out names, so this hands out its own -- the game cannot tell the difference, and every
 * {@code glBindTexture(name)} it makes still resolves to the right image.
 *
 * <p>An unknown name resolves to a 1x1 white texture rather than failing. That is not defensive
 * padding: the shader always samples, even when {@code GL_TEXTURE_2D} is disabled, and white is the
 * identity for the multiply. It also means a texture beta binds before uploading -- which it does,
 * every time, since {@code glBindTexture} precedes {@code glTexImage2D} -- renders as untextured
 * colour for that one draw instead of crashing the frame.
 */
public final class TextureStore implements AutoCloseable {
	private final WebGPUContext ctx;
	private final Map<Integer, GpuTexture> textures = new HashMap<>();
	/** Sampler state beta encoded in the texture PATH; see {@code GpuTexture.sampler}. */
	private final java.util.Set<Integer> linear = new java.util.HashSet<>();
	private final java.util.Set<Integer> clamp = new java.util.HashSet<>();
	private GpuTexture white;

	/**
	 * Names start high enough to be obviously ours in a log, and are never reused -- beta leaks a
	 * handful of texture names per resource reload, and reuse would alias two different images.
	 */
	private int nextName = 0x1000;

	public TextureStore(WebGPUContext ctx) {
		this.ctx = ctx;
	}

	/** Stands in for {@code glGenTextures}. */
	public synchronized int gen() {
		return nextName++;
	}

	/**
	 * Stands in for {@code glTexImage2D} at level 0: allocates (or reallocates) and uploads.
	 *
	 * @param rgba tightly packed R,G,B,A bytes from the buffer's current position, or null to
	 *             allocate without uploading
	 */
	public synchronized void define(int name, int width, int height, ByteBuffer rgba) {
		int levels = mipLevelsFor(name, width, height);
		GpuTexture existing = textures.get(name);
		if (existing != null
				&& (existing.width() != width || existing.height() != height
					|| existing.mipLevels() != levels)) {
			existing.close();
			existing = null;
			textures.remove(name);
		}
		if (existing == null) {
			existing = GpuTexture.create(ctx, width, height, levels, "gl-texture-" + name);
			textures.put(name, existing);
		}
		// No sampler state is inferred here. beta sets it with glTexParameteri around this upload,
		// which reaches parameter() above; deriving it from the path instead missed every upload
		// that did not come through TextureManager.getTextureId.
		if (rgba == null) {
			return;
		}
		if (levels > 1) {
			// Terrain and item atlases get the corrected chain rather than a plain box filter; see
			// Mipmapper for why beta's own transparent texels make the naive version wrong.
			uploadChain(existing, width, height, rgba);
		} else {
			existing.upload(0, 0, 0, width, height, rgba);
		}
	}

	/**
	 * Stands in for {@code glTexSubImage2D}; used by the animated water/lava/fire tiles.
	 *
	 * <p>{@code level} is honoured rather than assumed to be 0. It used to be dropped, on the
	 * reasoning that rebuilding a whole 256x256 chain at 20 Hz cost more than "tiles whose mips
	 * barely move" were worth. Both halves of that were wrong: nothing has to rebuild the whole
	 * sheet -- a tile's own chain is 8x8, 4x4, 2x2, 1x1 and the box filter never crosses a tile
	 * boundary anyway -- and lava's mips do not barely move, they swing the full width of the
	 * animation. Frozen at the load-time frame, distant lava stopped animating and sat at the wrong
	 * colour. See {@link AnimatedMipmaps}.
	 */
	public synchronized void update(int name, int level, int x, int y, int width, int height,
			ByteBuffer rgba) {
		GpuTexture texture = textures.get(name);
		if (texture == null || rgba == null || level < 0 || level >= texture.mipLevels()) {
			return;
		}
		texture.upload(level, x, y, width, height, rgba);
	}

	/**
	 * Uploads a level explicitly, for callers that already hold a chain.
	 *
	 * <p>Bounded by what was allocated. A content API declares its chain by uploading every level
	 * whether or not this store built one for that texture -- StationAPI allocates five levels for
	 * every atlas -- and a write past {@code mipLevelCount} is a Dawn validation error per call, not
	 * a dropped write.
	 */
	public synchronized void defineLevel(int name, int level, int width, int height, ByteBuffer rgba) {
		GpuTexture texture = textures.get(name);
		if (texture != null && rgba != null && level >= 0 && level < texture.mipLevels()) {
			texture.upload(level, 0, 0, width, height, rgba);
		}
	}

	private void uploadChain(GpuTexture texture, int width, int height, ByteBuffer rgba) {
		int[] argb = new int[width * height];
		int base = rgba.position();
		for (int i = 0; i < argb.length; i++) {
			int r = rgba.get(base + i * 4) & 0xFF;
			int g = rgba.get(base + i * 4 + 1) & 0xFF;
			int b = rgba.get(base + i * 4 + 2) & 0xFF;
			int a = rgba.get(base + i * 4 + 3) & 0xFF;
			argb[i] = a << 24 | r << 16 | g << 8 | b;
		}
		int[][] chain = Mipmapper.build(argb, width, height, BlockAtlas.tileTexels(),
			texture.mipLevels() - 1);
		ByteBuffer scratch = null;
		int size = width;
		for (int level = 0; level < chain.length; level++) {
			scratch = Mipmapper.toRgbaBytes(chain[level], scratch);
			texture.upload(level, 0, 0, size, size, scratch);
			size /= 2;
		}
	}

	/**
	 * True only while the block atlas is being uploaded.
	 *
	 * <p>The "only" is load-bearing, and the GL path got it wrong once already: an upload call has no
	 * idea which texture it is carrying, so gating on "square and a multiple of 16" also catches the
	 * 128x128 font sheet and every 256x256 GUI sheet. Mipmapping those gives them a minifying filter,
	 * which softens text, the crosshair, the buttons and the logo -- precisely the artefacts a
	 * pixel-art game must not have. {@code MipmapCompletenessMixin} identifies terrain by PATH and
	 * sets this; nothing else is ever mipmapped.
	 *
	 * <p>{@link BlockAtlas#isBlockAtlas(int)} is the second, stronger signal: it holds for the atlas's
	 * texture NAME and so still recognises the re-uploads a content API makes outside the terrain.png
	 * load -- RetroAPI composites its expanded sheet from {@code TextureManager.reload}, which this
	 * flag is not set for.
	 */
	private static volatile boolean loadingTerrain;

	public static void markTerrainLoad(boolean terrain) {
		loadingTerrain = terrain;
	}

	// GL sampler enums. Spelled out rather than imported so this file does not depend on which GL
	// binding is on the classpath -- under WebGPU the real GL11 has been rewritten out from under us.
	private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
	private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
	private static final int GL_TEXTURE_WRAP_S = 0x2802;
	private static final int GL_TEXTURE_WRAP_T = 0x2803;
	private static final int GL_LINEAR = 0x2601;
	private static final int GL_CLAMP = 0x2900;
	private static final int GL_CLAMP_TO_EDGE = 0x812F;

	/**
	 * Records one {@code glTexParameteri} against a texture name. See
	 * {@code GlBridge.glTexParameteri} for why this, and not the texture's path, is the source of
	 * truth for sampler state.
	 *
	 * <p>Both directions are honoured, which the path hints could not do: a texture re-uploaded
	 * without {@code %clamp%} genuinely stops clamping, instead of keeping the flag it was first
	 * given for the rest of the run.
	 *
	 * <p>Unknown parameters are ignored rather than rejected. Beta and its mods set
	 * {@code GL_TEXTURE_BASE_LEVEL}, {@code GL_TEXTURE_MAX_LEVEL} and others whose effect is already
	 * carried by the mip chain this store builds; silently dropping them is correct here, and a
	 * throw would take out the frame.
	 */
	public synchronized void parameter(int name, int parameter, int value) {
		switch (parameter) {
			// MAG only -- see GlBridge.glTexParameteri: a mipmapped MIN_FILTER also encodes mip
			// behaviour, and reading GL_NEAREST_MIPMAP_LINEAR as "linear" would soften every block.
			case GL_TEXTURE_MAG_FILTER -> set(linear, name, value == GL_LINEAR);
			case GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T ->
				set(clamp, name, value == GL_CLAMP || value == GL_CLAMP_TO_EDGE);
			case GL_TEXTURE_MIN_FILTER -> {
				// Deliberately no effect on the linear flag; listed so it is clear it was considered.
			}
			default -> {
			}
		}
	}

	private static void set(java.util.Set<Integer> flags, int name, boolean on) {
		if (on) {
			flags.add(name);
		} else {
			flags.remove(name);
		}
	}

	public synchronized boolean isLinear(int name) {
		return linear.contains(name);
	}

	public synchronized boolean isClamped(int name) {
		return clamp.contains(name);
	}

	private static int mipLevelsFor(int name, int width, int height) {
		if (!Config.MIPMAP || !(loadingTerrain || BlockAtlas.isBlockAtlas(name))) {
			return 1;
		}
		BlockAtlas.observe(width, height);
		return BlockAtlas.canMipmap(width, height) ? BlockAtlas.mipLevels() + 1 : 1;
	}

	public synchronized void delete(int name) {
		linear.remove(name);
		clamp.remove(name);
		GpuTexture texture = textures.remove(name);
		if (texture != null) {
			texture.close();
		}
	}

	/** Never null: an unbound or unknown name resolves to 1x1 white. */
	public synchronized GpuTexture get(int name) {
		GpuTexture texture = textures.get(name);
		if (texture != null) {
			return texture;
		}
		if (white == null) {
			white = GpuTexture.white(ctx);
		}
		return white;
	}

	public synchronized boolean has(int name) {
		return textures.containsKey(name);
	}

	public synchronized int count() {
		return textures.size();
	}

	@Override
	public synchronized void close() {
		for (GpuTexture texture : textures.values()) {
			texture.close();
		}
		textures.clear();
		if (white != null) {
			white.close();
			white = null;
		}
	}
}
