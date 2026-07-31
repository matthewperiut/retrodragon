package com.periut.retrodragon.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Capture buffer for beta's {@link net.minecraft.client.render.Tessellator}.
 *
 * <p>Writes one of two layouts, chosen once per section by {@link TerrainVertex}:
 *
 * <pre>
 *   LEGACY   +0 pos xyz (3xfloat)  +12 uv (2xfloat)     +20 colour (4xubyte)  +24 normal  +28 pad
 *   COMPACT  +0 pos xyz (3xfloat)  +12 uv (2xunorm16)   +16 colour (4xubyte)
 * </pre>
 *
 * LEGACY is byte-for-byte what the vanilla Tessellator writes, so the VBO can be drawn with exactly
 * the pointer setup vanilla uses -- a storage change, not a rendering change. The GL backend needs
 * that, because fixed-function {@code glTexCoordPointer} has no normalised integer format.
 *
 * <p>COMPACT drops the normal, which beta's terrain lighting bakes into the vertex colour and
 * {@code terrain.wgsl} never reads, and narrows the texture coordinate to the precision an atlas of
 * a few hundred texels actually carries. See {@link TerrainVertex} for the arithmetic.
 *
 * Position is baked as {@code (x + offset) * scale + bias}, which folds in the two matrix
 * transforms vanilla's ChunkBuilder wrapped around each display list -- the translate to
 * renderX/Y/Z and the 1.000001 seam scale about the section centre. Baking them makes every
 * section in the same 1024-block cell share one modelview, which is what lets them batch.
 */
public final class VertexSink {
	/** ints per vertex -- 32-byte stride, as vanilla. */
	public static final int STRIDE_INTS = 8;
	public static final int STRIDE_BYTES = 32;

	private int[] buf = new int[STRIDE_INTS * 512];
	private int pos;
	private int vertexCount;
	private int addedVertexCount;

	/**
	 * Ints per vertex for the layout this batch is being written in -- 8 legacy, 5 compact.
	 *
	 * <p>Fixed at {@code begin}, like {@link #splitQuads} and for the same reason: a buffer written
	 * in two shapes is not recoverable, and the consumer reads the shape from the same one flag.
	 */
	private int strideInts = STRIDE_INTS;
	private boolean compact;

	/**
	 * Read once per {@code begin}, not per vertex, and held for the batch.
	 *
	 * <p>A section's geometry is built on a worker thread and drawn on the render thread, and the
	 * two must agree on what a quad occupies. Sampling the flag once at the start of the section
	 * makes that agreement structural: everything in one buffer is shaped the same way, whatever
	 * happens to the flag afterwards. It never does change after startup, but a mesh half in each
	 * shape is unrecoverable and this costs nothing.
	 */
	private boolean splitQuads = true;

	private double offX, offY, offZ;
	private double scale = 1.0;
	private double biasX, biasY, biasZ;

	private float u, v;
	private int color = 0xFFFFFFFF;
	private int normal;
	private boolean colorDisabled;

	public void begin(double offX, double offY, double offZ, double scale, double biasX, double biasY, double biasZ) {
		this.pos = 0;
		this.vertexCount = 0;
		this.addedVertexCount = 0;
		this.splitQuads = !QuadVertices.indexed();
		this.compact = TerrainVertex.compact();
		this.strideInts = TerrainVertex.strideInts(this.compact);
		this.offX = offX;
		this.offY = offY;
		this.offZ = offZ;
		this.scale = scale;
		this.biasX = biasX;
		this.biasY = biasY;
		this.biasZ = biasZ;
		this.u = this.v = 0.0F;
		this.color = 0xFFFFFFFF;
		this.normal = 0;
		this.colorDisabled = false;
	}

	public int vertexCount() {
		return this.vertexCount;
	}

	public void setOffset(double x, double y, double z) {
		this.offX = x;
		this.offY = y;
		this.offZ = z;
	}

	public void translate(float x, float y, float z) {
		this.offX += x;
		this.offY += y;
		this.offZ += z;
	}

	public void texture(double u, double v) {
		this.u = (float) u;
		this.v = (float) v;
	}

	public void disableColor() {
		this.colorDisabled = true;
	}

	/** Vanilla clamps then packs in native byte order; both are reproduced exactly. */
	public void color(int r, int g, int b, int a) {
		if (this.colorDisabled) {
			return;
		}
		r = clamp(r);
		g = clamp(g);
		b = clamp(b);
		a = clamp(a);
		this.color = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
			? a << 24 | b << 16 | g << 8 | r
			: r << 24 | g << 16 | b << 8 | a;
	}

