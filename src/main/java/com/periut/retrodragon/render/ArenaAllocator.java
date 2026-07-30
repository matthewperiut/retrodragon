package com.periut.retrodragon.render;

import java.util.Map;
import java.util.TreeMap;

/**
 * Exact-fit suballocation with coalescing, in units of vertices.
 *
 * <p>Pure arithmetic, no GPU: the first shared terrain arena failed on allocator behaviour rather
 * than on anything graphical -- it grew to 512 MB and produced allocations that could never be
 * adjacent -- and none of that is observable through a rendering test. Keeping the policy here means
 * it can be checked directly. See {@link #main}.
 *
 * <h2>Why exact fit, and why coalescing</h2>
 *
 * The point of a shared arena is that neighbouring sections become ONE draw, which requires the
 * previous allocation to end exactly where the next begins. Rounding sizes up to a power of two --
 * the obvious way to get good reuse -- guarantees the opposite: a 300-vertex section in a 512-vertex
 * slot leaves a 212-vertex hole, so no two allocations are ever adjacent and nothing merges. Exact
 * fit is not an optimisation here, it is the whole mechanism.
 *
 * <p>Exact fit alone fragments, though: sections are re-meshed constantly and rarely come back the
 * same size, so a naive free list accumulates unusable holes and the arena grows without bound.
 * Adjacent free blocks are therefore merged on release, which is what keeps the high-water mark
 * near the live total instead of climbing forever.
 *
 * <h2>The cap is not optional</h2>
 *
 * Past the device's {@code maxBufferSize}, Dawn returns an INVALID buffer rather than failing, and
 * every later write and draw against it fails validation with nothing pointing at the cause. The
 * allocator refuses instead, and the caller falls back to a buffer of its own.
 */
public final class ArenaAllocator {
	/** Sentinel for "no room"; callers fall back to a private buffer. */
	public static final int NO_SPACE = -1;

	/** Free blocks by start offset, so neighbours are found in O(log n) and merged on release. */
	private final TreeMap<Integer, Integer> freeByOffset = new TreeMap<>();

	private final int capacity;
	private int top;
	private int live;
	private int peak;

	public ArenaAllocator(int capacityVertices) {
		this.capacity = capacityVertices;
	}

	/**
	 * @return the first vertex of the allocation, or {@link #NO_SPACE}
	 */
	public int allocate(int vertices) {
		if (vertices <= 0) {
			return NO_SPACE;
		}
		// Best fit among the holes: the tightest hole leaves the least unusable remainder, which
		// matters more than allocation speed when the whole point is to stay compact.
		int bestOffset = NO_SPACE;
		int bestSize = Integer.MAX_VALUE;
		for (Map.Entry<Integer, Integer> hole : freeByOffset.entrySet()) {
			int size = hole.getValue();
			if (size >= vertices && size < bestSize) {
				bestOffset = hole.getKey();
				bestSize = size;
				if (size == vertices) {
					break;
				}
			}
		}
		if (bestOffset != NO_SPACE) {
			freeByOffset.remove(bestOffset);
			int remainder = bestSize - vertices;
			if (remainder > 0) {
				freeByOffset.put(bestOffset + vertices, remainder);
			}
			live += vertices;
			return bestOffset;
		}

		if (top + vertices > capacity) {
			return NO_SPACE;
		}
		int at = top;
		top += vertices;
		live += vertices;
		peak = Math.max(peak, top);
		return at;
	}

	/** Returns a block, merging it with any free neighbour on either side. */
	public void release(int at, int vertices) {
		if (at < 0 || vertices <= 0) {
			return;
		}
		live -= vertices;
		int start = at;
		int size = vertices;

		Map.Entry<Integer, Integer> before = freeByOffset.floorEntry(start);
		if (before != null && before.getKey() + before.getValue() == start) {
			start = before.getKey();
			size += before.getValue();
			freeByOffset.remove(before.getKey());
		}
		Map.Entry<Integer, Integer> after = freeByOffset.ceilingEntry(start + size);
		if (after != null && after.getKey() == start + size) {
			size += after.getValue();
			freeByOffset.remove(after.getKey());
		}

		// A block that reaches the high-water mark is given back outright rather than kept as a
		// hole, so a world that unloads shrinks the arena's working set instead of just its
		// occupancy.
		if (start + size == top) {
			top = start;
			return;
		}
		freeByOffset.put(start, size);
	}

	/** Vertices currently allocated. */
	public int live() {
		return live;
	}

