package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;

import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles, which beta draws in two sweeps -- the ordinary one and the lit one for the water and
 * portal layers.
 *
 * <p>Both take {@link DrawPhase#PARTICLES}. A pack cares that a draw is a particle, not which of
 * beta's two internal sweeps it came from, and the sweeps differ only in whether the texture is the
 * particle sheet or the block atlas.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerPhaseMixin {
	@Inject(method = "render(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
	private void retrodragon$particlesBegin(Entity camera, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.PARTICLES);
	}

	@Inject(method = "render(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"))
	private void retrodragon$particlesEnd(Entity camera, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderLit(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
	private void retrodragon$litParticlesBegin(Entity camera, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.PARTICLES);
	}

	@Inject(method = "renderLit(Lnet/minecraft/entity/Entity;F)V", at = @At("RETURN"))
	private void retrodragon$litParticlesEnd(Entity camera, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}
}