	private static int clamp(int c) {
		return c > 255 ? 255 : Math.max(c, 0);
	}

	public void normal(float x, float y, float z) {
		int i = (byte) (x * 128.0F);
		int j = (byte) (y * 127.0F);
		int k = (byte) (z * 127.0F);
		this.normal = i | j << 8 | k << 16;
	}

	public void vertex(double x, double y, double z) {
		this.addedVertexCount++;
		// Four vertices per quad when the backend expands them with an index buffer instead; see
		// QuadVertices. The whole split below is then dead work whose only product is 50% more
		// geometry.
		//
		// Vanilla's TRIANGLE_MODE quad split: on the 4th vertex of a quad, re-emit v0 and v2 so
		// v0,v1,v2,v0,v2,v3 draws as GL_TRIANGLES.
		//
		// Vanilla copies pos/uv/color but NOT the normal word, and this used to reproduce that
		// omission verbatim. That is faithful to the code and wrong about the behaviour. Under GL a
		// normal is CURRENT STATE: glNormal3f applies to every vertex emitted after it, so a quad
		// whose face normal was set once has that normal at all four corners no matter how the
		// vertices are assembled. Leaving slot 6 unwritten instead hands the two duplicated vertices
		// whatever the buffer held there from a previous batch.
		//
		// Two of a face's six vertices then carry a stale normal, so per-vertex lighting is
		// discontinuous across exactly the edge the two triangles share -- a bright-to-dark gradient
		// with a hard diagonal crease down every quad. It is most obvious on entities and dropped
		// items, which are the geometry that is both lit and built entirely from quads.
		int stride = this.strideInts;
		if (this.splitQuads && this.addedVertexCount % 4 == 0) {
			grow(2 * stride);
			for (int i = 0; i < 2; i++) {
				// v0 then v2, counting back from where the copy is being written.
				int from = this.pos - stride * (3 - i);
				// A whole-vertex copy rather than a hand-listed set of slots. Vanilla lists them and
				// leaves the normal out, which is the omission described above; copying the vertex
				// entire is both the fix and the only spelling that survives a change of layout.
				System.arraycopy(this.buf, from, this.buf, this.pos, stride);
				this.vertexCount++;
				this.pos += stride;
			}
		}

		store(x, y, z);
	}

	/**
	 * Appends vertices already packed in beta's Tessellator layout -- 8 ints, position 3f at 0, uv 2f
	 * at 3, colour at 5, normal at 6 -- without running the quad split, because the writer has
	 * already produced whatever shape this sink was begun in.
	 *
	 * <p>{@code off*} is the Tessellator offset the writer baked into those positions; it is removed
	 * here so the sink can apply its own, which is the same value read from its own copy.
	 *
	 * <p>{@code addedVertexCount} is deliberately left alone: it is the split's phase counter, and
	 * vertices that did not go through the split must not shift the phase of ones that do.
	 */
	public void appendPacked(int[] src, int from, int count, double offX, double offY, double offZ) {
		for (int i = 0; i < count; i++) {
			int p = from + i * STRIDE_INTS;
			this.u = Float.intBitsToFloat(src[p + 3]);
			this.v = Float.intBitsToFloat(src[p + 4]);
			this.color = src[p + 5];
			this.normal = src[p + 6];
			store(Float.intBitsToFloat(src[p]) - offX,
				Float.intBitsToFloat(src[p + 1]) - offY,
				Float.intBitsToFloat(src[p + 2]) - offZ);
		}
	}

	/** The write itself, shared by the Tessellator path and {@link #appendPacked}. */
	private void store(double x, double y, double z) {
		int stride = this.strideInts;
		grow(stride);
		this.buf[this.pos + 0] = Float.floatToRawIntBits((float) ((x + this.offX) * this.scale + this.biasX));
		this.buf[this.pos + 1] = Float.floatToRawIntBits((float) ((y + this.offY) * this.scale + this.biasY));
		this.buf[this.pos + 2] = Float.floatToRawIntBits((float) ((z + this.offZ) * this.scale + this.biasZ));
		if (this.compact) {
			// Two unorm16s in one int. A Unorm16x2 attribute reads u from the LOWER address, and
			// this int reaches memory in the machine's own byte order, so u belongs in the low half
			// on a little-endian machine and the high half on a big-endian one. Same dance as
			// color() above, for the same reason.
			int packedU = TerrainVertex.packUv(this.u);
			int packedV = TerrainVertex.packUv(this.v);
			this.buf[this.pos + 3] = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
				? packedU | packedV << 16
				: packedV | packedU << 16;
			this.buf[this.pos + 4] = this.color;
		} else {
			this.buf[this.pos + 3] = Float.floatToRawIntBits(this.u);
			this.buf[this.pos + 4] = Float.floatToRawIntBits(this.v);
			this.buf[this.pos + 5] = this.color;
			this.buf[this.pos + 6] = this.normal;
		}
		this.pos += stride;
		this.vertexCount++;
	}

