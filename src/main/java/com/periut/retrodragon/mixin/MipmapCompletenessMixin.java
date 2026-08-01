package com.periut.retrodragon.mixin;

import java.awt.image.BufferedImage;

import com.periut.retrodragon.api.RetroSettings;
import com.periut.retrodragon.render.BlockAtlas;
import com.periut.retrodragon.render.TerrainMipmaps;

import net.minecraft.client.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builds our own mip chain, for the block atlas ONLY.
 *
 * The "only" is load-bearing and was got wrong once: `load(BufferedImage,int)` has no idea which
 * texture it is uploading, so an earlier version gated on "square and a multiple of 16". Beta's
 * font sheet (128x128) and every GUI sheet (256x256) satisfy that too, so they were mipmapped --
 * which sets a minifying filter on them and visibly broke text and the crosshair. Terrain is
 * identified by path in {@code getTextureId}, and nothing else is ever touched.
 *
 * The atlas's texture NAME is recorded as well, and that is the signal that keeps working when a
 * content API is installed: RetroAPI composites its expanded sheet and re-uploads it from the tail
 * of {@code getTextureId} and from {@code reload}, neither of which the path flag below brackets. Both
 * of those uploads still carry the name, so both are recognised -- otherwise the expanded atlas got a
 * single level after every resource reload while the shader went on sampling a chain that was no
 * longer there.
 *
 * Beta's own generator stays disabled: it builds an incomplete chain (levels 1..4 of a 256x256
 * atlas, no GL_TEXTURE_MAX_LEVEL) which is undefined and samples as flat white.
 */
@Mixin(TextureManager.class)
public class MipmapCompletenessMixin {
	/** Set for the duration of the terrain.png load, so the TAIL hook knows what it is looking at. */
	@Unique private static boolean retroperf$loadingTerrain;

	@Inject(method = "getTextureId(Ljava/lang/String;)I", at = @At("HEAD"))
	private void retroperf$markTerrain(String path, CallbackInfoReturnable<Integer> cir) {
		retroperf$loadingTerrain = path != null && path.endsWith("terrain.png");
		// The WebGPU backend needs the same signal, and for the same reason: it builds its mip chain
		// at upload time, where the only thing distinguishing terrain.png from a GUI sheet is this.
		com.periut.retrodragon.render.TextureStore.markTerrainLoad(retroperf$loadingTerrain);
		// Sampler state is NOT read from the path here. beta states it with glTexParameteri inside
		// load(BufferedImage,int), which every upload goes through -- including reload(), which this
		// hook never sees and which re-uploads everything on a texture-pack or video-option change.
		// See GlBridge.glTexParameteri.
	}

	@Inject(method = "getTextureId(Ljava/lang/String;)I", at = @At("RETURN"))
	private void retroperf$clearTerrain(String path, CallbackInfoReturnable<Integer> cir) {
		if (retroperf$loadingTerrain) {
			// Remembered for the rest of the run: this is the atlas, whoever uploads to it next.
			BlockAtlas.markTexture(cir.getReturnValueI());
		}
		retroperf$loadingTerrain = false;
		com.periut.retrodragon.render.TextureStore.markTerrainLoad(false);
	}

	@Inject(method = "load(Ljava/awt/image/BufferedImage;I)V", at = @At("TAIL"))
	private void retroperf$buildMipmaps(BufferedImage image, int id, CallbackInfo ci) {
		if (!retroperf$loadingTerrain && !BlockAtlas.isBlockAtlas(id)) {
			return;
		}
		// Recorded whether or not mipmaps are on: the shader's texel inset is derived from the atlas
		// size too, so an HD pack under -Dretroperf.mipmap=false still has to be measured here.
		BlockAtlas.observe(image.getWidth(), image.getHeight());
		if (RetroSettings.isMipmap()) {
			TerrainMipmaps.apply(image);
		}
	}
}
