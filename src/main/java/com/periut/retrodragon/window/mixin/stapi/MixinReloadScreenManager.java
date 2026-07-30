package com.periut.retrodragon.window.mixin.stapi;

import com.periut.retrodragon.window.LWJGLHelper;
import com.periut.retrodragon.window.StapiEarlyRenderLoopState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.ScreenScaler;
import net.modificationstation.stationapi.api.client.resource.ReloadScreenManager;
import net.modificationstation.stationapi.api.resource.CompositeResourceReload;
import net.modificationstation.stationapi.api.resource.ResourceReload;
import net.modificationstation.stationapi.impl.client.resource.ReloadScreenApplicationExecutor;
import net.modificationstation.stationapi.impl.client.resource.ReloadScreenManagerImpl;
import net.modificationstation.stationapi.impl.client.resource.ReloadScreenTessellatorHolder;
import net.modificationstation.stationapi.mixin.resourceloader.client.MinecraftAccessor;
import net.modificationstation.stationapi.mixin.resourceloader.client.TessellatorAccessor;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL11.*;

// priority 500: other mods (e.g. no_startup_screen) @Inject into openEarly();
// Mixin only allows injecting into a merged method when the injector's
// priority is HIGHER than the merging mixin's, so ours must sit below 1000.
@Mixin(value = ReloadScreenManager.class, remap = false, priority = 500)
public class MixinReloadScreenManager {

    @Shadow
    private static Optional<Thread> thread;

    @Shadow
    private static Optional<ResourceReload> currentReload;

    @Shadow
    private static Executor applicationExecutor;

    /**
     * @author starac
     * @reason Replace LWJGL2-specific code with LWJGLHelper for LWJGL3 compatibility
     */
    @Overwrite
    public static void openEarly() throws LWJGLException {
        ReloadScreenManagerImpl.isMinecraftDone = false;
        applicationExecutor = ReloadScreenApplicationExecutor.INSTANCE;
        // no_startup_screen injects AFTER this field write and cancels --
        // keep it as a plain static assignment, matching vanilla stapi.
        currentReload = Optional.of(new CompositeResourceReload());
        //noinspection deprecation
        final Minecraft minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();

        // Use starac's EarlyRenderLoop if available (LWJGL3 with proper event handling)
        if (LWJGLHelper.hasEarlyRenderLoop()) {
            System.out.println("[RetroDragon] Using EarlyRenderLoop for loading screen");
            starac_onStartupWithEarlyRenderLoop(minecraft);
            return;
        }

        // Fall back to LWJGL2 threaded approach
        if (LWJGLHelper.isLWJGL2()) {
            try {
                final Object drawable = LWJGLHelper.createSharedDrawable();
                thread = Optional.of(new Thread(() -> starac_onStartupThreaded(minecraft, drawable)));
                thread.ifPresent(Thread::start);
            } catch (LWJGLException e) {
                throw e;
            }
            return;
        }

        // No compatible approach available - skip early loading screen
        System.err.println("[RetroDragon] No compatible early loading screen implementation available");
    }

