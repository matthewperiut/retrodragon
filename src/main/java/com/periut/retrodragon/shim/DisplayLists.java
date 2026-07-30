package com.periut.retrodragon.shim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * GL display lists, recorded as geometry rather than as commands.
 *
 * <p>Beta compiles a display list per chunk layer and one each for the sky, the stars and the cloud
 * mesh -- geometry that does not change between frames, replayed with {@code glCallList}. WebGPU has
 * no equivalent, and there is nothing to emulate at the command level: what beta puts inside a list
 * is always {@code Tessellator.draw()} calls and nothing else.
 *
 * <p>So a list stores the vertex bytes, and the STATE is taken fresh at call time. That is both
 * simpler and more faithful than replaying recorded state would be: GL applies the modelview in
 * effect at {@code glCallList}, not at {@code glNewList}, and beta relies on exactly that -- the same
 * chunk list is called once per frame from a different camera position every time.
 *
 * <p>Lists are recorded in {@code GL_COMPILE} mode, which is the only mode beta uses, so nothing
 * draws while one is open.
 */
public final class DisplayLists {
	private static final Map<Integer, Recorded> LISTS = new HashMap<>();
	private static int nextName = 1;
	private static Recorded recording;

	private DisplayLists() {
	}

	private static final class Recorded {
		ByteBuffer vertices = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
		int vertexCount;
		int[] counts = new int[8];
		int[] modes = new int[8];
		int batchCount;

		/**
		 * Which vertex attributes each batch actually WROTE.
		 *
		 * <p>Recorded rather than assumed, and that distinction is visible on screen: beta's sky dome
		 * is built into a display list from a Tessellator with {@code hasColor} false -- the colour
		 * comes from {@code glColor3f} outside the list -- so replaying it as though every vertex
		 * carried a colour makes the shader read whatever bytes happened to be in those slots. The
		 * sky comes out in the previous batch's colours instead of the sky's.
		 */
		boolean[] hasColor = new boolean[8];
		boolean[] hasNormals = new boolean[8];
		boolean[] hasTexture = new boolean[8];

		void add(ByteBuffer source, int count, int mode, boolean color, boolean normals,
				boolean texture) {
			int bytes = count * DrawList.STRIDE;
			int needed = vertexCount * DrawList.STRIDE + bytes;
			if (needed > vertices.capacity()) {
				int capacity = vertices.capacity();
				while (capacity < needed) {
					capacity *= 2;
				}
				ByteBuffer grown = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
				vertices.position(0).limit(vertexCount * DrawList.STRIDE);
				grown.put(vertices);
				grown.clear();
				vertices = grown;
			}
			int sourcePosition = source.position();
			int sourceLimit = source.limit();
			source.limit(sourcePosition + bytes);
			vertices.clear().position(vertexCount * DrawList.STRIDE);
			vertices.put(source);
			vertices.clear();
			source.position(sourcePosition).limit(sourceLimit);

			if (batchCount == counts.length) {
				counts = java.util.Arrays.copyOf(counts, batchCount * 2);
				modes = java.util.Arrays.copyOf(modes, batchCount * 2);
				hasColor = java.util.Arrays.copyOf(hasColor, batchCount * 2);
				hasNormals = java.util.Arrays.copyOf(hasNormals, batchCount * 2);
				hasTexture = java.util.Arrays.copyOf(hasTexture, batchCount * 2);
			}
			counts[batchCount] = count;
			modes[batchCount] = mode;
			hasColor[batchCount] = color;
			hasNormals[batchCount] = normals;
			hasTexture[batchCount] = texture;
			batchCount++;
			vertexCount += count;
		}
	}

	/**
	 * Reserves {@code count} consecutive names, as {@code glGenLists} does.
	 *
	 * <p>Consecutive matters: beta reserves one block for the whole world renderer and then indexes
	 * into it arithmetically rather than storing each name.
	 */
	public static synchronized int gen(int count) {
		int first = nextName;
		nextName += Math.max(1, count);
		return first;
	}

	public static synchronized void begin(int list) {
		recording = new Recorded();
		LISTS.put(list, recording);
	}

	public static synchronized void end() {
		recording = null;
	}

	/** True while a list is open, in which case captured geometry belongs to it and not the frame. */
	public static boolean isRecording() {
		return recording != null;
	}

	public static synchronized void record(ByteBuffer source, int vertexCount, int glMode,
			boolean hasColor, boolean hasNormals, boolean hasTexture) {
		if (recording != null && vertexCount > 0) {
			recording.add(source, vertexCount, glMode, hasColor, hasNormals, hasTexture);
		}
	}

	public static synchronized void delete(int list, int count) {
		for (int i = 0; i < Math.max(1, count); i++) {
			LISTS.remove(list + i);
		}
	}

	/**
	 * Replays a list into the current frame under the CURRENT state.
	 *
	 * @param sink receives each recorded batch; the caller supplies the state, which is the whole
	 *             point -- see the class comment
	 */
	public static synchronized void call(int list, Replay sink) {
		Recorded recorded = LISTS.get(list);
		if (recorded == null || recorded.batchCount == 0) {
			return;
		}
		int firstVertex = 0;
		for (int batch = 0; batch < recorded.batchCount; batch++) {
			int count = recorded.counts[batch];
			recorded.vertices.clear();
			recorded.vertices.position(firstVertex * DrawList.STRIDE);
			sink.batch(recorded.vertices, count, recorded.modes[batch],
				recorded.hasColor[batch], recorded.hasNormals[batch], recorded.hasTexture[batch]);
			firstVertex += count;
		}
		recorded.vertices.clear();
	}

	/** Receives one recorded batch during {@link #call}. */
	public interface Replay {
		void batch(ByteBuffer vertices, int vertexCount, int glMode,
			boolean hasColor, boolean hasNormals, boolean hasTexture);
	}

	public static synchronized int count() {
		return LISTS.size();
	}

	public static synchronized void clear() {
		LISTS.clear();
		recording = null;
	}
}
