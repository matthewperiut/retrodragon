package com.periut.retrodragon.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.periut.retrodragon.RetroDragon;

/**
 * What the block atlas actually IS this run -- its size in texels, and whether it is still a uniform
 * grid of tiles.
 *
 * <h2>Why this exists</h2>
 *
 * Every part of RetroDragon's terrain path used to hardcode beta's numbers: a 256x256 sheet cut into
 * a 16x16 grid of 16-texel tiles. Both halves of that are wrong as soon as a content API is
 * installed, and b1.7.3 has two:
 *
 * <ul>
 *   <li><b>RetroAPI</b> composites modded block textures into a LARGER sheet (power of two, still
 *       square, still 16 slots per row) and rewrites vanilla's {@code / 256.0} UV maths to match. The
 *       grid survives; its pitch stays the sprite size, so the sheet gains ROWS, not columns --
 *       a 512x512 atlas is 32 tiles per axis, NOT 16. Assuming 16 there put the per-tile clamp on
 *       the wrong boundaries, which is the one thing worse than not clamping at all.</li>
 *   <li><b>StationAPI</b> replaces terrain.png with a STITCHED atlas: arbitrary size, sprites packed
 *       at arbitrary positions in arbitrary sizes, no grid whatsoever. Nothing derived from a tile
 *       index means anything there.</li>
 * </ul>
 *
 * <h2>How it is detected</h2>
 *
 * In order, first hit wins:
 *
 * <ol>
 *   <li>{@code -Dretrodragon.atlasTexels} / {@code -Dretrodragon.atlasTile}, for a setup this file
 *       has not been taught about. {@code -Dretrodragon.atlasTile=-1} says "stitched, no grid".</li>
 *   <li>StationAPI, if loaded -- checked BEFORE RetroAPI on purpose. When both are present the
 *       stitched atlas is the one the GPU sees, and "no grid" is also the conservative answer.</li>
 *   <li>RetroAPI, if loaded: its own {@code AtlasExpander} publishes the composited size and sprite
 *       size, which is exactly the (texels, pitch) pair needed here.</li>
 *   <li>Otherwise the dimensions of the level-0 upload actually observed, cut into beta's 16x16
 *       grid -- which covers vanilla and every HD texture pack without either mod.</li>
 * </ol>
 *
 * <p>Both content APIs are read REFLECTIVELY and neither is a dependency of this mod: RetroDragon
 * compiles and runs with neither, either, or both installed.
 *
 * <h2>What a non-grid atlas turns off</h2>
 *
 * The per-tile clamp, the mip chain and RGSS all rest on "a 2x2 box never crosses a tile boundary",
 * which a stitched atlas breaks. On {@link #uniformGrid()} being false the terrain program falls
 * back to a single nearest tap at level 0 -- vanilla's look, and the only sampling that is CORRECT
 * without knowing where each sprite ends.
 */
public final class BlockAtlas {
	/** Beta's grid: 16 sprites per axis at every resolution, so the pitch is {@code texels / 16}. */
	public static final int VANILLA_TILES = 16;

	/** {@code -Dretrodragon.atlasTexels=<n>}: forces the atlas edge length. 0 = detect. */
	private static final int FORCED_TEXELS = Integer.getInteger("retrodragon.atlasTexels", 0);
	/**
	 * {@code -Dretrodragon.atlasTile=<n>}: forces the grid pitch in texels. 0 = detect,
	 * negative = "this atlas has no grid", which disables clamping, mipmapping and RGSS.
	 */
	private static final int FORCED_TILE = Integer.getInteger("retrodragon.atlasTile", 0);

	private static volatile int texels = 256;
	private static volatile int tileTexels = 16;
	private static volatile boolean uniformGrid = true;
	private static volatile SpriteGrid spriteGrid;

