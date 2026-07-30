package com.periut.retrodragon.render;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import com.periut.retrodragon.Config;

import net.minecraft.client.render.chunk.ChunkBuilder;

/**
 * Breadth-first visibility over the section graph: terrain behind hills is never drawn.
 *
 * Starts at the camera's section and walks outward, only stepping through a section when its
 * connectivity mask says sight can enter one face and leave by another. Anything the walk never
 * reaches is occluded.
 *
 * Two properties keep this conservative -- it may draw something hidden, but never hides something
 * visible: sections without a computed mask are treated as fully open, and traversal is restricted
 * to directions that move *away* from the camera on each axis, which is exactly how a straight
 * sight line behaves.
 *
 * The result is cached and only recomputed when something that could change it changes: the camera
 * crossing into a new section, or a section being rebuilt/repositioned.
 */
public final class OcclusionCuller {
	private static final Map<Long, ChunkBuilder> grid = new HashMap<>();
	private static final java.util.HashSet<Long> reachable = new java.util.HashSet<>();
	/** Section -> bitmask of entry directions already expanded, so each direction is walked once. */
	private static final Map<Long, Integer> expandedFaces = new HashMap<>();
	private static final ArrayDeque<long[]> queue = new ArrayDeque<>();

	private static long cachedCameraSection = Long.MIN_VALUE;
	private static int cachedBuildCount = -1;
	private static int cachedSectionCount = -1;
	private static boolean valid;

	private static int culled;
	private static int total;

	private OcclusionCuller() {
	}

	public static void update(ChunkBuilder[] sections, double camX, double camY, double camZ) {
		if (!Config.OCCLUSION) {
			valid = false;
			return;
		}
		int camSx = Math.floorDiv((int) Math.floor(camX), SectionVisibility.SIZE);
		int camSy = Math.floorDiv((int) Math.floor(camY), SectionVisibility.SIZE);
		int camSz = Math.floorDiv((int) Math.floor(camZ), SectionVisibility.SIZE);
		long camKey = key(camSx, camSy, camSz);

		int builds = ChunkBuilder.chunkUpdates;
		if (valid && camKey == cachedCameraSection && builds == cachedBuildCount
				&& sections.length == cachedSectionCount) {
			return;
		}
		cachedCameraSection = camKey;
		cachedBuildCount = builds;
		cachedSectionCount = sections.length;

		grid.clear();
		for (ChunkBuilder section : sections) {
			grid.put(key(Math.floorDiv(section.x, SectionVisibility.SIZE),
				Math.floorDiv(section.y, SectionVisibility.SIZE),
				Math.floorDiv(section.z, SectionVisibility.SIZE)), section);
		}

		walk(camSx, camSy, camSz,
			section -> grid.containsKey(section),
			section -> {
				ChunkBuilder builder = grid.get(section);
				return builder == null
					? SectionVisibility.ALL_CONNECTED
					: ((RetroSection) builder).retroperf$visibility();
			});
		valid = true;
	}

	/**
	 * The graph walk itself, over an abstract lattice.
	 *
	 * <p>Separated from the game's data so it can be exercised directly -- see {@link #main}. The
	 * culler's whole risk is over-culling, and over-culling is invisible in a screenshot unless you
	 * already know what should have been there; a synthetic graph with a known answer is the only way
	 * to check it that does not involve staring at terrain.
	 *
	 * <p>Results land in {@link #reachable}.
	 *
	 * @param exists     whether a section is in the lattice at all
	 * @param visibility that section's connectivity mask
	 */
	static void walk(int camSx, int camSy, int camSz,
			java.util.function.LongPredicate exists,
			java.util.function.LongUnaryOperator visibility) {
		reachable.clear();
		expandedFaces.clear();
		queue.clear();
		// Camera section: reachable, and entered from no particular face.
		reachable.add(key(camSx, camSy, camSz));
		queue.add(new long[] { camSx, camSy, camSz, -1 });

		while (!queue.isEmpty()) {
			long[] node = queue.poll();
			int sx = (int) node[0];
			int sy = (int) node[1];
			int sz = (int) node[2];
			int entry = (int) node[3];

			long mask = visibility.applyAsLong(key(sx, sy, sz));

			for (int face = 0; face < 6; face++) {
				// Only expand through a face sight could actually leave by.
				if (entry >= 0 && !SectionVisibility.connected(mask, opposite(entry), face)) {
					continue;
				}
				int nx = sx + dx(face);
				int ny = sy + dy(face);
				int nz = sz + dz(face);
				// Monotonic outward traversal: never step back toward the camera on any axis.
				if (movesTowardCamera(face, sx, sy, sz, camSx, camSy, camSz)) {
					continue;
				}
				long neighbour = key(nx, ny, nz);
				if (!exists.test(neighbour)) {
					continue;
				}
				reachable.add(neighbour);
				// Re-expand a section once per DIRECTION it is entered from. Marking it visited on
				// first arrival loses sight lines that enter from another side and could leave
				// through faces the first path could not -- that under-approximates reachability and
				// culls terrain that is actually visible.
				int bit = 1 << face;
				int seen = expandedFaces.getOrDefault(neighbour, 0);
				if ((seen & bit) != 0) {
					continue;
				}
				expandedFaces.put(neighbour, seen | bit);
				queue.add(new long[] { nx, ny, nz, face });
			}
		}
	}

