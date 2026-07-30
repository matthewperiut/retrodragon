package org.lwjgl.opengl;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.function.BooleanSupplier;

/**
 * Provides a hook for running an early render loop before the main game loop starts.
 * This allows mods like StationAPI to show loading screens during startup without
 * the complexity of multi-threaded context management.
 *
 * Usage:
 * 1. Detect this class exists (indicates starac/LWJGL3 compatibility layer)
 * 2. Call runLoop() with your render callback and completion condition
 * 3. The loop handles event polling, input, and buffer swapping properly
 */
public final class EarlyRenderLoop {

    private EarlyRenderLoop() {}

    /**
     * Runs an early render loop on the current thread.
     * This properly handles event polling and input while rendering.
     *
     * <p>Everything that touches the window goes through {@link Display}, never through raw
     * {@code GLFW}: on the default SDL3 backend {@link Display#getHandle()} is an
     * {@code SDL_Window*} and GLFW is never initialized, so {@code glfwSwapBuffers} /
     * {@code glfwWindowShouldClose} would silently early-return on {@code _GLFW_REQUIRE_INIT}
     * and the whole loading screen would render into a back buffer that is never presented.
     * {@code selfChecks} enforces this by asserting the compiled class has no GLFW reference.
     *
     * @param shouldContinue Returns true while the loop should keep running, false to exit
     * @param render Called each frame to perform rendering
     * @param targetFps Target frames per second (0 for unlimited)
     */
    public static void runLoop(BooleanSupplier shouldContinue, Runnable render, int targetFps) {
        // Still the right liveness probe on both backends: handle stays -1 until Display.create(),
        // and the SDL branch stores a non-negative SDL_Window* there.
        if (Display.getHandle() == -1L) {
            throw new IllegalStateException("Display not created yet");
        }

        long frameTimeNanos = targetFps > 0 ? 1_000_000_000L / targetFps : 0;
        long lastFrameTime = System.nanoTime();

        // RetroCenter: a child instance neither pumps GLFW events (the
        // parked hub thread owns the event queue) nor presents while the
        // present gate holds (the hub's last frame stays on screen).
        boolean retrocenterChild = com.periut.retrodragon.retrocenter.bridge.HubBridge.callerIsChild();
        while (shouldContinue.getAsBoolean()) {
            // Proper event polling (async-safe: raw glfwPollEvents deadlocks
            // against the CGL lock on macOS with glfw_async)
            if (!retrocenterChild) {
                Display.pollEvents();
            }

            // Poll input devices
            if (Mouse.isCreated()) {
                Mouse.poll();
            }
            if (Keyboard.isCreated()) {
                Keyboard.poll();
            }

            // Drain input event queues to prevent buildup
            while (Mouse.next()) { /* drain */ }
            while (Keyboard.next()) { /* drain */ }

            // Call the render callback
            render.run();

            // Swap buffers. Display.swapBuffers() carries the same RetroCenter present gate on
            // both branches, so there is no guard to repeat here.
            Display.swapBuffers();

            // Frame rate limiting
            if (frameTimeNanos > 0) {
                long now = System.nanoTime();
                long elapsed = now - lastFrameTime;
                long sleepTime = frameTimeNanos - elapsed;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime / 1_000_000, (int) (sleepTime % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                lastFrameTime = System.nanoTime();
            } else {
                lastFrameTime = System.nanoTime();
            }

            // Check if window should close. On SDL this reads the latched quit/close flag that the
            // loop's own Display.pollEvents() above feeds.
            if (Display.isCloseRequested()) {
                break;
            }
        }
    }

    /**
     * Simplified version with default 60 FPS target.
     */
    public static void runLoop(BooleanSupplier shouldContinue, Runnable render) {
        runLoop(shouldContinue, render, 60);
    }
}