	/**
	 * Where each sprite sits on a stitched sheet, in the one form the terrain path needs: its SIZE.
	 *
	 * <p>That is enough because {@code TextureStitcher} -- modern Minecraft's, which StationAPI uses --
	 * rounds every slot to {@code smallestEncompassingPowerOfTwo} and subdivides recursively, so a slot
	 * of size S always sits at a multiple of S. A sprite's origin is therefore
	 * {@code floor(uv / size) * size}: exactly the formula both terrain shaders already use for a tile
	 * grid, with a per-quad pitch instead of a global one. One byte per vertex carries it, rather than
	 * the four-component rect that would be needed if the packing were arbitrary.
	 *
	 * <p>Immutable and published through a volatile field, because it is built on the render thread
	 * during a resource reload and read by every mesh worker.
	 */
	public static final class SpriteGrid {
		/** Cell pitch in texels: the smallest sprite, so no cell can straddle two sprites. */
		private final int cellTexels;
		private final int cells;
		private final int atlasTexels;
		/** {@code log2(sprite size)} per cell, 0 where nothing was mapped. */
		private final byte[] sizeLog2;

		SpriteGrid(int cellTexels, int cells, int atlasTexels) {
			this.cellTexels = cellTexels;
			this.cells = cells;
			this.atlasTexels = atlasTexels;
			this.sizeLog2 = new byte[cells * cells];
		}

		void put(int x, int y, int size) {
			int log2 = Integer.numberOfTrailingZeros(size);
			for (int cy = y / cellTexels; cy < (y + size) / cellTexels && cy < cells; cy++) {
				for (int cx = x / cellTexels; cx < (x + size) / cellTexels && cx < cells; cx++) {
					this.sizeLog2[cx + cy * cells] = (byte) log2;
				}
			}
		}

		/** The owning sprite's edge length in texels for a texture coordinate, or 0 if unmapped. */
		public int sizeAt(float u, float v) {
			return sizeAtTexel((int) (u * this.atlasTexels), (int) (v * this.atlasTexels));
		}

		/** The same, addressed in texels -- what a sub-image upload knows. */
		public int sizeAtTexel(int x, int y) {
			int cx = x / this.cellTexels;
			int cy = y / this.cellTexels;
			if (cx < 0 || cy < 0 || cx >= this.cells || cy >= this.cells) {
				return 0;
			}
			int log2 = this.sizeLog2[cx + cy * this.cells];
			return log2 == 0 ? 0 : 1 << log2;
		}

		/** Smallest sprite on the sheet, which is what bounds the mip chain. */
		public int smallest() {
			return this.cellTexels;
		}
	}

	/**
	 * The sprite table for a stitched sheet, or null when the atlas is a plain grid (vanilla,
	 * RetroAPI, any HD pack) and the uniform pitch already describes it.
	 */
	public static SpriteGrid spriteGrid() {
		return uniformGrid ? null : spriteGrid;
	}

	/**
	 * Whether anything in this install can replace the atlas with a stitched sheet.
	 *
	 * <p>Answered from the MOD LIST, not from the atlas, because {@link TerrainVertex} has to fix the
	 * vertex layout before the first resource reload has stitched anything. StationAPI can; RetroAPI
	 * composites at a fixed sprite size and stays a grid, so it never needs the extra byte.
	 */
	public static boolean mayStitch() {
		return StationApiAtlas.PRESENT;
	}

	/**
	 * The GL name beta handed out for the block atlas, or -1 before it has been loaded.
	 *
	 * <p>Identifying the atlas by NAME rather than by the path of the load in progress is what makes
	 * this survive RetroAPI: it re-uploads the composited sheet from {@code TextureManager.reload}
	 * and from the tail of {@code getTextureId}, neither of which is inside the terrain.png load our
	 * path hook brackets. Every one of those uploads still carries this name.
	 */
	private static volatile int textureName = -1;

	private static volatile int observedWidth;
	private static volatile int observedHeight;

	/** Throttles the StationAPI probe: it walks three reflective calls, unlike RetroAPI's two fields. */
	private static int probeCountdown;
	private static String reported = "";

	private BlockAtlas() {
	}

	/** Beta's block atlas texture, from {@code getTextureId("/terrain.png")}. */
	public static void markTexture(int name) {
		if (textureName != name) {
			textureName = name;
			refresh();
		}
	}

	/** True for the one texture name the block atlas lives in. */
	public static boolean isBlockAtlas(int name) {
		return name >= 0 && name == textureName;
	}

	/** A level-0 upload of the block atlas, whoever produced the image. */
	public static void observe(int width, int height) {
		if (width > 0 && height > 0 && (width != observedWidth || height != observedHeight)) {
			observedWidth = width;
			observedHeight = height;
		}
		refresh();
	}

	/** The atlas edge length in texels. */
	public static int texels() {
		return texels;
	}

