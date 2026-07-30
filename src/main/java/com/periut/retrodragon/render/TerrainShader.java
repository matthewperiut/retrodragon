package com.periut.retrodragon.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.RetroDragon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Our own terrain program -- the first piece of rendering RetroDragon owns end to end.
 *
 * It exists because two things are impossible in fixed function: correct mip level selection on
 * beta's atlas (needs a coordinate that is continuous across block faces) and any kind of texture
 * supersampling. It is also the step that forces a real vertex/fragment contract, which is what a
 * WebGPU backend will need.
 *
 * GLSL 120 with compatibility built-ins, so it drops into beta's existing fixed-function state
 * (matrices, fog, vertex colour) without re-plumbing any of it. That keeps the GL 2.1 floor, which
 * is the macOS ceiling.
 *
 * Fails soft: any compile or link error disables the shader path for the session and terrain falls
 * back to fixed function.
 */
public final class TerrainShader {
	private static int program;
	private static int uAtlasSize;
	private static int uTileTexels;
	private static int uMaxLod;
	private static int uRgss;
	private static int uFogMode;
	private static boolean broken;
	private static boolean active;

	private TerrainShader() {
	}

	public static boolean isActive() {
		return active;
	}

	/**
	 * Binds the program. Returns false if the caller should use the fixed-function path.
	 *
	 * @param tileTexels the atlas's grid pitch, or 0 for a stitched atlas -- which turns the per-tile
	 *     clamp off, since there is no tile to clamp into
	 */
	public static boolean begin(float atlasWidth, float atlasHeight, float tileTexels, float maxLod) {
		if (broken || !Config.SHADER) {
			return false;
		}
		// This is a GL program, and under WebGPU there is no GL to compile it with: the shim stubs
		// glCompileShader and glGetShaderi returns GL_FALSE, so build() reported three errors about a
		// shader that was never going to exist and then disabled itself for a backend that does not
		// want it. The WebGPU path does this work in its own terrain pipeline instead -- the same
		// atlas parameters go across as uniforms in WebGpuFrame.captureTerrain via TerrainAppearance.
		if (RenderBackend.isWebGpu()) {
			return false;
		}
		if (program == 0 && !build()) {
			return false;
		}
		GL20.glUseProgram(program);
		GL20.glUniform2f(uAtlasSize, atlasWidth, atlasHeight);
		GL20.glUniform1f(uTileTexels, tileTexels);
		GL20.glUniform1f(uMaxLod, tileTexels > 0.0F ? maxLod : 0.0F);
		GL20.glUniform1f(uRgss, Config.RGSS && tileTexels > 0.0F ? 1.0F : 0.0F);
		GL20.glUniform1i(uFogMode, FogState.mode());
		active = true;
		return true;
	}

	public static void end() {
		if (active) {
			GL20.glUseProgram(0);
			active = false;
		}
	}

	private static boolean build() {
		int vertex = 0;
		int fragment = 0;
		try {
			vertex = compile(GL20.GL_VERTEX_SHADER, "terrain.vsh");
			fragment = compile(GL20.GL_FRAGMENT_SHADER, "terrain.fsh");
			if (vertex == 0 || fragment == 0) {
				return fail(null);
			}
			int id = GL20.glCreateProgram();
			GL20.glAttachShader(id, vertex);
			GL20.glAttachShader(id, fragment);
			GL20.glLinkProgram(id);
			if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				String log = GL20.glGetProgramInfoLog(id, 2048);
				GL20.glDeleteProgram(id);
				return fail("link failed: " + log);
			}
			program = id;
			// The sampler stays on unit 0, which is where beta binds terrain.png.
			GL20.glUseProgram(program);
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "atlas"), 0);
			GL20.glUseProgram(0);
			uAtlasSize = GL20.glGetUniformLocation(program, "atlasSize");
			uTileTexels = GL20.glGetUniformLocation(program, "tileTexels");
			uMaxLod = GL20.glGetUniformLocation(program, "maxLod");
			uRgss = GL20.glGetUniformLocation(program, "rgss");
			uFogMode = GL20.glGetUniformLocation(program, "fogMode");
			RetroDragon.detail("terrain shader active");
			return true;
		} catch (Throwable t) {
			return fail(String.valueOf(t));
		} finally {
			if (vertex != 0) {
				GL20.glDeleteShader(vertex);
			}
			if (fragment != 0) {
				GL20.glDeleteShader(fragment);
			}
		}
	}

	private static int compile(int type, String name) throws IOException {
		String source;
		try (InputStream in = TerrainShader.class.getResourceAsStream("/assets/retrodragon/shaders/" + name)) {
			if (in == null) {
				RetroDragon.LOGGER.error("shader resource missing: {}", name);
				return 0;
			}
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int id = GL20.glCreateShader(type);
		GL20.glShaderSource(id, source);
		GL20.glCompileShader(id);
		if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			RetroDragon.LOGGER.error("{} failed to compile: {}", name, GL20.glGetShaderInfoLog(id, 2048));
			GL20.glDeleteShader(id);
			return 0;
		}
		return id;
	}

	private static boolean fail(String message) {
		broken = true;
		if (message != null) {
			RetroDragon.LOGGER.error("terrain shader disabled ({}), falling back to fixed function", message);
		} else {
			RetroDragon.LOGGER.error("terrain shader disabled, falling back to fixed function");
		}
		return false;
	}
}