	/** Highest offset ever reached; what the GPU buffer actually has to cover. */
	public int peak() {
		return peak;
	}

	public int top() {
		return top;
	}

	public int holes() {
		return freeByOffset.size();
	}

	// --- self-check ---------------------------------------------------------------------------

	/** {@code ./gradlew arenaTest} */
	public static void main(String[] args) {
		// 1. Consecutive allocations are ADJACENT. This is the property the whole design exists for:
		//    without it no two sections ever merge into one draw.
		ArenaAllocator a = new ArenaAllocator(1 << 20);
		int first = a.allocate(300);
		int second = a.allocate(1700);
		int third = a.allocate(64);
		check(first == 0, "first allocation starts at 0");
		check(second == first + 300, "allocations must be adjacent, got " + second + " after 300");
		check(third == second + 1700, "allocations must stay adjacent");

		// 2. Freeing the middle and then a neighbour merges them into one hole, rather than leaving
		//    two that nothing fits in.
		a.release(second, 1700);
		check(a.holes() == 1, "one hole after freeing the middle, got " + a.holes());
		a.release(first, 300);
		check(a.holes() == 1, "adjacent holes must coalesce, got " + a.holes());
		int rejoined = a.allocate(2000);
		check(rejoined == 0, "the coalesced hole must satisfy 300+1700, got " + rejoined);

		// 3. No allocation ever overlaps another. Checked by painting.
		ArenaAllocator b = new ArenaAllocator(1 << 16);
		int[] painted = new int[1 << 16];
		java.util.List<int[]> held = new java.util.ArrayList<>();
		long seed = 12345L;
		for (int step = 0; step < 4000; step++) {
			seed = seed * 6364136223846793005L + 1442695040888963407L;
			int roll = (int) (seed >>> 33);
			if (held.size() > 8 && roll % 3 == 0) {
				int[] block = held.remove(roll % held.size());
				for (int i = block[0]; i < block[0] + block[1]; i++) {
					painted[i] = 0;
				}
				b.release(block[0], block[1]);
			} else {
				int size = 64 + roll % 900;
				int at = b.allocate(size);
				if (at == NO_SPACE) {
					continue;
				}
				for (int i = at; i < at + size; i++) {
					check(painted[i] == 0, "allocation overlaps a live block at " + i);
					painted[i] = 1;
				}
				held.add(new int[] { at, size });
			}
		}

		// 4. Fragmentation stays bounded. An exact-fit allocator with no coalescing climbs without
		//    limit under this churn; the check is that the high-water mark stays within a small
		//    factor of what is actually live.
		int liveNow = b.live();
		double overhead = liveNow == 0 ? 0 : (double) b.peak() / liveNow;
		check(overhead < 3.0,
			"fragmentation should stay bounded, peak " + b.peak() + " vs live " + liveNow
				+ " (" + String.format("%.2f", overhead) + "x)");

		// 5. THE POINT OF ALL THIS: how many draws would the live blocks collapse into?
		//
		//    A draw can cover several sections when their allocations are contiguous, so sorting the
		//    live blocks by offset and counting contiguous runs is exactly the draw count this
		//    allocator would produce for a frame where everything is visible. Reported rather than
		//    merely asserted, because the number is the whole justification for a shared arena -- and
		//    the previous power-of-two design would score 1.00 blocks per run, i.e. no merging at all.
		held.sort((p, q) -> Integer.compare(p[0], q[0]));
		int runs = 0;
		int end = Integer.MIN_VALUE;
		for (int[] block : held) {
			if (block[0] != end) {
				runs++;
			}
			end = block[0] + block[1];
		}
		double perRun = held.isEmpty() ? 0 : (double) held.size() / runs;
		check(perRun > 2.0,
			"contiguous runs should cover several blocks each, got "
				+ String.format("%.2f", perRun) + " -- with no merging this is 1.00 and the arena"
				+ " buys nothing over a buffer per section");

		// 6. The cap is refused rather than exceeded -- Dawn would hand back an invalid buffer.
		ArenaAllocator small = new ArenaAllocator(1000);
		check(small.allocate(600) == 0, "first fits");
		check(small.allocate(600) == NO_SPACE, "a request past the cap must be refused, not granted");

		System.out.println("ArenaAllocator self-check OK: " + held.size() + " blocks collapse to "
			+ runs + " draws (" + String.format("%.2f", perRun) + " per draw), peak/live "
			+ String.format("%.2f", overhead) + "x, " + b.holes() + " holes");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