	/** The grid pitch in texels -- one block face is one tile across. Meaningless without a grid. */
	public static int tileTexels() {
		return tileTexels;
	}

	/** Tiles per axis. 16 for vanilla, more once RetroAPI has added rows. */
	public static float tiles() {
		return tileTexels > 0 ? texels / (float) tileTexels : 0.0F;
	}

	/** False for a stitched atlas, where no tile-grid reasoning holds. */
	public static boolean uniformGrid() {
		return uniformGrid;
	}

	/**
	 * Whether a tap can be bounded to the texture it belongs to -- the one precondition mipmapping
	 * and RGSS both rest on.
	 *
	 * <p>True two ways: a uniform grid, where one pitch describes the whole sheet, or a stitched
	 * sheet whose sprites were placeable, where each vertex carries its own. Only an atlas that is
	 * neither falls back to a single nearest tap at level 0.
	 */
	public static boolean clamped() {
		return uniformGrid || spriteGrid != null;
	}

	/**
	 * The smallest boundary {@link Mipmapper}'s box filter must not average across.
	 *
	 * <p>The grid pitch on a grid, and the smallest sprite on a stitched sheet -- in both cases the
	 * thing that runs out of texels first as the chain deepens. 0 when neither is known, which stops
	 * the chain being built at all.
	 */
	public static int filterPitch() {
		if (uniformGrid) {
			return tileTexels;
		}
		SpriteGrid grid = spriteGrid;
		return grid == null ? 0 : grid.smallest();
	}

	/**
	 * How many mip levels below 0 may exist for this atlas.
	 *
	 * <p>Bounded by the PITCH, not by the atlas size: the 2x2 box filter may not cross a tile
	 * boundary, so the tile has to stay at least 2 texels across at the deepest level. A 16-texel
	 * tile therefore allows 4 levels ({@code log2(16)}) whatever the sheet around it grew to.
	 */
	public static int mipLevels() {
		if (uniformGrid) {
			return mipLevels(tileTexels);
		}
		// Stitched but placeable: the chain is bounded by the SMALLEST sprite, for the same reason
		// the grid bounds it by the pitch -- that is the one that runs out of texels first.
		SpriteGrid grid = spriteGrid;
		return grid == null ? 0 : mipLevels(grid.smallest());
	}

	/**
	 * True when this image can carry the mip chain.
	 *
	 * <p>{@link Mipmapper} downsamples the whole sheet rather than looping per tile, which is correct
	 * only because a 2x2 box at level L covers a 2^L-aligned region and every boundary on the sheet --
	 * a grid pitch, or a stitched sprite's power-of-two slot -- is a multiple of it. That holds for
	 * both shapes, so the only question is whether the sheet divides evenly.
	 */
	public static boolean canMipmap(int width, int height) {
		if (uniformGrid) {
			return canMipmap(width, height, tileTexels);
		}
		SpriteGrid grid = spriteGrid;
		return grid != null && canMipmap(width, height, grid.smallest());
	}

	// The two rules above with the pitch passed in, so the self-check can exercise every atlas shape
	// without a content API installed to produce one.

	static int mipLevels(int tileTexels) {
		int levels = 0;
		int pitch = tileTexels;
		while (levels < TerrainMipmaps.LEVELS && pitch >= 2) {
			pitch /= 2;
			levels++;
		}
		return levels;
	}

	static boolean canMipmap(int width, int height, int tileTexels) {
		return width == height && tileTexels >= 2 && width % tileTexels == 0;
	}

