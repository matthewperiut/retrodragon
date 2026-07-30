package com.periut.retrodragon.window.mixin.stapi;

import com.google.common.primitives.Floats;
import com.periut.retrodragon.window.StapiEarlyRenderLoopState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.Tessellator;
import net.modificationstation.stationapi.api.client.resource.ReloadScreenManager;
import net.modificationstation.stationapi.api.resource.ResourceReload;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.CompletionException;

@Mixin(targets = "net.modificationstation.stationapi.api.client.resource.ReloadScreen", remap = false)
public abstract class ReloadScreenMixin extends Screen {

    @Shadow
    private boolean firstRenderTick;

    @Shadow
    private long initTimestamp;

    @Shadow
    private long currentTime;

    @Shadow
    private long lastRender;

    @Shadow
    private boolean exceptionThrown;

    @Shadow
    private boolean finished;

    @Shadow
    private long fadeOutStart;

    @Shadow
    private float scrollProgress;

    @Shadow
    private float progress;

    @Shadow
    private long exceptionStart;

    @Shadow
    private Exception exception;

    @Shadow
    @Final
    private Runnable done;

    @Shadow
    @Final
    private Screen parent;

    @Shadow
    @Final
    private Runnable backgroundEmitter;

    @Shadow
    @Final
    private Runnable stage0Emitter;

    @Shadow
    private long BACKGROUND_FADE_IN;

    @Shadow
    @Final
    private long BACKGROUND_START;

    @Shadow
    private long STAGE_0_START;

    @Shadow
    private long STAGE_0_FADE_IN;

    @Shadow
    private long GLOBAL_FADE_OUT;

    @Shadow
    private long RELOAD_START;

    @Shadow
    private long EXCEPTION_TRANSFORM;

    @Shadow
    abstract boolean isReloadStarted();

    // Wrap-up mode fields for EarlyRenderLoop
    @Unique
    private static final long WRAP_UP_DURATION = 100; // 100ms wrap-up period after loading

    @Unique
    private boolean starac_inWrapUp = false;

    @Unique
    private long starac_wrapUpStart = 0;

    /**
     * Inject at the end of the constructor to set faster animation timings.
     * Applies to both the startup (EarlyRenderLoop) screen and in-game resource reloads.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void starac_onConstructorReturn(Screen parent, Runnable done, Tessellator tessellator, CallbackInfo ci) {
        // Faster animations
        BACKGROUND_FADE_IN = 250;
        STAGE_0_START = BACKGROUND_START + BACKGROUND_FADE_IN;
        STAGE_0_FADE_IN = 500;
        GLOBAL_FADE_OUT = 300;
        RELOAD_START = STAGE_0_START + STAGE_0_FADE_IN;
        EXCEPTION_TRANSFORM = 200;
    }

    /**
     * @author starac
     * @reason Add wrap-up mode support for EarlyRenderLoop with faster animation completion
     */
    @Overwrite
    public void render(int mouseX, int mouseY, float delta) {
        if (firstRenderTick) {
            firstRenderTick = false;
            initTimestamp = System.currentTimeMillis();
        }
        currentTime = System.currentTimeMillis() - initTimestamp;

        final long MAX_FPS = 60;
        var partial = currentTime - lastRender < (1000 / MAX_FPS);
        if (partial) currentTime = lastRender;
        else lastRender = currentTime;
        var locationsSize = ReloadScreenManagerAccessor.getLocations().size();

        // Check if we should enter wrap-up mode (loading done). Applies in both the
        // startup EarlyRenderLoop case and in-game reloads, so both finish quickly.
        if (!exceptionThrown && !finished && !starac_inWrapUp && ReloadScreenManager.isReloadComplete()) {
            // Enter wrap-up mode - must complete before finishing
            starac_inWrapUp = true;
            starac_wrapUpStart = currentTime;
        }

        // During wrap-up, accelerate animations to catch up
        float progressLerp = 0.05F;
        float scrollLerp = 0.05F;
        if (starac_inWrapUp) {
            // Speed up lerping during wrap-up
            progressLerp = 0.4F;
            scrollLerp = 0.4F;

            // Wrap-up MUST complete its full duration before finishing
            var wrapUpElapsed = currentTime - starac_wrapUpStart;
            if (wrapUpElapsed >= WRAP_UP_DURATION) {
                try {
                    ReloadScreenManager.getCurrentReload().ifPresent(ResourceReload::throwException);
                    finished = true;
                    fadeOutStart = currentTime;
                    starac_inWrapUp = false;
                } catch (CompletionException e) {
                    exceptionThrown = true;
                    exceptionStart = currentTime;
                    exception = e;
                    starac_inWrapUp = false;
                    System.err.println("[RetroDragon] An exception occurred during resource loading");
                    e.printStackTrace();
                }
            }
        } else if (!exceptionThrown && !finished && !(scrollProgress + .1 < locationsSize) && !(progress + .1 < 1) && ReloadScreenManager.isReloadComplete()) {
            // Original logic for non-EarlyRenderLoop mode
            try {
                ReloadScreenManager.getCurrentReload().ifPresent(ResourceReload::throwException);
                finished = true;
                fadeOutStart = currentTime;
            } catch (CompletionException e) {
                exceptionThrown = true;
                exceptionStart = currentTime;
                exception = e;
                System.err.println("[RetroDragon] An exception occurred during resource loading");
                e.printStackTrace();
            }
        }

        if (!partial) {
            Optional<ResourceReload> reload;
            progress = Floats.constrainToRange(progress * (1 - progressLerp) + (isReloadStarted() && (reload = ReloadScreenManager.getCurrentReload()).isPresent() ? reload.get().getProgress() : 0) * progressLerp, 0, 1);
            scrollProgress = Floats.constrainToRange(scrollProgress * (1 - scrollLerp) + locationsSize * scrollLerp, 0, locationsSize);
        }
        if ((finished ? currentTime <= fadeOutStart + GLOBAL_FADE_OUT : currentTime < BACKGROUND_START + BACKGROUND_FADE_IN) && parent != null)
            parent.render(mouseX, mouseY, delta);
        renderBackground();
        super.render(mouseX, mouseY, delta);
        stage0Emitter.run();
        if (finished && currentTime - fadeOutStart > GLOBAL_FADE_OUT) {
            // In EarlyRenderLoop mode, don't call onFinish() here - it will be called later
            // This keeps reloadScreen set so isReloadComplete() returns true for StationAPI's while loop
            if (!StapiEarlyRenderLoopState.isUsingEarlyRenderLoop()) {
                ReloadScreenManagerAccessor.onFinish();
            }
            done.run();
        }
    }

    @Override
    public void renderBackground() {
        backgroundEmitter.run();
    }
}