	/** Bytes per vertex in the buffer {@link #copyOut} returns. */
	public int strideBytes() {
		return this.strideInts * 4;
	}

	private void grow(int needed) {
		if (this.pos + needed <= this.buf.length) {
			return;
		}
		int cap = this.buf.length;
		while (cap < this.pos + needed) {
			cap <<= 1;
		}
		int[] next = new int[cap];
		System.arraycopy(this.buf, 0, next, 0, this.pos);
		this.buf = next;
	}

	/** Copies the captured vertices into {@code dst}, which must hold {@code vertexCount()*32} bytes. */
	public void writeTo(ByteBuffer dst) {
		dst.clear();
		dst.asIntBuffer().put(this.buf, 0, this.pos);
		dst.limit(this.pos * 4);
	}

	/** Exact-size copy, so a worker can hand off its result and keep reusing this sink. */
	public int[] copyOut() {
		int[] out = new int[this.pos];
		System.arraycopy(this.buf, 0, out, 0, this.pos);
		return out;
	}

	/**
	 * Self-check: a quad's six split vertices must all carry the face's normal.
	 *
	 * <p>Guards the defect that put a hard diagonal crease down every entity and dropped item. The
	 * split re-emits v0 and v2 to turn a quad into two triangles, and it used to copy position, uv
	 * and colour but not the packed normal -- so two of the six vertices kept whatever the buffer
	 * held there previously, and the lighting term broke across the shared diagonal.
	 *
	 * <p>The buffer is deliberately dirtied with a different normal first. Without that, a
	 * zero-filled fresh buffer can leave the stale slots reading as a plausible value and the test
	 * passes while broken; with it, the omission shows up as the wrong normal word directly.
	 */
	public static void main(String[] args) {
		VertexSink sink = new VertexSink();
		int failures = 0;

		// Dirty the buffer: a full quad under a DIFFERENT normal, so the slots the second quad's
		// duplicated vertices land on already hold something recognisable and wrong.
		sink.begin(0, 0, 0, 1.0, 0, 0, 0);
		sink.normal(0.0F, 0.0F, 1.0F);
		for (int i = 0; i < 4; i++) {
			sink.vertex(i, 0, 0);
		}
		int stale = sink.copyOut()[6];

		// The real quad: one face normal, four corners, as TexturedQuad.draw emits it.
		sink.begin(0, 0, 0, 1.0, 0, 0, 0);
		sink.normal(0.0F, 1.0F, 0.0F);
		for (int i = 0; i < 4; i++) {
			sink.vertex(i, 0, 0);
		}

		int[] data = sink.copyOut();
		if (sink.vertexCount() != 6) {
			System.out.println("FAIL: a quad must split into 6 vertices, got " + sink.vertexCount());
			failures++;
		}
		int face = data[6];
		for (int v = 0; v < 6; v++) {
			int got = data[v * STRIDE_INTS + 6];
			if (got != face) {
				System.out.println("FAIL: vertex " + v + " normal 0x" + Integer.toHexString(got)
					+ ", expected the face normal 0x" + Integer.toHexString(face)
					+ (got == stale ? " (it kept the previous batch's normal)" : ""));
				failures++;
			}
		}

		failures += checkAppendPacked();
		failures += checkQuadMode();
		failures += checkCompactPacking();
		failures += checkTileBoundaries();

		if (failures > 0) {
			System.out.println("VertexSink self-check FAILED (" + failures + ")");
			System.exit(1);
		}
		System.out.println("VertexSink self-check OK: quad split preserves the face normal"
			+ " across all 6 vertices");
	}