	/**
	 * Re-derives everything from the content APIs. Cheap enough for the per-frame call in
	 * {@link SectionDrawer}: two static field reads with RetroAPI, nothing at all with neither mod.
	 */
	public static synchronized void refresh() {
		// The SIZE comes from the upload that actually happened, and only the TILE COUNT from a
		// content API. That split matters: RetroAPI leaves its published size at the default when no
		// mod contributed a texture, so believing it over the image would put a 256-texel inset on a
		// 512-texel sheet. What it does always know is how it divided the sheet up.
		int width = observedWidth > 0 ? observedWidth : 256;
		int height = observedHeight > 0 ? observedHeight : width;
		int tiles = VANILLA_TILES;
		boolean grid = true;
		String source = "vanilla";

		if (StationApiAtlas.PRESENT) {
			// Stitched -- but not necessarily gridless. StationAPI packs with modern Minecraft's
			// TextureStitcher, which rounds every slot to a power of two and places it on its own
			// multiple, so a pack whose sprites are all one size produces a plain uniform grid. For
			// b1.7.3 content that is every sprite at 16x16, and the grid path both terrain shaders
			// already implement is then exactly right. Only a pack that MIXES sprite sizes has no
			// tile to reason about, and that case still falls back to one nearest tap.
			int[] size = StationApiAtlas.size();
			if (size != null) {
				width = size[0];
				height = size[1];
			}
			int[] sprites = StationApiAtlas.grid();
			if (sprites != null && sprites[0] > 0 && width > 0 && width % sprites[0] == 0) {
				tiles = width / sprites[0];
				grid = true;
			} else {
				grid = false;
			}
			source = "stationapi";
		} else if (RetroApiAtlas.PRESENT) {
			// {composited size, sprite size} -- their ratio is the grid RetroAPI generates UVs for.
			int[] atlas = RetroApiAtlas.terrain();
			if (atlas != null && atlas[1] > 0) {
				tiles = Math.max(1, atlas[0] / atlas[1]);
				if (observedWidth <= 0) {
					width = atlas[0];
					height = atlas[0];
				}
				source = "retroapi";
			}
		}

		int pitch = grid ? width / tiles : 0;

		if (FORCED_TEXELS > 0) {
			width = FORCED_TEXELS;
			height = FORCED_TEXELS;
			source = "forced";
		}
		if (FORCED_TILE != 0) {
			pitch = Math.max(0, FORCED_TILE);
			grid = FORCED_TILE > 0;
			source = "forced";
		}

		// The tile maths in both terrain shaders, and the mip builder, all assume a SQUARE sheet of
		// whole tiles. Anything else is treated as stitched rather than sampled with a grid that does
		// not divide it -- a wrong clamp bleeds neighbouring textures, which is the artefact this
		// whole path exists to remove.
		if (grid && (width != height || pitch < 1 || width % pitch != 0)) {
			grid = false;
			pitch = 0;
		}

		texels = width;
		tileTexels = pitch;
		uniformGrid = grid;

		String summary = source + " " + width + "x" + height
			+ (grid ? " grid " + (width / pitch) + "x" + (width / pitch) + " of " + pitch : " stitched");
		if (!summary.equals(reported)) {
			reported = summary;
			RetroDragon.detail("block atlas: {}", summary);
		}
	}

	/**
	 * RetroAPI's composited terrain sheet.
	 *
	 * <p>{@code AtlasExpander} keeps the size it grew the sheet to and the sprite size it composited
	 * at in two public statics, updated on every load, reload and texture-pack switch. Their RATIO is
	 * the grid it rewrote vanilla's UV divisor for, which is the one thing that cannot be worked out
	 * from the image alone -- a 512x512 sheet is 16 tiles of 32 to a texture pack and 32 tiles of 16
	 * to RetroAPI.
	 */
	private static final class RetroApiAtlas {
		static final boolean PRESENT = loaded("retroapi");
		private static Field size;
		private static Field sprite;
		private static boolean broken;

		static int[] terrain() {
			if (broken) {
				return null;
			}
			try {
				if (size == null) {
					Class<?> expander = Class.forName("com.periut.retroapi.client.texture.AtlasExpander");
					size = expander.getField("terrainAtlasSize");
					sprite = expander.getField("terrainSpriteSize");
				}
				int atlas = size.getInt(null);
				int pitch = sprite.getInt(null);
				return atlas > 0 && pitch > 0 ? new int[] { atlas, pitch } : null;
			} catch (Throwable t) {
				broken = true;
				RetroDragon.LOGGER.warn("retroapi is installed but its atlas size could not be read ({});"
					+ " falling back to the observed upload size", t.toString());
				return null;
			}
		}
	}

