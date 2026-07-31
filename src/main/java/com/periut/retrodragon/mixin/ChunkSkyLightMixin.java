package com.periut.retrodragon.mixin;

import com.periut.retrodragon.render.SectionMesher;

import net.minecraft.world.chunk.Chunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives beta's {@code Chunk.hasSkyLight} a per-thread copy, because meshing made it shared.
 *
 * <p>{@code Chunk.getLight} sets that static whenever the block it just read had any sky light, and
 * vanilla uses it as a per-section verdict: {@code ChunkBuilder.rebuild} clears it, walks the
 * section, and stores whatever it ended up as in {@code ChunkBuilder.hasSkyLight}. That field is
 * read in exactly one place -- {@code WorldRenderer.notifyAmbientDarknessChanged}, which re-marks
 * every sky-lit section dirty when the ambient darkness changes (dawn, dusk, rain, thunder, a
 * dimension's light curve).
 *
 * <p>A JVM-global write-only flag is a fine way to spell that when the only writer is the render
 * thread and the clear-walk-read is one uninterrupted call. It is not fine once two mesh workers do
 * it at once: worker A's clear lands in the middle of worker B's walk and wipes the {@code true} B
 * had already recorded, so B's section is filed as having no sky light. That section is then skipped
 * by every ambient-darkness pass from then on, and keeps the sky lighting it was built with until
 * something else happens to dirty it -- a chunk that is visibly a different time of day from its
 * neighbours, and stays that way.
 *
 * <p>The static is still written, so the vanilla {@code rebuild} path (which is what runs with
 * {@code -Dretroperf.terrain=false}) behaves exactly as before.
 */
@Mixin(Chunk.class)
public class ChunkSkyLightMixin {

	@Redirect(
		method = "getLight(IIII)I",
		at = @At(value = "FIELD",
			opcode = Opcodes.PUTSTATIC,
			target = "Lnet/minecraft/world/chunk/Chunk;hasSkyLight:Z"),
		require = 1)
	private void retroperf$markSkyLight(boolean value) {
		Chunk.hasSkyLight = value;
		SectionMesher.markSkyLight();
	}
}
