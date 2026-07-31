package com.periut.retrodragon.shim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import com.periut.retrodragon.RetroDragon;

/**
 * GL's pixel-unpack state, and the conversion of an upload into what the GPU takes.
 *
 * <h2>Why this exists</h2>
 *
 * {@code GpuTexture.upload} wants one thing: RGBA8 texels, tightly packed, {@code w * h * 4} bytes.
 * Beta always hands exactly that over -- its PNG loader converts to RGBA bytes itself and it never
 * touches {@code glPixelStorei} -- so the shim used to ignore the {@code format}, {@code type} and
 * unpack arguments entirely and pass the buffer straight through.
 *
 * <p>StationAPI does neither of those things, and the result was the reported bug: every block white
 * (a 1x1 white texture stood in for an atlas that had never been uploaded) with grass and leaves
 * green, because their biome tint lives in the vertex colour and survived the missing texture. Two
 * separate assumptions broke:
 *
 * <ul>
 *   <li><b>Component order and width.</b> {@code NativeImage} uploads whatever its {@code Format}
 *       says -- {@code GL_RGB}, {@code GL_LUMINANCE}, {@code GL_LUMINANCE_ALPHA} -- and
 *       {@code TextureUtil.initTexture} uploads {@code GL_BGRA} with
 *       {@code GL_UNSIGNED_INT_8_8_8_8_REV}, which is the ARGB int a {@code BufferedImage} holds.
 *       Read as RGBA bytes, a 3-byte-per-texel image is sheared and an ARGB int has its channels
 *       rotated.</li>
 *   <li><b>Sub-rectangles.</b> {@code NativeImage.upload} does not slice the source image. It
 *       uploads the WHOLE buffer and selects the rectangle with {@code GL_UNPACK_ROW_LENGTH},
 *       {@code GL_UNPACK_SKIP_PIXELS} and {@code GL_UNPACK_SKIP_ROWS} -- which is how every animated
 *       sprite's frames are addressed. Ignoring those uploads the top-left corner of the sheet
 *       instead of the requested frame.</li>
 * </ul>
 *
 * <p>Beta's own path is unchanged and still costs nothing: tightly packed
 * {@code GL_RGBA}/{@code GL_UNSIGNED_BYTE} with no skips returns the caller's own buffer, with no
 * copy and no per-texel loop.
 */
public final class PixelStore {
	// Formats. Spelled out rather than imported, like the rest of the shim: under WebGPU the real
	// GL11 has been rewritten out from under us and must not be depended on for constants.
	private static final int GL_ALPHA = 0x1906;
	private static final int GL_RGB = 0x1907;
	private static final int GL_RGBA = 0x1908;
	private static final int GL_LUMINANCE = 0x1909;
	private static final int GL_LUMINANCE_ALPHA = 0x190A;
	private static final int GL_BGR = 0x80E0;
	private static final int GL_BGRA = 0x80E1;

	// Types.
	private static final int GL_UNSIGNED_BYTE = 0x1401;
	private static final int GL_UNSIGNED_INT_8_8_8_8 = 0x8035;
	private static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;

	// Unpack state. The pack half (GL_PACK_*) is not kept: readback is already tightly packed.
	private static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
	private static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
	private static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
	private static final int GL_UNPACK_ALIGNMENT = 0x0CF5;

	private static volatile int rowLength;
	private static volatile int skipRows;
	private static volatile int skipPixels;
	private static volatile int alignment = 4;

	/** One warning per unhandled (format, type) pair, not one per upload. */
	private static volatile String warned = "";

	private PixelStore() {
	}

	/** Records one {@code glPixelStorei}. Unknown parameters keep GL's default, which is theirs. */
	public static void store(int parameter, int value) {
		switch (parameter) {
			case GL_UNPACK_ROW_LENGTH -> rowLength = Math.max(0, value);
			case GL_UNPACK_SKIP_ROWS -> skipRows = Math.max(0, value);
			case GL_UNPACK_SKIP_PIXELS -> skipPixels = Math.max(0, value);
			// GL accepts only these four; anything else is a GL_INVALID_VALUE that leaves the state
			// alone, so falling back to the default is the faithful answer rather than a guess.
			case GL_UNPACK_ALIGNMENT ->
				alignment = value == 1 || value == 2 || value == 4 || value == 8 ? value : 4;
			default -> {
				// GL_UNPACK_SWAP_BYTES and GL_UNPACK_LSB_FIRST are set to their defaults (false) by
				// StationAPI and never to anything else; the pack parameters do not affect uploads.
			}
		}
	}

