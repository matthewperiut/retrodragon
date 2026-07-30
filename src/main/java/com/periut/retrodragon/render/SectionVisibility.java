package com.periut.retrodragon.render;

/**
 * Per-section face-to-face connectivity, computed during meshing.
 *
 * Answers "can sight pass through this section from face A and out of face B". Solid rock has no
 * connections and blocks the traversal; open air connects everything. The occlusion BFS walks the
 * section graph using these masks, so terrain hidden behind hills is never drawn.
 *
 * Computed by labelling connected components of non-opaque cells: every component records which of
 * the 6 faces it touches, and each component contributes all pairs of the faces it touches. This is
 * a conservative over-approximation -- it can say "connected" for a path that a real sight line
 * could not follow, which only ever means drawing something unnecessarily, never culling something
 * visible.
 */
public final class SectionVisibility {
	public static final int SIZE = 16;
	/** Every face reachable from every face -- the safe answer for unknown or unbuilt sections. */
	public static final long ALL_CONNECTED = (1L << 36) - 1L;

	// Face order matches Direction indexing used by the BFS: -Y, +Y, -Z, +Z, -X, +X.
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;

	private static final int CELLS = SIZE * SIZE * SIZE;

	private SectionVisibility() {
	}

	/**
	 * @param opaque per-cell opacity, indexed {@code (y * SIZE + z) * SIZE + x}
	 * @return 36-bit mask, bit {@code in * 6 + out}
	 */
	public static long compute(boolean[] opaque) {
		int[] component = new int[CELLS];
		java.util.Arrays.fill(component, -1);
		int[] queue = new int[CELLS];
		long mask = 0L;
		int next = 1;

		for (int start = 0; start < CELLS; start++) {
			if (opaque[start] || component[start] != -1) {
				continue;
			}
			int id = next++;
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			component[start] = id;
			int faces = 0;

			while (head < tail) {
				int index = queue[head++];
				int x = index & 15;
				int z = index >> 4 & 15;
				int y = index >> 8 & 15;

				if (y == 0) {
					faces |= 1 << DOWN;
				}
				if (y == SIZE - 1) {
					faces |= 1 << UP;
				}
				if (z == 0) {
					faces |= 1 << NORTH;
				}
				if (z == SIZE - 1) {
					faces |= 1 << SOUTH;
				}
				if (x == 0) {
					faces |= 1 << WEST;
				}
				if (x == SIZE - 1) {
					faces |= 1 << EAST;
				}

				if (x > 0) {
					tail = push(opaque, component, queue, tail, index - 1, id);
				}
				if (x < SIZE - 1) {
					tail = push(opaque, component, queue, tail, index + 1, id);
				}
				if (z > 0) {
					tail = push(opaque, component, queue, tail, index - SIZE, id);
				}
				if (z < SIZE - 1) {
					tail = push(opaque, component, queue, tail, index + SIZE, id);
				}
				if (y > 0) {
					tail = push(opaque, component, queue, tail, index - SIZE * SIZE, id);
				}
				if (y < SIZE - 1) {
					tail = push(opaque, component, queue, tail, index + SIZE * SIZE, id);
				}
			}

			// This component joins every face it touches to every other face it touches.
			for (int in = 0; in < 6; in++) {
				if ((faces & 1 << in) == 0) {
					continue;
				}
				for (int out = 0; out < 6; out++) {
					if ((faces & 1 << out) != 0) {
						mask |= 1L << in * 6 + out;
					}
				}
			}
		}
		return mask;
	}

	private static int push(boolean[] opaque, int[] component, int[] queue, int tail, int index, int id) {
		if (!opaque[index] && component[index] == -1) {
			component[index] = id;
			queue[tail++] = index;
		}
		return tail;
	}

	public static boolean connected(long mask, int in, int out) {
		return (mask & connectionBit(in, out)) != 0L;
	}

	/** The single bit meaning "sight entering by {@code in} can leave by {@code out}". */
	public static long connectionBit(int in, int out) {
		return 1L << in * 6 + out;
	}
}
