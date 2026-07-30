package com.periut.retrodragon.mixin;

import org.lwjgl.opengl.ARBOcclusionQuery;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A mixin with no members, whose only job is to make {@link GlPlugin} see these classes.
 *
 * <p>A mixin config plugin's {@code preApply} is called once per class that the config's mixins
 * target -- so naming a class here is what gives the plugin the chance to rewrite it. The rewriting
 * cannot be done with ordinary injectors, because LWJGL 3.4 declares most of the GL API
 * {@code native} and a native method has no bytecode to inject into; see {@link GlPlugin}.
 *
 * <p>Listing a class here costs nothing under the GL backend: the plugin checks which backend won
 * and returns immediately.
 */
@Mixin(value = {
	GL11.class,
	GL12.class,
	GL13.class,
	GL14.class,
	GL15.class,
	GL20.class,
	GL30.class,
	ARBOcclusionQuery.class,
	ARBVertexBufferObject.class,
	ARBShaderObjects.class,
}, remap = false)
public class GlOwnershipMixin {
}