	/**
	 * One upload's pixels as tightly packed RGBA8, or null when there is nothing to upload.
	 *
	 * @return the caller's own buffer when it already holds exactly that, so beta's path is a
	 *         pass-through; otherwise a fresh direct buffer positioned at 0
	 */
	public static ByteBuffer rgba(int format, int type, int width, int height, ByteBuffer pixels) {
		if (pixels == null || width <= 0 || height <= 0) {
			return null;
		}
		int bpp = bytesPerPixel(format, type);
		if (bpp == 0) {
			warn(format, type);
			return null;
		}
		int stride = rowStride(width, bpp);
		int base = pixels.position() + skipRows * stride + skipPixels * bpp;
		// Checked before the pass-through, not after: a buffer too small for the rectangle asked for
		// would otherwise be handed to the queue as-is, and a texture write reads what it was told to
		// whether or not the caller allocated it.
		if (base < 0 || (long) base + (long) (height - 1) * stride + (long) width * bpp
				> pixels.limit()) {
			truncated(width, height);
			return null;
		}
		if (format == GL_RGBA && type == GL_UNSIGNED_BYTE
				&& base == pixels.position() && stride == width * 4) {
			return pixels;
		}
		// Packed types are a native-endian word however the caller's buffer is labelled: GL reads the
		// memory, not a Java view of it.
		ByteBuffer source = type == GL_UNSIGNED_BYTE
			? pixels
			: pixels.duplicate().order(ByteOrder.nativeOrder());
		ByteBuffer out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
		for (int y = 0; y < height; y++) {
			int row = base + y * stride;
			for (int x = 0; x < width; x++) {
				int at = row + x * bpp;
				if (type == GL_UNSIGNED_BYTE) {
					putBytes(out, format, source, at);
				} else {
					putPacked(out, format, type, source.getInt(at));
				}
			}
		}
		return out.position(0);
	}

	/**
	 * The {@code IntBuffer} spelling of the same upload.
	 *
	 * <p>Not an overload for tidiness: it is the one StationAPI's {@code TextureUtil} and beta's own
	 * LWJGL3 compat layer both call, and until it existed every one of those uploads was rewritten to
	 * an empty method body by {@code GlPlugin} and silently did nothing.
	 */
	public static ByteBuffer rgba(int format, int type, int width, int height, IntBuffer pixels) {
		if (pixels == null || width <= 0 || height <= 0) {
			return null;
		}
		int stride = bytesPerPixel(format, type) == 4 ? rowStride(width, 4) : 0;
		if (stride == 0 || stride % 4 != 0) {
			// Sub-word components, or a row padded to an odd number of ints. Reinterpret the buffer's
			// memory as bytes and take the ordinary path rather than growing a second loop for a case
			// nothing in practice hits.
			ByteBuffer bytes = ByteBuffer.allocateDirect(pixels.remaining() * 4).order(pixels.order());
			bytes.asIntBuffer().put(pixels.duplicate());
			return rgba(format, type, width, height, bytes);
		}
		int ints = stride / 4;
		int base = pixels.position() + skipRows * ints + skipPixels;
		if (base < 0 || (long) base + (long) (height - 1) * ints + width > pixels.limit()) {
			truncated(width, height);
			return null;
		}
		// An int of GL_UNSIGNED_BYTE components is its four bytes in memory order, which is exactly
		// what the two packed types describe -- _REV on a little-endian machine, the plain one on a
		// big-endian one. Reusing them keeps a single decode instead of a third spelling of it.
		int packed = type != GL_UNSIGNED_BYTE ? type
			: ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
				? GL_UNSIGNED_INT_8_8_8_8_REV : GL_UNSIGNED_INT_8_8_8_8;
		ByteBuffer out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
		for (int y = 0; y < height; y++) {
			int row = base + y * ints;
			for (int x = 0; x < width; x++) {
				putPacked(out, format, packed, pixels.get(row + x));
			}
		}
		return out.position(0);
	}

	/** Bytes one texel occupies in the SOURCE, or 0 for a combination this shim cannot read. */
	private static int bytesPerPixel(int format, int type) {
		if (type == GL_UNSIGNED_INT_8_8_8_8 || type == GL_UNSIGNED_INT_8_8_8_8_REV) {
			return format == GL_RGBA || format == GL_BGRA ? 4 : 0;
		}
		if (type != GL_UNSIGNED_BYTE) {
			return 0;
		}
		return switch (format) {
			case GL_RGBA, GL_BGRA -> 4;
			case GL_RGB, GL_BGR -> 3;
			case GL_LUMINANCE_ALPHA -> 2;
			case GL_LUMINANCE, GL_ALPHA -> 1;
			default -> 0;
		};
	}

