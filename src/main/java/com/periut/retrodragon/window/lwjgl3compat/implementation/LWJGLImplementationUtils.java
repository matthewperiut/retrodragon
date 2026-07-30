package com.periut.retrodragon.window.lwjgl3compat.implementation;

import com.periut.retrodragon.window.lwjgl3compat.implementation.glfw.GLFWKeyboardImplementation;
import com.periut.retrodragon.window.lwjgl3compat.implementation.glfw.GLFWMouseImplementation;
import com.periut.retrodragon.window.lwjgl3compat.implementation.glfw.VirtualGLFWMouseImplementation;
import com.periut.retrodragon.window.lwjgl3compat.implementation.input.CombinedInputImplementation;
import com.periut.retrodragon.window.lwjgl3compat.implementation.input.InputImplementation;
import com.periut.retrodragon.window.lwjgl3compat.implementation.input.MouseImplementation;
import org.lwjgl.glfw.GLFW;

/**
 * @author Zarzelcow
 * @created 28/09/2022 - 3:12 PM
 */
public class LWJGLImplementationUtils {
    private static final boolean allowVirtualCursor = Boolean.getBoolean("legacy_lwjgl3.allow_virtual_cursor") || System.getenv("LEGACY_LWJGL3_ALLOW_VIRTUAL_CURSOR") != null;
    private static InputImplementation _inputImplementation;

    public static InputImplementation getOrCreateInputImplementation() {
        if (_inputImplementation == null) {
            _inputImplementation = createImplementation();
        }
        return _inputImplementation;
    }

    private static InputImplementation createImplementation() {
        MouseImplementation mouse = allowVirtualCursor && GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND ?
                VirtualGLFWMouseImplementation.getInstance() :
                new GLFWMouseImplementation();
        return new CombinedInputImplementation(new GLFWKeyboardImplementation(), mouse);
    }

}
