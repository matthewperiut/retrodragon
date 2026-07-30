package com.periut.retrodragon.window.mixin.lwjgl3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.lwjgl.openal.AL;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import paulscode.sound.libraries.LibraryLWJGLOpenAL;

@Mixin(value = LibraryLWJGLOpenAL.class, remap = false)
public class LibraryLWJGLOpenALMixin {

	@Unique
	private static MethodHandle alExit = null;

	static {
		try {
			//noinspection JavaLangInvokeHandleSignature
			alExit = MethodHandles.lookup().findStatic(AL.class, "exit", MethodType.methodType(void.class));
		} catch (Exception ignored) {
		}
	}

	@Redirect(method = "libraryCompatible", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL;destroy()V", remap = false))
	private static void libraryCompatible$replaceALDestroy() {
		exitAL();
	}

	@Redirect(method = "cleanup", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL;destroy()V"))
	private void cleanup$replaceALDestroy() {
		exitAL();
	}

	@Unique
	private static void exitAL() {
		// RetroCenter: the OpenAL context is shared with the hub; a child
		// instance shutting its sound system down must not destroy it.
		if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance()) {
			return;
		}
		try {
			alExit.invoke();
		} catch (Throwable ignored) {
		}
	}
}
