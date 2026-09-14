package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.SectionMesher;
import com.periut.retrodragon.render.TerrainLight;

import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.Dimension;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The one place a terrain luminance can be intercepted, which is what makes {@link TerrainLight}
 * possible without reimplementing anyone's lighting.
 *
 * <p>Every colour any renderer puts in a terrain vertex is a tint times a blend of
 * {@code Block.getLuminance}, and that is {@code blockView.getNaturalBrightness} for every block in
 * the game, for a content API's blocks, and inside StationAPI's own smooth-lighting calculator.
 * Answering it differently for one meshing walk is therefore enough to take a whole section apart
 * into "what colour is this" and "how lit is it", with the renderer none the wiser.
 *
 * <h2>Two field reads, no method bodies</h2>
 *
 * Both answers are already expressible in beta's own arithmetic, so nothing is rewritten or
 * cancelled here -- a {@code @Redirect} on a field read is the whole of each:
 *
 * <pre>
 *   DAYLIGHT      ambientDarkness reads as 0   -- max(sky, block), the luminance at noon
 *   BLOCK_FLOOR   ambientDarkness reads as 15  -- max(sky - 15, block) is block at any sky level
 *   TINT          the brightness table reads as all ones, so every lookup returns 1.0
 * </pre>
 *
 * <p>Redirects rather than an {@code @Inject} on {@code getNaturalBrightness} because that method is
 * the hottest thing in meshing -- tens of thousands of calls per section, several per block face --
 * and a cancellable injection allocates a {@code CallbackInfoReturnable} at every one of them. A
 * field redirect is a static call that returns a value already in a register.
 *
 * <p>Redirecting the darkness also keeps every wrinkle of {@code getRawBrightness}: the slab,
 * farmland and stairs case that takes the brightest neighbour, the {@code y >= 128} sky above the
 * world, the out-of-range 15. None of it is copied here, so none of it can drift.
 *
 * <h2>The sky-light flag rides here too</h2>
 *
 * {@code Chunk.getLight(IIII)} sets a JVM-global {@code Chunk.hasSkyLight} whenever the block it
 * read has any sky light, and vanilla uses it as a per-section verdict: {@code ChunkBuilder.rebuild}
 * clears it, walks the section, and stores the result. {@code WorldRenderer} then re-lights exactly
 * those sections when the ambient darkness steps. A write-only global is fine when the only writer
 * is the render thread; it is not fine once mesh workers do it at once, so that verdict is collected
 * per-thread instead -- see {@link SectionMesher#markSkyLight()}.
 *
 * <p>Collected HERE, at the call, rather than by redirecting the store inside {@code getLight}.
 * Two reasons, and the second is the one that matters:
 *
 * <ul>
 * <li>The call is virtual, so it covers any {@code Chunk} subclass. StationAPI's flattening replaces
 *     chunks with one that OVERRIDES {@code getLight} and does not call super -- it reads its own
 *     16-block sections and writes the static itself. A redirect inside beta's method body never ran
 *     there, so every section was filed as having no sky light and the world never re-lit when the
 *     sun moved: outdoors at midnight, lit as if it were noon, under a sky full of stars.
 * <li>That subclass is a STATIONAPI class, and a mixin cannot portably name a Minecraft-derived
 *     method on one. The refmap that remaps {@code getLight} for production is built from the
 *     Minecraft mappings and does not reach a third party's class, so a mixin that resolved in a
 *     named dev run failed to resolve in a remapped one -- and a failed injection is a crash on
 *     world load, not a missing feature. Every name in this file belongs to Minecraft.
 * </ul>
 *
 * <p>The sky level is asked for separately rather than inferred from the combined result, which
 * cannot distinguish sky light from a torch. One nibble read per lookup, on a path that was already
 * reading one.
 *
 * <h2>Thread scope</h2>
 *
 * The pass is per-thread and set only around a section's block iteration, so the render thread's own
 * lighting queries -- entities, particles, the sky -- are never in a pass and go through untouched.
 * It is cleared in a finally block by {@code SectionMesher}, because a worker that threw mid-section
 * would otherwise answer every later query at the wrong time of day.
 */
@Mixin(WorldRegion.class)
public abstract class WorldRegionLightMixin {

	/**
	 * A brightness table that is 1.0 at every level, for the tint walk.
	 *
	 * <p>Handed back in place of the dimension's own, which is the smallest possible way to say "no
	 * light in this walk": the lookup still happens, still indexes by the level beta computed, and
	 * still returns a float -- it just returns the same one. Everything downstream, however the
	 * renderer blends and scales it, then produces the vertex's tint and face shade alone, because a
	 * weighted average of ones is one.
	 */
	@Unique
	private static final float[] retrodragon$ONES = new float[16];

	static {
		java.util.Arrays.fill(retrodragon$ONES, 1.0F);
	}

	@Redirect(
		method = "getRawBrightness(IIIZ)I",
		at = @At(value = "FIELD",
			opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/world/World;ambientDarkness:I"),
		require = 1)
	private int retrodragon$ambientDarkness(World world) {
		return switch (TerrainLight.pass()) {
			case DAYLIGHT -> 0;
			case BLOCK_FLOOR -> 15;
			default -> world.ambientDarkness;
		};
	}

	@Redirect(
		method = "getRawBrightness(IIIZ)I",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/chunk/Chunk;getLight(IIII)I"),
		require = 1)
	private int retrodragon$getLight(Chunk chunk, int x, int y, int z, int ambientDarkness) {
		if (chunk.getLight(LightType.SKY, x, y, z) > 0) {
			SectionMesher.markSkyLight();
		}
		return chunk.getLight(x, y, z, ambientDarkness);
	}

	@Redirect(
		method = { "getNaturalBrightness(IIII)F", "getLuminance(III)F" },
		at = @At(value = "FIELD",
			opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/world/dimension/Dimension;lightLevelToLuminance:[F"),
		require = 2)
	private float[] retrodragon$brightnessTable(Dimension dimension) {
		return TerrainLight.pass() == TerrainLight.Pass.TINT
			? retrodragon$ONES : dimension.lightLevelToLuminance;
	}
}