	/**
	 * A quad handed over as raw Tessellator words survives, and does NOT get split a second time.
	 *
	 * <p>This is the path a content API's direct buffer writes take (see {@link RetroTessellator}).
	 * Two things can silently ruin it: running the quad split over vertices the writer already split,
	 * which turns six into nine and shears every face; and forgetting that the writer baked the
	 * Tessellator's offset into the positions, which puts the geometry a section away from where it
	 * belongs. Both are checked, with an offset and a bias that are non-zero and different from each
	 * other so neither can cancel out.
	 */
	private static int checkAppendPacked() {
		VertexSink sink = new VertexSink();
		sink.begin(10.0, 20.0, 30.0, 2.0, 1.0, 2.0, 3.0);

		// One quad as the writer leaves it: four vertices, 8 ints each, positions already carrying
		// the Tessellator's own offset (7, 8, 9).
		int[] packed = new int[4 * STRIDE_INTS];
		for (int i = 0; i < 4; i++) {
			int p = i * STRIDE_INTS;
			packed[p] = Float.floatToRawIntBits(i + 7.0F);
			packed[p + 1] = Float.floatToRawIntBits(8.0F);
			packed[p + 2] = Float.floatToRawIntBits(9.0F);
			packed[p + 3] = Float.floatToRawIntBits(0.25F);
			packed[p + 4] = Float.floatToRawIntBits(0.75F);
			packed[p + 5] = 0x11223344;
			packed[p + 6] = 0x00556677;
		}
		sink.appendPacked(packed, 0, 4, 7.0, 8.0, 9.0);

		int failures = 0;
		if (sink.vertexCount() != 4) {
			System.out.println("FAIL: appendPacked must not re-split -- 4 vertices in, "
				+ sink.vertexCount() + " out");
			failures++;
		}
		int[] out = sink.copyOut();
		int stride = out.length / Math.max(1, sink.vertexCount());
		for (int i = 0; i < sink.vertexCount(); i++) {
			// The writer's offset is removed and the sink's own applied: (i + 7 - 7 + 10) * 2 + 1.
			float x = Float.intBitsToFloat(out[i * stride]);
			float want = (i + 10.0F) * 2.0F + 1.0F;
			if (x != want) {
				System.out.println("FAIL: appendPacked vertex " + i + " x is " + x + ", expected "
					+ want + " -- the Tessellator offset was counted twice or not at all");
				failures++;
			}
		}
		if (!TerrainVertex.compact() && sink.vertexCount() == 4) {
			if (out[5] != 0x11223344 || out[6] != 0x00556677) {
				System.out.println("FAIL: appendPacked lost the colour or normal word");
				failures++;
			}
		}
		if (failures == 0) {
			System.out.println("VertexSink appendPacked OK: raw Tessellator words keep their shape,"
				+ " and the writer's offset is not applied twice");
		}
		return failures;
	}

	/**
	 * With the split off, a quad is four vertices and nothing is duplicated.
	 *
	 * <p>The count is the whole assertion. Everything downstream -- the arena's adjacency merge, the
	 * index count, the base vertex -- is derived from it, and a sink that quietly kept splitting
	 * would produce a buffer that draws as sheared triangles rather than one that fails to draw.
	 */
	private static int checkQuadMode() {
		QuadVertices.select(true);
		try {
			VertexSink sink = new VertexSink();
			sink.begin(0, 0, 0, 1.0, 0, 0, 0);
			for (int i = 0; i < 8; i++) {
				sink.vertex(i, 0, 0);
			}
			if (sink.vertexCount() != 8) {
				System.out.println("FAIL: indexed quads must store 4 vertices per quad, two quads"
					+ " gave " + sink.vertexCount());
				return 1;
			}
			System.out.println("VertexSink quad mode OK: two quads stored as 8 vertices, not 12");
			return 0;
		} finally {
			QuadVertices.select(false);
		}
	}