	/** Conservative: anything not in the lattice, or with culling off, is drawn. */
	public static boolean isVisible(ChunkBuilder section) {
		if (!valid) {
			return true;
		}
		return reachable.contains(key(
			Math.floorDiv(section.x, SectionVisibility.SIZE),
			Math.floorDiv(section.y, SectionVisibility.SIZE),
			Math.floorDiv(section.z, SectionVisibility.SIZE)));
	}

	public static void noteCulled(int culledSections, int totalSections) {
		culled = culledSections;
		total = totalSections;
	}

	public static int lastCulled() {
		return culled;
	}

	public static int lastTotal() {
		return total;
	}

	/**
	 * True when stepping through {@code face} would move back toward the camera on that axis.
	 *
	 * A straight sight line advances weakly monotonically away from the eye on every axis, so a
	 * path that doubles back cannot be a real line of sight and is pruned. Moving DOWN heads toward
	 * the camera precisely when this section is already above it, and so on per axis.
	 */
	private static boolean movesTowardCamera(int face, int sx, int sy, int sz, int cx, int cy, int cz) {
		return switch (face) {
			case SectionVisibility.DOWN -> sy > cy;
			case SectionVisibility.UP -> sy < cy;
			case SectionVisibility.NORTH -> sz > cz;
			case SectionVisibility.SOUTH -> sz < cz;
			case SectionVisibility.WEST -> sx > cx;
			case SectionVisibility.EAST -> sx < cx;
			default -> false;
		};
	}

	private static int opposite(int face) {
		return switch (face) {
			case SectionVisibility.DOWN -> SectionVisibility.UP;
			case SectionVisibility.UP -> SectionVisibility.DOWN;
			case SectionVisibility.NORTH -> SectionVisibility.SOUTH;
			case SectionVisibility.SOUTH -> SectionVisibility.NORTH;
			case SectionVisibility.WEST -> SectionVisibility.EAST;
			default -> SectionVisibility.WEST;
		};
	}

	private static int dx(int face) {
		return face == SectionVisibility.WEST ? -1 : face == SectionVisibility.EAST ? 1 : 0;
	}

	private static int dy(int face) {
		return face == SectionVisibility.DOWN ? -1 : face == SectionVisibility.UP ? 1 : 0;
	}

	private static int dz(int face) {
		return face == SectionVisibility.NORTH ? -1 : face == SectionVisibility.SOUTH ? 1 : 0;
	}

	private static long key(int x, int y, int z) {
		return (x & 0x3FFFFFL) << 42 | (y & 0xFFFL) << 30 | z & 0x3FFFFFL;
	}

	// --- self-check ---------------------------------------------------------------------------

