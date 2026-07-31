package com.periut.retrodragon.mixin;

import com.periut.retrodragon.api.DrawPhase;
import com.periut.retrodragon.api.ShaderApi;

import net.minecraft.client.render.Culler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamps every world draw with what the game was drawing, for {@code api/DrawPhase}.
 *
 * <p>In the engine rather than in each shader mod on purpose. Every shader implementation on this
 * game has had to rediscover the phase from the bound texture -- clouds are whatever draws
 * {@code /environment/clouds.png} -- which works until two things share a texture and then fails
 * silently. The information exists exactly here, at the call, and costs a static int write.
 *
 * <p>Each method sets its phase on entry and restores the enclosing one on exit, so a draw between
 * two of them (a mod's own geometry, a block entity) keeps the surrounding phase rather than
 * inheriting whichever ran last.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererPhaseMixin {
	@Inject(method = "renderSky(F)V", at = @At("HEAD"))
	private void retrodragon$skyBegin(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.SKY);
		// The first point in the frame where beta's camera transform and projection are both loaded,
		// which is what a shader extension's per-frame state is built from. Earlier -- at the top of
		// renderWorld -- the matrices are still the GUI's.
		com.periut.retrodragon.render.WebGpuFrame.notifyWorldFrame(tickDelta);
	}

	/**
	 * The sunrise/sunset fan is the next untextured draw after the dimension is asked for its
	 * colours, and there is no other way to tell it from the dome: both are untextured triangle fans
	 * under identical GL state.
	 */
	@Inject(method = "renderSky(F)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/dimension/Dimension;getBackgroundColor(FF)[F"))
	private void retrodragon$skySunset(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.SKY_SUNSET);
	}

	/** The first texture bind inside renderSky is the sun; stars and the void plane follow. */
	@Inject(method = "renderSky(F)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/texture/TextureManager;getTextureId(Ljava/lang/String;)I",
		ordinal = 0))
	private void retrodragon$skyCelestial(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.CELESTIAL);
	}

	@Inject(method = "renderSky(F)V", at = @At("RETURN"))
	private void retrodragon$skyEnd(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderClouds(F)V", at = @At("HEAD"))
	private void retrodragon$cloudsBegin(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.CLOUDS);
	}

	@Inject(method = "renderClouds(F)V", at = @At("RETURN"))
	private void retrodragon$cloudsEnd(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderFancyClouds(F)V", at = @At("HEAD"))
	private void retrodragon$fancyCloudsBegin(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.CLOUDS);
	}

	@Inject(method = "renderFancyClouds(F)V", at = @At("RETURN"))
	private void retrodragon$fancyCloudsEnd(float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderEntities(Lnet/minecraft/util/math/Vec3d;"
		+ "Lnet/minecraft/client/render/Culler;F)V", at = @At("HEAD"))
	private void retrodragon$entitiesBegin(Vec3d cameraPos, Culler culler, float tickDelta,
			CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.ENTITIES);
	}

	@Inject(method = "renderEntities(Lnet/minecraft/util/math/Vec3d;"
		+ "Lnet/minecraft/client/render/Culler;F)V", at = @At("RETURN"))
	private void retrodragon$entitiesEnd(Vec3d cameraPos, Culler culler, float tickDelta,
			CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderMiningProgress(Lnet/minecraft/entity/player/PlayerEntity;"
		+ "Lnet/minecraft/util/hit/HitResult;ILnet/minecraft/item/ItemStack;F)V", at = @At("HEAD"))
	private void retrodragon$damageBegin(PlayerEntity player, HitResult hit, int mode,
			ItemStack stack, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.BLOCK_DAMAGE);
	}

	@Inject(method = "renderMiningProgress(Lnet/minecraft/entity/player/PlayerEntity;"
		+ "Lnet/minecraft/util/hit/HitResult;ILnet/minecraft/item/ItemStack;F)V", at = @At("RETURN"))
	private void retrodragon$damageEnd(PlayerEntity player, HitResult hit, int mode,
			ItemStack stack, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	@Inject(method = "renderBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;"
		+ "Lnet/minecraft/util/hit/HitResult;ILnet/minecraft/item/ItemStack;F)V", at = @At("HEAD"))
	private void retrodragon$outlineBegin(PlayerEntity player, HitResult hit, int mode,
			ItemStack stack, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.BLOCK_OUTLINE);
	}

	@Inject(method = "renderBlockOutline(Lnet/minecraft/entity/player/PlayerEntity;"
		+ "Lnet/minecraft/util/hit/HitResult;ILnet/minecraft/item/ItemStack;F)V", at = @At("RETURN"))
	private void retrodragon$outlineEnd(PlayerEntity player, HitResult hit, int mode,
			ItemStack stack, float tickDelta, CallbackInfo ci) {
		ShaderApi.setPhase(DrawPhase.WORLD);
	}

	// Terrain's phase is NOT set here. It is set inside WorldRendererMixin, immediately before the
	// section drawer runs.
	//
	// Two @Inject callbacks at one injection point have no defined order between them, and the other
	// one at renderChunks HEAD does the actual drawing. When it ran first, every terrain batch was
	// captured under whatever phase preceded it -- and a shader extension routing on phase then sent
	// the packed terrain stream to a program built for beta's 32-byte vertex. A pipeline bakes its
	// stride in, so that is not a wrong colour: it is the whole section erupting across the screen.
}