	/**
	 * A packed tile edge still reads as its OWN tile once the shader divides it up.
	 *
	 * <p>The one property the whole compact texture coordinate rests on, and the one that was
	 * quietly false: {@code terrain.wgsl} picks a tile with {@code floor(uv / tileSize)}, so a tile
	 * edge that decodes even a fraction below the boundary puts the outermost sliver of that tile in
	 * the previous one and samples a neighbouring texture. On beta's water -- tile 13 across, 12
	 * down, magenta placeholder on every side -- that is a white speck along the edge of a surface
	 * quad, appearing only when a pixel centre happens to land in a band 4e-5 of a block wide.
	 *
	 * <p>Checked at the boundary itself rather than at some chosen distance inside it, because the
	 * boundary is the worst case and a fragment is always strictly inside one. Both atlas shapes
	 * that exist: vanilla's 256 sheet and a 512 one, where the tile is a different fraction of it.
	 */
	private static int checkTileBoundaries() {
		TerrainVertex.select(true);
		try {
			if (!TerrainVertex.compact()) {
				System.out.println("VertexSink tile boundaries SKIPPED"
					+ " (-Dretroperf.compactTerrain=false)");
				return 0;
			}
			int failures = 0;
			for (int texels : new int[] { 256, 512 }) {
				float tileSize = 16.0F / TerrainVertex.shaderAtlasTexels(texels);
				for (int tile = 0; tile < texels / 16; tile++) {
					float exact = tile * 16.0F / texels;
					// Exactly what a Unorm16x2 attribute delivers, and what the shader does with it.
					float decoded = TerrainVertex.packUv(exact) / 65535.0F;
					int got = (int) Math.floor(decoded / tileSize);
					if (got != tile) {
						System.out.println("FAIL: on a " + texels + " atlas the edge of tile " + tile
							+ " (" + exact + ") decodes to " + decoded + ", which the shader reads"
							+ " as tile " + got);
						failures++;
					}
				}
			}
			if (failures == 0) {
				System.out.println("VertexSink tile boundaries OK: every tile edge decodes into its"
					+ " own tile on a 256 and a 512 atlas");
			}
			return failures;
		} finally {
			TerrainVertex.select(false);
		}
	}

	/**
	 * The compact packing, checked by reading the bytes back the way the GPU will.
	 *
	 * <p>Not "does it produce 20 bytes" -- that would pass with the fields in any order. The texture
	 * coordinate is recovered through the same unorm16 conversion the vertex format applies, at the
	 * byte offsets the pipeline declares, so a swapped u/v, a wrong offset or the wrong endianness
	 * each show up as a coordinate that is not the one that went in.
	 *
	 * <p>Deliberately asymmetric coordinates: u and v equal would make a swap invisible, which is
	 * the mistake that makes this class of test worthless.
	 */
	private static int checkCompactPacking() {
		TerrainVertex.select(true);
		QuadVertices.select(true);
		try {
			if (!TerrainVertex.compact()) {
				System.out.println("VertexSink compact packing SKIPPED"
					+ " (-Dretroperf.compactTerrain=false)");
				return 0;
			}
			VertexSink sink = new VertexSink();
			sink.begin(0, 0, 0, 1.0, 0, 0, 0);
			sink.color(10, 20, 30, 40);
			sink.texture(0.25, 0.75);
			sink.vertex(1.5, 2.5, 3.5);

			int failures = 0;
			if (sink.strideBytes() != TerrainVertex.COMPACT_STRIDE) {
				System.out.println("FAIL: compact stride is " + sink.strideBytes()
					+ ", expected " + TerrainVertex.COMPACT_STRIDE);
				failures++;
			}

			ByteBuffer bytes = ByteBuffer
				.allocateDirect(sink.vertexCount() * sink.strideBytes())
				.order(ByteOrder.nativeOrder());
			sink.writeTo(bytes);

			float x = bytes.getFloat(0);
			float y = bytes.getFloat(4);
			float z = bytes.getFloat(8);
			// Exactly how a Unorm16x2 attribute reads it: two unsigned 16-bit values, u at the
			// lower address, each divided by 65535.
			float u = (bytes.getShort(12) & 0xFFFF) / 65535.0F;
			float v = (bytes.getShort(14) & 0xFFFF) / 65535.0F;
			int color = bytes.getInt(16);

			if (x != 1.5F || y != 2.5F || z != 3.5F) {
				System.out.println("FAIL: compact position (" + x + ", " + y + ", " + z + ")");
				failures++;
			}
			// One part in 65535 is the representable step; anything wrong is wrong by far more.
			if (Math.abs(u - 0.25F) > 1e-4 || Math.abs(v - 0.75F) > 1e-4) {
				System.out.println("FAIL: compact uv (" + u + ", " + v + "), expected (0.25, 0.75)"
					+ (Math.abs(u - 0.75F) < 1e-4 ? " -- the halves are swapped" : ""));
				failures++;
			}
			int expected = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
				? 40 << 24 | 30 << 16 | 20 << 8 | 10
				: 10 << 24 | 20 << 16 | 30 << 8 | 40;
			if (color != expected) {
				System.out.println("FAIL: compact colour 0x" + Integer.toHexString(color)
					+ ", expected 0x" + Integer.toHexString(expected));
				failures++;
			}
			if (failures == 0) {
				System.out.println("VertexSink compact packing OK: 20 bytes, uv round-trips through"
					+ " unorm16 at the offsets the pipeline declares");
			}
			return failures;
		} finally {
			TerrainVertex.select(false);
			QuadVertices.select(false);
		}
	}
}
