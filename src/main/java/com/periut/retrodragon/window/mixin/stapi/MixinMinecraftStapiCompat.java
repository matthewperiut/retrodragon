package com.periut.retrodragon.window.mixin.stapi;

import com.periut.retrodragon.window.LWJGLHelper;
import com.periut.retrodragon.window.StapiEarlyRenderLoopState;
import com.periut.retrodragon.window.Starac;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenScaler;
import net.modificationstation.stationapi.impl.client.resource.ReloadScreenApplicationExecutor;
import net.modificationstation.stationapi.impl.client.resource.ReloadScreenManagerImpl;
import net.modificationstation.stationapi.mixin.resourceloader.client.MinecraftAccessor;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11.*;

/**
 * Mixin for Minecraft to handle the EarlyRenderLoop execution for StationAPI compatibility.
 * This runs at the same injection point as StationAPI's MinecraftMixin.stationapi_applyReloadsAndWait
 * but handles the single-threaded LWJGL3 case.
 */
@Mixin(value = Minecraft.class, priority = 500) // Lower priority = runs first
public class MixinMinecraftStapiCompat {

    /**
     * Injects before StationAPI's stationapi_applyReloadsAndWait to handle EarlyRenderLoop mode.
     * When in EarlyRenderLoop mode, this runs the render loop.
     */
    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;renderLoadingScreen()V"
            )
    )
    private void starac_handleEarlyRenderLoop(CallbackInfo ci) {
        // Mark Minecraft initialization as done (same as StationAPI does)
        ReloadScreenManagerImpl.isMinecraftDone = true;

        // Only handle EarlyRenderLoop mode; let StationAPI handle LWJGL2 threaded mode
        if (!StapiEarlyRenderLoopState.isUsingEarlyRenderLoop()) {
            return; // Let StationAPI's injection handle it
        }

        // Run the single-threaded render loop
        starac_runEarlyRenderLoop();

        // Mark that we need to call onFinish() after StationAPI's injection runs
        Starac.STAPI_NEEDS_ON_FINISH_CLEANUP = true;

        // Don't cancel - StationAPI's injection will run next, but its while loop will exit
        // immediately since isReloadComplete() returns true
    }

    @Unique
    private void starac_runEarlyRenderLoop() {
        Screen screen = StapiEarlyRenderLoopState.getEarlyRenderLoopScreen();
        if (screen == null) return;

        //noinspection deprecation
        var minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();
        var screenScaler = new ScreenScaler(minecraft.options, minecraft.displayWidth, minecraft.displayHeight);
        var width = screenScaler.getScaledWidth();
        var height = screenScaler.getScaledHeight();
        var timer = ((MinecraftAccessor) minecraft).getTimer();

        // Set up GL state
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrtho(0.0, screenScaler.rawScaledWidth, screenScaler.rawScaledHeight, 0.0, 1000.0, 3000.0);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glTranslatef(0.0f, 0.0f, -2000.0f);
        glViewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glDisable(GL_LIGHTING);
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_FOG);

        try {
            LWJGLHelper.runEarlyRenderLoop(
                    () -> !StapiEarlyRenderLoopState.getEarlyRenderLoopDone().get(),
                    () -> {
                        // Process ALL pending executor tasks each frame
                        Runnable task;
                        while ((task = ReloadScreenApplicationExecutor.INSTANCE.poll()) != null) {
                            task.run();
                        }

                        var f = timer.partialTick;
                        timer.advance();
                        timer.partialTick = f;
                        var mouseX = Mouse.getX() * width / minecraft.displayWidth;
                        var mouseY = height - Mouse.getY() * height / minecraft.displayHeight - 1;
                        screen.render(mouseX, mouseY, timer.partialTick);
                    }
            );
        } catch (LWJGLException e) {
            throw new RuntimeException("Failed to run EarlyRenderLoop", e);
        }

        // Clean up GL state to match what Minecraft expects after loading
        glDisable(GL_LIGHTING);
        glDisable(GL_FOG);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.1f);

        // Clear to black (Minecraft's default)
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Reset screen state - Minecraft expects currentScreen to be null after early loading
        minecraft.currentScreen = null;

        // Reset our state (but don't call onFinish yet - StationAPI's injection needs reloadScreen to be set)
        StapiEarlyRenderLoopState.reset();

        System.out.println("[RetroDragon] EarlyRenderLoop completed, handing off to Minecraft");
    }

}