    /**
     * Sets up state for single-threaded early render loop.
     * Returns immediately - actual render loop runs from the Minecraft mixin.
     */
    @Unique
    private static void starac_onStartupWithEarlyRenderLoop(final Minecraft minecraft) {
        StapiEarlyRenderLoopState.setUsingEarlyRenderLoop(true);
        StapiEarlyRenderLoopState.setEarlyRenderLoopDone(new AtomicBoolean(false));

        // Create the ReloadScreen using reflection to access the package-private class
        try {
            Class<?> reloadScreenClass = Class.forName("net.modificationstation.stationapi.api.client.resource.ReloadScreen");
            Object tessellator = TessellatorAccessor.stationapi_create(48);
            ReloadScreenTessellatorHolder.reloadScreenTessellator = (net.minecraft.client.render.Tessellator) tessellator;

            // Create done callback that sets our flag
            Runnable doneCallback = () -> StapiEarlyRenderLoopState.getEarlyRenderLoopDone().set(true);

            // Constructor: ReloadScreen(Screen parent, Runnable done, Tessellator tessellator)
            var constructor = reloadScreenClass.getDeclaredConstructor(
                    net.minecraft.client.gui.screen.Screen.class,
                    Runnable.class,
                    net.minecraft.client.render.Tessellator.class
            );
            constructor.setAccessible(true);
            Object screen = constructor.newInstance(
                    minecraft.currentScreen,
                    doneCallback,
                    tessellator
            );
            StapiEarlyRenderLoopState.setEarlyRenderLoopScreen((net.minecraft.client.gui.screen.Screen) screen);

            var screenScaler = new ScreenScaler(minecraft.options, minecraft.displayWidth, minecraft.displayHeight);
            ((net.minecraft.client.gui.screen.Screen) screen).init(minecraft, screenScaler.getScaledWidth(), screenScaler.getScaledHeight());

            // Call setTextRenderer via reflection
            var setTextRenderer = reloadScreenClass.getDeclaredMethod("setTextRenderer", TextRenderer.class);
            setTextRenderer.setAccessible(true);
            setTextRenderer.invoke(screen,
                    new TextRenderer(minecraft.options, "/font/default.png", minecraft.textureManager));

        } catch (Exception e) {
            throw new RuntimeException("Failed to create ReloadScreen for EarlyRenderLoop", e);
        }
    }

    /**
     * Threaded approach for LWJGL2 using SharedDrawable.
     */
    @Unique
    private static void starac_onStartupThreaded(
            final Minecraft minecraft,
            final Object drawable
    ) {
        try {
            LWJGLHelper.makeCurrent(drawable);
        } catch (LWJGLException e) {
            throw new RuntimeException(e);
        }
        var done = new AtomicBoolean();

        // Create the ReloadScreen using reflection
        Object localReloadScreen;
        try {
            Class<?> reloadScreenClass = Class.forName("net.modificationstation.stationapi.api.client.resource.ReloadScreen");
            Object tessellator = TessellatorAccessor.stationapi_create(48);
            ReloadScreenTessellatorHolder.reloadScreenTessellator = (net.minecraft.client.render.Tessellator) tessellator;

            var constructor = reloadScreenClass.getDeclaredConstructor(
                    net.minecraft.client.gui.screen.Screen.class,
                    Runnable.class,
                    net.minecraft.client.render.Tessellator.class
            );
            constructor.setAccessible(true);
            localReloadScreen = constructor.newInstance(
                    minecraft.currentScreen,
                    (Runnable) () -> done.set(true),
                    tessellator
            );

            var screenScaler = new ScreenScaler(minecraft.options, minecraft.displayWidth, minecraft.displayHeight);
            var screen = (net.minecraft.client.gui.screen.Screen) localReloadScreen;
            var widthVal = screenScaler.getScaledWidth();
            var heightVal = screenScaler.getScaledHeight();
            screen.init(minecraft, widthVal, heightVal);

            var setTextRenderer = reloadScreenClass.getDeclaredMethod("setTextRenderer", TextRenderer.class);
            setTextRenderer.setAccessible(true);
            setTextRenderer.invoke(localReloadScreen,
                    new TextRenderer(minecraft.options, "/font/default.png", minecraft.textureManager));

            var timer = ((MinecraftAccessor) minecraft).getTimer();
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
            while (!done.get()) {
                while (true) if (!Mouse.next()) break;
                while (true) if (!Keyboard.next()) break;
                var f = timer.partialTick;
                timer.advance();
                timer.partialTick = f;
                var mouseX = Mouse.getX() * widthVal / minecraft.displayWidth;
                var mouseY = heightVal - Mouse.getY() * heightVal / minecraft.displayHeight - 1;
                screen.render(mouseX, mouseY, timer.partialTick);
                try {
                    LWJGLHelper.displayUpdate();
                } catch (LWJGLException e) {
                    throw new RuntimeException(e);
                }
            }
            glDisable(GL_LIGHTING);
            glDisable(GL_FOG);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.1f);
            try {
                LWJGLHelper.releaseContext(drawable);
            } catch (LWJGLException e) {
                throw new RuntimeException(e);
            }
            thread = Optional.empty();

        } catch (Exception e) {
            throw new RuntimeException("Failed to run threaded ReloadScreen", e);
        }
    }
}