	/**
	 * StationAPI's stitched game atlas.
	 *
	 * <p>Read for the log line only -- see the class comment for why a stitched atlas cannot be
	 * sampled through a tile grid at all. Re-probed every 64th refresh, since the walk is three
	 * reflective calls and the answer only changes on a resource reload.
	 */
	private static final class StationApiAtlas {
		static final boolean PRESENT = loaded("stationapi");
		private static Method getBakedModelManager;
		private static Method getAtlas;
		private static Object atlasId;
		private static Method getWidth;
		private static Method getHeight;
		private static Field spriteMap;
		private static Method spriteX;
		private static Method spriteY;
		private static Method spriteContents;
		private static Method contentsWidth;
		private static Method contentsHeight;
		private static boolean linked;
		private static boolean broken;
		private static int[] cached;
		/** {@code {pitch, count}} from the last successful sprite walk; pitch 0 = not a grid. */
		private static int[] cachedGrid;
		private static String reportedSprites = "";

		static int[] size() {
			// Throttled whether or not there is an answer yet: before the first resource reload the
			// atlas does not exist, and retrying that from every frame costs a thrown exception each
			// time for a number that is only in the log.
			if (broken || probeCountdown-- > 0) {
				return cached;
			}
			probeCountdown = 64;
			if (!linked && !link()) {
				return cached;
			}
			try {
				Object atlas = getAtlas.invoke(getBakedModelManager.invoke(null), atlasId);
				int width = (Integer) getWidth.invoke(atlas);
				int height = (Integer) getHeight.invoke(atlas);
				if (width > 0 && height > 0) {
					cached = new int[] { width, height };
					cachedGrid = walkSprites(atlas);
				}
			} catch (Throwable t) {
				// No atlas yet, most likely. Not fatal and not worth a log line every 64 frames.
			}
			return cached;
		}

		/** The grid this atlas turns out to be, or null when it has not been walked yet. */
		static int[] grid() {
			return cachedGrid;
		}

		/**
		 * Whether the stitched sheet is, in fact, a uniform grid.
		 *
		 * <p>{@code TextureStitcher} is modern Minecraft's: it rounds every slot to
		 * {@code smallestEncompassingPowerOfTwo} and subdivides recursively, so a slot of size S always
		 * sits at a multiple of S. For b1.7.3 content every sprite is 16x16, which makes the sheet a
		 * plain grid of 16-texel tiles -- the same shape RetroAPI produces, and one both terrain
		 * shaders already sample correctly.
		 *
		 * <p>So this is not an approximation: it either finds one square power-of-two size shared by
		 * every sprite, each sprite aligned to it, or it reports no grid and the caller falls back to a
		 * single nearest tap. A pack that mixes sprite sizes lands in the second case honestly.
		 *
		 * @return {@code {pitch, spriteCount}}, pitch 0 when the sprites do not share one aligned size
		 */
		private static int[] walkSprites(Object atlas) throws Exception {
			java.util.Map<?, ?> byId = (java.util.Map<?, ?>) spriteMap.get(atlas);
			if (byId == null || byId.isEmpty()) {
				return null;
			}
			int atlasTexels = (Integer) getWidth.invoke(atlas);
			int pitch = 0;
			int count = 0;
			int smallest = Integer.MAX_VALUE;
			int largest = 0;
			boolean uniform = true;
			// Every sprite square, a power of two, and on its own multiple. That is what
			// TextureStitcher guarantees and what makes a size alone enough to find the origin; a
			// sprite failing it gets no entry and falls back to a single nearest tap.
			boolean placeable = true;
			for (Object sprite : byId.values()) {
				if (sprite == null) {
					continue;
				}
				Object contents = spriteContents.invoke(sprite);
				int w = (Integer) contentsWidth.invoke(contents);
				int h = (Integer) contentsHeight.invoke(contents);
				int x = (Integer) spriteX.invoke(sprite);
				int y = (Integer) spriteY.invoke(sprite);
				count++;
				smallest = Math.min(smallest, Math.min(w, h));
				largest = Math.max(largest, Math.max(w, h));
				if (w != h || w < 2 || (w & (w - 1)) != 0 || x % w != 0 || y % h != 0) {
					placeable = false;
					uniform = false;
				} else if (pitch == 0) {
					pitch = w;
				} else if (pitch != w) {
					uniform = false;
				}
			}
			if (!uniform) {
				pitch = 0;
			}

			// The per-sprite table, built whenever the sheet is placeable but NOT a single grid --
			// which is the case a tile index cannot describe and the per-vertex sprite size can.
			SpriteGrid built = null;
			if (placeable && !uniform && smallest >= 2 && atlasTexels % smallest == 0) {
				built = new SpriteGrid(smallest, atlasTexels / smallest, atlasTexels);
				for (Object sprite : byId.values()) {
					if (sprite == null) {
						continue;
					}
					Object contents = spriteContents.invoke(sprite);
					built.put((Integer) spriteX.invoke(sprite), (Integer) spriteY.invoke(sprite),
						(Integer) contentsWidth.invoke(contents));
				}
			}
			spriteGrid = built;

			String summary = count + " sprites, " + smallest + ".." + largest
				+ (pitch > 0 ? ", uniform grid of " + pitch
					: built != null ? ", mixed sizes -- per-sprite clamp on a " + smallest + " cell"
					: ", not placeable -- no clamp");
			if (!summary.equals(reportedSprites)) {
				reportedSprites = summary;
				RetroDragon.detail("stationapi atlas: {}", summary);
			}
			return new int[] { pitch, count };
		}

