package com.periut.retrodragon.window;

import net.minecraft.client.gui.screen.Screen;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds state shared between ReloadScreenManager and Minecraft mixins
 * for EarlyRenderLoop mode coordination.
 */
public final class StapiEarlyRenderLoopState {
    private static boolean usingEarlyRenderLoop = false;
    private static Screen earlyRenderLoopScreen = null;
    private static AtomicBoolean earlyRenderLoopDone = null;

    private StapiEarlyRenderLoopState() {}

    public static boolean isUsingEarlyRenderLoop() {
        return usingEarlyRenderLoop;
    }

    public static void setUsingEarlyRenderLoop(boolean value) {
        usingEarlyRenderLoop = value;
    }

    public static Screen getEarlyRenderLoopScreen() {
        return earlyRenderLoopScreen;
    }

    public static void setEarlyRenderLoopScreen(Screen screen) {
        earlyRenderLoopScreen = screen;
    }

    public static AtomicBoolean getEarlyRenderLoopDone() {
        return earlyRenderLoopDone;
    }

    public static void setEarlyRenderLoopDone(AtomicBoolean done) {
        earlyRenderLoopDone = done;
    }

    public static void reset() {
        usingEarlyRenderLoop = false;
        earlyRenderLoopScreen = null;
        earlyRenderLoopDone = null;
    }
}