	/** A source row in bytes: GL_UNPACK_ROW_LENGTH texels, rounded up to GL_UNPACK_ALIGNMENT. */
	private static int rowStride(int width, int bpp) {
		int texels = rowLength > 0 ? rowLength : width;
		int bytes = texels * bpp;
		int align = Math.max(1, alignment);
		return (bytes + align - 1) / align * align;
	}

	private static void putBytes(ByteBuffer out, int format, ByteBuffer src, int at) {
		byte opaque = (byte) 0xFF;
		switch (format) {
			case GL_RGBA -> out.put(src.get(at)).put(src.get(at + 1))
				.put(src.get(at + 2)).put(src.get(at + 3));
			case GL_BGRA -> out.put(src.get(at + 2)).put(src.get(at + 1))
				.put(src.get(at)).put(src.get(at + 3));
			case GL_RGB -> out.put(src.get(at)).put(src.get(at + 1)).put(src.get(at + 2)).put(opaque);
			case GL_BGR -> out.put(src.get(at + 2)).put(src.get(at + 1)).put(src.get(at)).put(opaque);
			case GL_LUMINANCE -> {
				byte l = src.get(at);
				out.put(l).put(l).put(l).put(opaque);
			}
			case GL_LUMINANCE_ALPHA -> {
				byte l = src.get(at);
				out.put(l).put(l).put(l).put(src.get(at + 1));
			}
			// GL's own answer for an ALPHA texture is RGB = 0, not RGB = white. Reproduced rather
			// than improved on: a caller relying on it wants what GL would have drawn.
			case GL_ALPHA -> out.put((byte) 0).put((byte) 0).put((byte) 0).put(src.get(at));
			default -> {
			}
		}
	}

	/**
	 * One texel of a packed 8_8_8_8 type.
	 *
	 * <p>{@code _REV} puts the FIRST component of the format in the LOW byte, which is why
	 * {@code GL_BGRA} + {@code GL_UNSIGNED_INT_8_8_8_8_REV} is precisely a Java {@code 0xAARRGGBB} --
	 * the reason every Minecraft version has uploaded {@code BufferedImage} pixels that way.
	 */
	private static void putPacked(ByteBuffer out, int format, int type, int value) {
		int c0;
		int c1;
		int c2;
		int c3;
		if (type == GL_UNSIGNED_INT_8_8_8_8_REV) {
			c0 = value & 0xFF;
			c1 = value >>> 8 & 0xFF;
			c2 = value >>> 16 & 0xFF;
			c3 = value >>> 24 & 0xFF;
		} else {
			c0 = value >>> 24 & 0xFF;
			c1 = value >>> 16 & 0xFF;
			c2 = value >>> 8 & 0xFF;
			c3 = value & 0xFF;
		}
		if (format == GL_BGRA) {
			out.put((byte) c2).put((byte) c1).put((byte) c0).put((byte) c3);
		} else {
			out.put((byte) c0).put((byte) c1).put((byte) c2).put((byte) c3);
		}
	}

	private static void warn(int format, int type) {
		String key = format + "/" + type;
		if (!key.equals(warned)) {
			warned = key;
			RetroDragon.LOGGER.warn("texture upload in an unsupported layout (format 0x{}, type 0x{});"
				+ " it will be skipped rather than uploaded as the wrong colours",
				Integer.toHexString(format), Integer.toHexString(type));
		}
	}

	private static void truncated(int width, int height) {
		RetroDragon.LOGGER.warn("texture upload of {}x{} runs past the end of its buffer with the"
			+ " current unpack state; skipped", width, height);
	}

	/**
	 * {@code ./gradlew pixelStoreTest} -- the conversions, without a GPU.
	 *
	 * <p>What it pins down is the two things that were silently wrong: a packed ARGB int must come
	 * out as RGBA in that order, and a sub-rectangle selected purely by unpack state must be the
	 * rectangle that was asked for rather than the corner of the sheet.
	 */
	public static void main(String[] args) {
		reset();

		// Beta's own upload: already what the GPU takes, so the same buffer comes back.
		ByteBuffer tight = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder());
		expect(rgba(GL_RGBA, GL_UNSIGNED_BYTE, 2, 2, tight) == tight,
			"tightly packed RGBA bytes are passed through without a copy");