		/** Resolves every member in one go, so a half-linked state can never be used. */
		private static boolean link() {
			try {
				Class<?> api = Class.forName(
					"net.modificationstation.stationapi.api.client.StationRenderAPI");
				Method manager = api.getMethod("getBakedModelManager");
				Class<?> atlases = Class.forName(
					"net.modificationstation.stationapi.api.client.texture.atlas.Atlases");
				Object id = atlases.getField("GAME_ATLAS_TEXTURE").get(null);
				// The DECLARED parameter type, not id.getClass(): the identifier may be a subclass,
				// and getMethod matches the declaration exactly.
				Class<?> identifier = Class.forName(
					"net.modificationstation.stationapi.api.util.Identifier");
				Method atlasOf = manager.getReturnType().getMethod("getAtlas", identifier);
				Class<?> texture = Class.forName(
					"net.modificationstation.stationapi.api.client.texture.SpriteAtlasTexture");
				getWidth = texture.getMethod("getWidth");
				getHeight = texture.getMethod("getHeight");
				// The sprite table is private, and there is no public enumerator on the atlas -- only
				// getSprite(Identifier), which cannot answer "what shape is this sheet".
				spriteMap = texture.getDeclaredField("sprites");
				spriteMap.setAccessible(true);
				Class<?> spriteClass = Class.forName(
					"net.modificationstation.stationapi.api.client.texture.Sprite");
				spriteX = spriteClass.getMethod("getX");
				spriteY = spriteClass.getMethod("getY");
				spriteContents = spriteClass.getMethod("getContents");
				Class<?> contentsClass = Class.forName(
					"net.modificationstation.stationapi.api.client.texture.SpriteContents");
				contentsWidth = contentsClass.getMethod("getWidth");
				contentsHeight = contentsClass.getMethod("getHeight");
				getBakedModelManager = manager;
				atlasId = id;
				getAtlas = atlasOf;
				linked = true;
				return true;
			} catch (Throwable t) {
				// A structural mismatch with this StationAPI version. Give up for good: the size is
				// only reported, and the decision that matters -- a stitched atlas has no grid -- was
				// made from the mod being loaded at all.
				broken = true;
				RetroDragon.detail("stationapi atlas size unavailable ({}); its atlas is"
					+ " stitched either way, which is all the renderer needs to know", t.toString());
				return false;
			}
		}
	}

