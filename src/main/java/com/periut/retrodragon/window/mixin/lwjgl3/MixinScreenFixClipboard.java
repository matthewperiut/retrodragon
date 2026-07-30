package com.periut.retrodragon.window.mixin.lwjgl3;

import com.periut.retrodragon.window.lwjgl3compat.annotations.Public;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Screen.class)
public class MixinScreenFixClipboard {

	/**
	 * @author moehreag
	 * @reason Fix clipboard access on the LWJGL3 backends
	 *
	 * <p>Goes through {@link Display}, not raw GLFW: {@code Display.getHandle()} is an
	 * {@code SDL_Window*} on the default SDL3 backend and GLFW is never initialized there, so
	 * {@code glfwGetClipboardString(getHandle())} silently returned NULL. {@code selfChecks}
	 * asserts this class carries no GLFW reference.
	 */
	@Overwrite
	public static String getClipboard(){
		return Display.getClipboard();
	}

	/**
	 * @author moehreag
	 * @reason Fix clipboard access on the LWJGL3 backends
	 */
	//@Overwrite
	@Public
	private static void setClipboard(String string){
		Display.setClipboard(string);
	}
}