		// GL_BGRA + _REV is a Java ARGB int. 0xFF204060 is r=0x20, g=0x40, b=0x60, a=0xFF.
		IntBuffer argb = IntBuffer.wrap(new int[] { 0xFF204060 });
		ByteBuffer out = rgba(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 1, 1, argb);
		expect(rgbaAt(out, 0) == 0x204060FF, "an ARGB int unpacks to R,G,B,A in that order");

		// The same bits read as GL_RGBA: the first component is now R, so the channels rotate.
		out = rgba(GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, 1, 1, IntBuffer.wrap(new int[] { 0xFF204060 }));
		expect(rgbaAt(out, 0) == 0x604020FF, "and as RGBA_REV the same word is B,G,R -> R,G,B");

		// Three bytes per texel, which read as RGBA would shear the image by one channel per texel.
		ByteBuffer rgb = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder());
		rgb.put(new byte[] { 1, 2, 3, 4, 5, 6 }).position(0);
		out = rgba(GL_RGB, GL_UNSIGNED_BYTE, 2, 1, rgb);
		expect(rgbaAt(out, 0) == 0x010203FF && rgbaAt(out, 1) == 0x040506FF,
			"a 3-byte texel gains an opaque alpha and does not shift its neighbour");

		// StationAPI's animated sprites: the whole sheet is handed over and the frame is chosen with
		// unpack state alone. A 4x4 sheet of ARGB ints, taking the bottom-right 2x2.
		int[] sheet = new int[16];
		for (int i = 0; i < sheet.length; i++) {
			sheet[i] = 0xFF000000 | i;
		}
		reset();
		store(GL_UNPACK_ROW_LENGTH, 4);
		store(GL_UNPACK_SKIP_PIXELS, 2);
		store(GL_UNPACK_SKIP_ROWS, 2);
		out = rgba(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 2, 2, IntBuffer.wrap(sheet));
		expect((rgbaAt(out, 0) & 0xFF00) >> 8 == 10 && (rgbaAt(out, 1) & 0xFF00) >> 8 == 11
				&& (rgbaAt(out, 2) & 0xFF00) >> 8 == 14 && (rgbaAt(out, 3) & 0xFF00) >> 8 == 15,
			"row length and skip select the requested rectangle, not the top-left corner");

		// The same selection through the byte path, which is what NativeImage's RGBA images take.
		ByteBuffer bytes = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder());
		for (int i = 0; i < 16; i++) {
			bytes.put((byte) i).put((byte) 0).put((byte) 0).put((byte) 0xFF);
		}
		bytes.position(0);
		out = rgba(GL_RGBA, GL_UNSIGNED_BYTE, 2, 2, bytes);
		expect(rgbaAt(out, 0) >>> 24 == 10 && rgbaAt(out, 3) >>> 24 == 15,
			"and the byte path selects the same rectangle");

		// Alignment pads a 3-byte row out to the next multiple of 4.
		reset();
		store(GL_UNPACK_ALIGNMENT, 4);
		ByteBuffer padded = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		padded.put(new byte[] { 1, 2, 3, 0, 4, 5, 6, 0 }).position(0);
		out = rgba(GL_RGB, GL_UNSIGNED_BYTE, 1, 2, padded);
		expect(rgbaAt(out, 0) == 0x010203FF && rgbaAt(out, 1) == 0x040506FF,
			"a padded row is stepped over by its aligned stride");

		reset();
		expect(rgba(GL_RGBA, GL_UNSIGNED_BYTE, 4, 4, (ByteBuffer) null) == null,
			"a null upload allocates rather than uploading");
		expect(rgba(GL_RGBA, 0x1406, 1, 1, ByteBuffer.allocateDirect(16)) == null,
			"an unreadable layout is skipped rather than guessed at");
		expect(rgba(GL_RGBA, GL_UNSIGNED_BYTE, 8, 8, ByteBuffer.allocateDirect(16)) == null,
			"and so is an upload that runs past the end of its buffer");

		System.out.println("PixelStore self-check OK");
	}

	/** GL's defaults, which is where every one of these starts. */
	private static void reset() {
		rowLength = 0;
		skipRows = 0;
		skipPixels = 0;
		alignment = 4;
	}

	private static int rgbaAt(ByteBuffer out, int texel) {
		int at = texel * 4;
		return (out.get(at) & 0xFF) << 24 | (out.get(at + 1) & 0xFF) << 16
			| (out.get(at + 2) & 0xFF) << 8 | out.get(at + 3) & 0xFF;
	}

	private static void expect(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
