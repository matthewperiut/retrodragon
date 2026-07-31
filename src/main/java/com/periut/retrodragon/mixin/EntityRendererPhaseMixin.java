package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two per-entity draws a shader pack always has to treat specially.
 *
 * <p>Entity fire is unlit and fullbright -- it is a flame, and shading it by the sun makes it dim in
 * its own shadow. The blob shadow is a dark circle stamped on the ground, which a pack with a real
 * shadow pass wants gone entirely; without a phase for it, suppressing it means every pack
 * hard-coding beta's {@code %clamp%/misc/shadow.png} path.
 *
 * <p>Both restore {@link DrawPhase#ENTITIES} rather than {@link DrawPhase#NONE}: they are called
 * from inside the entity sweep, and the draws that follow are the next entity's.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererPhaseMixin {
	@Inject(method = "renderOnFire(Lnet/minecraft/entity/Entity;DDDF)V", at = @At("HEAD"))
	private void retrodragon$fireBegin(Entity entity, double x, double y, double z, float tickDelta,
			CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.ENTITY_FIRE);
	}

	@Inject(method = "renderOnFire(Lnet/minecraft/entity/Entity;DDDF)V", at = @At("RETURN"))
	private void retrodragon$fireEnd(Entity entity, double x, double y, double z, float tickDelta,
			CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.ENTITIES);
	}

	@Inject(method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"))
	private void retrodragon$shadowBegin(Entity entity, double x, double y, double z, float opacity,
			float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.ENTITY_SHADOW);
	}

	@Inject(method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("RETURN"))
	private void retrodragon$shadowEnd(Entity entity, double x, double y, double z, float opacity,
			float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.ENTITIES);
	}
}