	/**
	 * {@code ./gradlew blockAtlasTest} -- the grid maths, without a game or a content API.
	 *
	 * <p>What it pins down is the thing that was silently wrong before: the pitch and the tile COUNT
	 * are independent. A 512x512 sheet is 16 tiles per axis when a texture pack doubled the sprites,
	 * and 32 when RetroAPI added rows of 16-texel sprites, and those two need different clamp
	 * boundaries from the same atlas size.
	 */
	public static void main(String[] args) {
		// Vanilla, and an HD pack: 16 sprites per axis, so the pitch scales with the sheet.
		observe(256, 256);
		expect(texels() == 256 && tileTexels() == 16 && uniformGrid() && tiles() == 16.0F,
			"vanilla: 256 texels, 16-texel tiles, 16 per axis");
		observe(512, 512);
		expect(texels() == 512 && tileTexels() == 32 && tiles() == 16.0F,
			"HD pack: a doubled sheet has doubled tiles, still 16 per axis");

		// A non-square or indivisible sheet is not a grid, and must not be sampled as one.
		observe(512, 256);
		expect(!uniformGrid() && tileTexels() == 0 && mipLevels() == 0,
			"a non-square sheet has no usable grid");
		observe(256, 256);
		expect(uniformGrid(), "and going back to a square sheet restores the grid");

		// RetroAPI's shape: the sheet grew, the sprites did not. 32 tiles per axis of 16 texels.
		expect(canMipmap(512, 512, 16), "a 512 sheet of 16-texel tiles is mipmappable");
		expect(mipLevels(16) == 4, "a 16-texel tile allows 4 levels whatever the sheet size");
		expect(mipLevels(8) == 3 && mipLevels(4) == 2 && mipLevels(2) == 1 && mipLevels(1) == 0,
			"the chain is bounded by the tile, not by the sheet");
		expect(!canMipmap(512, 256, 16), "a non-square sheet cannot carry the chain");
		expect(!canMipmap(300, 300, 16), "nor can a sheet the tile does not divide");

		// The box filter must stay inside a tile at every level it is allowed to reach: a tile of
		// 16 texels at level 4 is 1 texel, and the level after that would average across the border.
		int pitch = 16;
		for (int level = 0; level < mipLevels(16); level++) {
			pitch /= 2;
			expect(pitch >= 1, "level " + (level + 1) + " left a sub-texel tile");
		}

		spriteGridChecks();

		System.out.println("BlockAtlas self-check OK");
	}

	/**
	 * The stitched-sheet lookup: a mixed-size sheet, addressed the way a vertex addresses it.
	 *
	 * <p>The property that matters is that a coordinate ANYWHERE inside a sprite reports that
	 * sprite's size, including at its last texel -- because the shader recovers the origin with
	 * {@code floor(uv / size) * size}, and a size that is wrong by one step puts the clamp on a
	 * neighbouring texture's boundary. A 32-sprite is checked at all four of its corners for exactly
	 * that reason: it spans four 16-texel cells and every one of them has to agree.
	 */
	private static void spriteGridChecks() {
		// 64x64 sheet: a 32 sprite at (0,0), 16s filling the rest of the top-left quadrant's row.
		SpriteGrid grid = new SpriteGrid(16, 4, 64);
		grid.put(0, 0, 32);
		grid.put(32, 0, 16);
		grid.put(48, 0, 16);
		grid.put(32, 16, 16);

		expect(grid.smallest() == 16, "the cell is the smallest sprite");
		// Every corner of the 32 sprite, in texels, including its far edge.
		for (int[] at : new int[][] { { 0, 0 }, { 31, 0 }, { 0, 31 }, { 31, 31 }, { 16, 16 } }) {
			expect(grid.sizeAtTexel(at[0], at[1]) == 32,
				"a 32 sprite reads 32 at texel " + at[0] + "," + at[1]);
		}
		expect(grid.sizeAtTexel(32, 0) == 16 && grid.sizeAtTexel(47, 15) == 16,
			"the 16 sprite beside it reads 16");
		expect(grid.sizeAtTexel(0, 48) == 0, "an unmapped cell reads 0, which disables the clamp");

		// Through a texture coordinate, which is how the mesher asks. The last texel of the 32 sprite
		// is at uv just under 32/64, and must still report 32 rather than the neighbour's 16.
		expect(grid.sizeAt(0.0F, 0.0F) == 32, "uv 0,0 lands in the 32 sprite");
		expect(grid.sizeAt(31.5F / 64.0F, 31.5F / 64.0F) == 32,
			"the 32 sprite's last texel still reads 32");
		expect(grid.sizeAt(32.5F / 64.0F, 0.5F / 64.0F) == 16,
			"one texel further is the next sprite");

		// The chain is bounded by the smallest sprite, exactly as it is by the pitch on a grid.
		expect(mipLevels(16) == 4, "a 16-texel sprite allows 4 levels");
		System.out.println("BlockAtlas sprite grid OK: mixed sizes resolve to the owning sprite");
	}

	private static void expect(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what + " -- got " + reported
				+ " (texels " + texels + ", tile " + tileTexels + ", grid " + uniformGrid + ")");
		}
	}

	private static boolean loaded(String modId) {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId);
		} catch (Throwable t) {
			// No loader (the offscreen self-checks run on a bare classpath): vanilla rules apply.
			return false;
		}
	}
}