	/**
	 * {@code java com.periut.retrodragon.render.OcclusionCuller}
	 *
	 * <p>The only failure mode that matters here is OVER-culling -- hiding something that should be
	 * visible. It is also the one you cannot see: a missing chunk of hillside looks like terrain that
	 * was never there. So the checks are built on synthetic lattices whose correct answer is known by
	 * construction rather than by looking.
	 */
	public static void main(String[] args) {
		// A BOUNDED lattice. The walk's only termination condition is running out of sections that
		// exist -- in the game that is the loaded set, but a predicate that says "yes" to everything
		// expands until it exhausts the heap.
		int radius = 4;
		java.util.function.LongPredicate all = section -> within(section, radius);

		// 1. Everything open: every section the monotonic rule allows must be reachable. The rule is
		//    that a sight line never doubles back, so the reachable set is the octant-wise cone
		//    around the camera -- which for an open lattice is everything.
		walk(0, 0, 0, all, section -> SectionVisibility.ALL_CONNECTED);
		int missing = 0;
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					if (!reachable.contains(key(x, y, z))) {
						missing++;
					}
				}
			}
		}
		check(missing == 0, "an open lattice must be fully reachable, " + missing + " sections culled");

		// 2. A solid section blocks sight THROUGH it but is itself visible -- you can see the wall.
		long wall = key(2, 0, 0);
		walk(0, 0, 0, all, section -> section == wall ? 0L : SectionVisibility.ALL_CONNECTED);
		check(reachable.contains(wall), "a solid section must still be drawn; you can see the wall");
		check(!reachable.contains(key(3, 0, 0)),
			"a solid section must block the section directly behind it");

		// 3. The regression this culler was disabled for. A section is reached FIRST from a direction
		//    it cannot be left by, and only later from one it can. Marking it visited on first
		//    arrival throws away the second path and culls everything beyond it.
		//
		//    The lattice has to be a corridor, not an open field: in an open lattice the target is
		//    reachable half a dozen other ways and the check passes whether the bug is present or
		//    not. Everything here is solid except three open sections and the corner itself, so the
		//    ONLY route to (2,0,2) is through the corner leaving southward.
		//
		//        (0,0,0) camera ──east──▶ (1,0,0) ──east──▶ (2,0,0)
		//                                    │                 │
		//                                  south             south
		//                                    ▼                 ▼
		//                                 (1,0,1) ──east──▶ (2,0,1) corner: NORTH↔SOUTH only
		//                                                        │
		//                                                      south
		//                                                        ▼
		//                                                     (2,0,2) target
		//
		//    Breadth-first, the corner is reached from (1,0,1) heading EAST before it is reached from
		//    (2,0,0) heading SOUTH. The first entry is through its WEST face, which connects to
		//    nothing; the second is through its NORTH face, which reaches SOUTH.
		long corner = key(2, 0, 1);
		long northSouthOnly = pair(SectionVisibility.NORTH, SectionVisibility.SOUTH);
		java.util.Set<Long> corridor = java.util.Set.of(key(1, 0, 0), key(1, 0, 1), key(2, 0, 0));
		walk(0, 0, 0, all, section -> {
			if (section == corner) {
				return northSouthOnly;
			}
			return corridor.contains(section) ? SectionVisibility.ALL_CONNECTED : 0L;
		});
		check(reachable.contains(key(2, 0, 2)),
			"a section reachable only by entering through a SECOND face must not be culled --"
				+ " this is the per-entry-face regression that disabled occlusion culling");

		// 4. A section outside the loaded set is never reached, and does not stop the walk crashing
		//    into it either.
		walk(0, 0, 0, section -> within(section, radius) && section != key(1, 0, 0),
			section -> SectionVisibility.ALL_CONNECTED);
		check(!reachable.contains(key(1, 0, 0)), "a section outside the lattice is not reachable");

		System.out.println("OcclusionCuller self-check OK");
	}

	/** Decodes a key and asks whether it is inside the test lattice. */
	private static boolean within(long section, int radius) {
		int x = signed((int) (section >>> 42 & 0x3FFFFFL), 22);
		int y = signed((int) (section >>> 30 & 0xFFFL), 12);
		int z = signed((int) (section & 0x3FFFFFL), 22);
		return Math.abs(x) <= radius && Math.abs(y) <= radius && Math.abs(z) <= radius;
	}

	/** Sign-extends a field of {@code bits} width, since {@link #key} stores coordinates truncated. */
	private static int signed(int value, int bits) {
		int shift = 32 - bits;
		return value << shift >> shift;
	}

	/** A mask connecting exactly one pair of faces, for the self-check. */
	private static long pair(int a, int b) {
		return SectionVisibility.connectionBit(a, b) | SectionVisibility.connectionBit(b, a);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
	}
}
