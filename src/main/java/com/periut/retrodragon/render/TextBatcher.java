package com.periut.retrodragon.render;

import com.periut.retrodragon.shim.DrawList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Draws beta's bitmap font as one batch per string instead of one draw call per character.
 *
 * <h2>What this replaces</h2>
 *
 * b1.7.3's text renderer compiles 288 display lists at startup -- one per glyph, plus 32 that only
 * change the current colour -- and renders a string by filling an {@code IntBuffer} with list IDs and
 * calling {@code glCallLists}. Each glyph list draws a quad and then issues a {@code glTranslatef}
 * for the advance, so the position of character N depends on having executed lists 0..N-1.
 *
 * <p>That shape cannot survive translation. A display list replayed as geometry loses the embedded
 * advance, so every glyph in a string would stack at the same spot; replayed as commands it costs a
 * draw call and a uniform update per character. The debug overlay alone is a few hundred characters,
 * and the title screen's buttons and splash text are dozens each.
 *
 * <p>So the glyph quads are generated directly here, with the advance accumulated on the CPU and the
 * colour baked into the vertices. One string becomes one batch -- and because the modelview is left
 * untouched (the string's position goes into the vertices, not into a {@code glTranslatef}),
 * consecutive strings produce identical uniform blocks and merge into a single draw.
 *
 * <p>Vertices are emitted in beta's own 32-byte layout, so this feeds the same path as everything
 * else and needs no special case downstream.
 */
public final class TextBatcher {
	/** The font sheet is a 16x16 grid of 8-pixel cells. */
	private static final int COLUMNS = 16;
	private static final float SHEET = 128.0F;
	/**
	 * 7.99 rather than 8: sampling exactly on a cell boundary picks up the neighbouring glyph's
	 * edge texel. Beta uses the same fudge, and matching it matters -- a different value shifts every
	 * glyph by a fraction of a texel against the GL path.
	 */
	private static final float CELL = 7.99F;

	/** Where the printable characters start in the sheet; the first two rows are control codes. */
	private static final int CHARACTER_OFFSET = 32;

	private static final char COLOR_PREFIX = '§';
	private static final String COLOR_CODES = "0123456789abcdef";

	/** Four vertices per glyph, 32 bytes each; grows to the longest string ever drawn. */
	private static ByteBuffer vertices =
		ByteBuffer.allocateDirect(256 * 4 * DrawList.STRIDE).order(ByteOrder.nativeOrder());

	private TextBatcher() {
	}

	/**
	 * @param widths     beta's per-cell advance table, indexed by sheet cell
	 * @param texture    the font texture's GL name
	 * @param validChars the character set, whose index into it selects the sheet cell
	 * @param shadow     true for the offset dark pass beta draws underneath
	 * @return the number of glyphs emitted
	 */
	public static int draw(int[] widths, int texture, String validChars, String text,
			float x, float y, int color, boolean shadow) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		if (shadow) {
			// Beta's own shadow colour: each channel quartered, alpha preserved.
			color = ((color & 0xFCFCFC) >> 2) + (color & 0xFF000000);
		}
		int rgba = toRgba(color);

		ensureCapacity(text.length());
		vertices.clear();

		float pen = x;
		int glyphs = 0;
		String lower = text.toLowerCase(java.util.Locale.ROOT);

		for (int i = 0; i < text.length(); i++) {
			// Colour codes come in pairs and may repeat; beta consumes them in a loop, so a string
			// like "§a§lx" changes colour twice before drawing anything.
			while (i < text.length() - 1 && text.charAt(i) == COLOR_PREFIX) {
				int code = COLOR_CODES.indexOf(lower.charAt(i + 1));
				if (code < 0 || code > 15) {
					code = 15;
				}
				rgba = toRgba(paletteColor(code, shadow) | color & 0xFF000000);
				i += 2;
			}
			if (i >= text.length()) {
				break;
			}

			int index = validChars.indexOf(text.charAt(i));
			if (index < 0) {
				// Not in the font: beta draws nothing AND does not advance, so neither do we.
				continue;
			}
			int cell = index + CHARACTER_OFFSET;
			emit(pen, y, cell, rgba);
			glyphs++;
			pen += widths[cell];
		}

		if (glyphs == 0) {
			return 0;
		}
		vertices.position(0).limit(glyphs * 4 * DrawList.STRIDE);
		WebGpuFrame.captureText(vertices, glyphs * 4, texture);
		return glyphs;
	}

	/** One glyph quad, wound the way beta's Tessellator winds them. */
	private static void emit(float x, float y, int cell, int rgba) {
		float u = cell % COLUMNS * 8 / SHEET;
		float v = cell / COLUMNS * 8 / SHEET;
		float du = CELL / SHEET;

		vertex(x, y + CELL, u, v + du, rgba);
		vertex(x + CELL, y + CELL, u + du, v + du, rgba);
		vertex(x + CELL, y, u + du, v, rgba);
		vertex(x, y, u, v, rgba);
	}

	private static void vertex(float x, float y, float u, float v, int rgba) {
		int base = vertices.position();
		vertices.putFloat(base, x);
		vertices.putFloat(base + 4, y);
		vertices.putFloat(base + 8, 0.0F);
		vertices.putFloat(base + 12, u);
		vertices.putFloat(base + 16, v);
		vertices.putInt(base + 20, rgba);
		vertices.putInt(base + 24, 0);
		vertices.putInt(base + 28, 0);
		vertices.position(base + DrawList.STRIDE);
	}

	/** ARGB int to the R,G,B,A byte order the vertex layout stores. */
	private static int toRgba(int argb) {
		int a = argb >>> 24;
		if (a == 0) {
			// Beta treats a zero alpha as "unspecified" and draws opaque.
			a = 255;
		}
		return a << 24 | (argb & 0xFF) << 16 | (argb >> 8 & 0xFF) << 8 | argb >> 16 & 0xFF;
	}

	/**
	 * Minecraft's sixteen text colours, generated rather than tabulated because that is how the game
	 * builds them: two bits of intensity per channel, plus a brightness step for the high eight, and
	 * one special case where gold would otherwise be indistinguishable from orange.
	 */
	private static int paletteColor(int code, boolean shadow) {
		int step = (code >> 3) * 85;
		int red = (code >> 2 & 1) * 170 + step;
		int green = (code >> 1 & 1) * 170 + step;
		int blue = (code & 1) * 170 + step;
		if (code == 6) {
			red += 85;
		}
		if (shadow) {
			red /= 4;
			green /= 4;
			blue /= 4;
		}
		return red << 16 | green << 8 | blue;
	}

	private static void ensureCapacity(int characters) {
		int needed = characters * 4 * DrawList.STRIDE;
		if (needed <= vertices.capacity()) {
			return;
		}
		int capacity = vertices.capacity();
		while (capacity < needed) {
			capacity *= 2;
		}
		vertices = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
	}
}
