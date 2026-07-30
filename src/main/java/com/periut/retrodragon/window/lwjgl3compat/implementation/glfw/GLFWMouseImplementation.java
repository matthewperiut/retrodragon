package com.periut.retrodragon.window.lwjgl3compat.implementation.glfw;

import com.periut.retrodragon.window.lwjgl3compat.implementation.input.MouseImplementation;
import com.periut.retrodragon.window.lwjgl3compat.wayland.WaylandCenterCursor;
import org.lwjgl.glfw.*;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EventQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;

/**
 * @author Zarzelcow
 * @created 28/09/2022 - 8:58 PM
 */
public class GLFWMouseImplementation implements MouseImplementation {
    protected GLFWMouseButtonCallback buttonCallback;
    private GLFWCursorPosCallback posCallback;
    private GLFWScrollCallback scrollCallback;
    private GLFWCursorEnterCallback cursorEnterCallback;
    private long windowHandle;
    private boolean grabbed;
    private boolean isInsideWindow;

    private final EventQueue event_queue = new EventQueue(Mouse.EVENT_SIZE);

    private final ByteBuffer tmp_event = ByteBuffer.allocate(Mouse.EVENT_SIZE);

    private double last_x;
    private double last_y;
    private double accum_dx;
    private double accum_dy;
    private double accum_dz;
    protected byte[] button_states = new byte[this.getButtonCount()];
    private long last_event_nanos;

    @Override
    public void createMouse() {
        this.windowHandle = Display.getHandle();

        if (GLFW.glfwRawMouseMotionSupported() && !Mouse.getPrivilegedBoolean("org.lwjgl.input.Mouse.disableRawInput"))
            GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);

        this.buttonCallback = GLFWMouseButtonCallback.create((window, button, action, mods) -> {
            byte state = action == GLFW.GLFW_PRESS ? (byte)1 : (byte)0;
            putMouseEvent((byte) button, state, 0, System.nanoTime());
            if (button < button_states.length)
                button_states[button] = state;
        });
        this.posCallback = GLFWCursorPosCallback.create((window, xpos, ypos) -> {
            int x = (int) (xpos * Display.getContentScaleX());
            int y = Display.getHeight() - (int) (ypos * Display.getContentScaleY());
            double dx = x - last_x;
            double dy = y - last_y;
            if (dx != 0 || dy != 0) {
                accum_dx += dx;
                accum_dy += dy;
                last_x = x;
                last_y = y;
                long nanos = System.nanoTime();
                if (grabbed) {
                    putMouseEventWithCoords((byte)-1, (byte)0, dx, dy, 0, nanos);
                } else {
                    putMouseEventWithCoords((byte)-1, (byte)0, x, y, 0, nanos);
                }
            }
        });
        this.scrollCallback = GLFWScrollCallback.create((window, xoffset, yoffset) -> {
            accum_dz += yoffset;
            putMouseEvent((byte)-1, (byte)0, (int) yoffset, System.nanoTime());
        });
        this.cursorEnterCallback = GLFWCursorEnterCallback.create((window, entered) -> this.isInsideWindow = entered);

        GLFW.glfwSetMouseButtonCallback(this.windowHandle, this.buttonCallback);
        GLFW.glfwSetCursorPosCallback(this.windowHandle, this.posCallback);
        GLFW.glfwSetScrollCallback(this.windowHandle, this.scrollCallback);
        GLFW.glfwSetCursorEnterCallback(this.windowHandle, this.cursorEnterCallback);
    }

    protected void putMouseEvent(byte button, byte state, int dz, long nanos) {
        if (grabbed)
            putMouseEventWithCoords(button, state, 0, 0, dz, nanos);
        else
            putMouseEventWithCoords(button, state, last_x, last_y, dz, nanos);
    }

    protected void putMouseEventWithCoords(byte button, byte state, double coord1, double coord2, int dz, long nanos) {
        tmp_event.clear();
        tmp_event.put(button).put(state).putDouble(coord1).putDouble(coord2).putDouble(dz).putLong(nanos);
        tmp_event.flip();
        event_queue.putEvent(tmp_event);
        last_event_nanos = nanos;
    }

    @Override
    public void destroyMouse() {
        this.buttonCallback.free();
        this.posCallback.free();
        this.scrollCallback.free();
        this.cursorEnterCallback.free();
    }

    private void reset() {
        this.event_queue.clearEvents();
        accum_dx = accum_dy = 0;
    }
    @Override
    public void pollMouse(DoubleBuffer coord_buffer, ByteBuffer buttons_buffer) {
        if (grabbed) {
            coord_buffer.put(0, accum_dx);
            coord_buffer.put(1, accum_dy);
        } else {
            coord_buffer.put(0, last_x);
            coord_buffer.put(1, last_y);
        }
        coord_buffer.put(2, accum_dz);
        accum_dx = accum_dy = accum_dz = 0;
        for (int i = 0; i < button_states.length; i++)
            buttons_buffer.put(i, button_states[i]);
    }

    @Override
    public void readMouse(ByteBuffer readBuffer) {
        event_queue.copyEvents(readBuffer);
    }

    @Override
    public void setCursorPosition(double x, double y) {
        this.last_x = x;
        this.last_y = y;
        double screenX = x / Display.getContentScaleX();
        double screenY = (Display.getHeight() - y) / Display.getContentScaleY();
        GLFW.glfwSetCursorPos(this.windowHandle, screenX, screenY);
    }

    @Override
    public void grabMouse(boolean grab) {
        if (!grab && grabbed) {
            double screenX = last_x / Display.getContentScaleX();
            double screenY = (Display.getHeight() - last_y) / Display.getContentScaleY();
            GLFW.glfwSetCursorPos(this.windowHandle, screenX, screenY);
        }
        GLFW.glfwSetInputMode(this.windowHandle, GLFW.GLFW_CURSOR, grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        if (!grab) {
            double screenX = last_x / Display.getContentScaleX();
            double screenY = (Display.getHeight() - last_y) / Display.getContentScaleY();
            if (isWayland() && WaylandCenterCursor.isAvailable()) {
                // Set up the warp request; GLFW's next surface commit
                // (in Display.update) will activate it.
                WaylandCenterCursor.setupWarp((int) screenX, (int) screenY);
            } else {
                GLFW.glfwSetCursorPos(this.windowHandle, screenX, screenY);
            }
        }
        this.grabbed = grab;
        this.reset();
    }

    public static boolean isWayland() {
        return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
    }

    @Override
    public boolean hasWheel() {
        return true;
    }

    @Override
    public int getButtonCount() {
        return GLFW.GLFW_MOUSE_BUTTON_LAST + 1;
    }

    @Override
    public boolean isInsideWindow() {
        return this.isInsideWindow;
    }
}
