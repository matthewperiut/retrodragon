package com.periut.retrodragon.window.mixin.lwjgl3;

import com.periut.retrodragon.window.lwjgl3compat.annotations.CreateStub;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.ARBShaderObjects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ARBShaderObjects.class, remap = false)
public abstract class ARBShaderObjectsMixin {
    @CreateStub("glGetObjectParameterARB")
    @Shadow
    public static void glGetObjectParameterivARB(int obj, int pname, IntBuffer params) {
    }

    @CreateStub("glUniformMatrix4ARB")
    @Shadow
    public static void glUniformMatrix4fvARB(int location, boolean transpose, FloatBuffer value) {
    }

    @CreateStub("glUniform1ARB")
    @Shadow
    public static void glUniform1ivARB(int location, IntBuffer value) {
    }
}
