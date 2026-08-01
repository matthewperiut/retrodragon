package com.periut.retrodragon.render;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.periut.retrodragon.RetroDragon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Draws the world's offscreen colour target onto the window, resampling it with the filter
 * {@link RenderScale} currently names. The GL half of render scale.
 *
 * <p>Called from {@code PostProcess.resolve}, which already owns the framebuffer, the state save and
 * the fullscreen quad -- this only picks a program and sets its uniforms, so there is exactly one
 * place in the GL backend that knows how to get an offscreen frame onto the screen.
 *
 * <h2>Integer snapping is geometry, not sampling</h2>
 *
 * {@link RenderScale#INTEGER} uses the same shader as {@link RenderScale#NEAREST}. What differs is
 * the rectangle it is drawn into: the ratio is floored to a whole number and the remainder becomes a
 * border. Doing it in the destination rectangle rather than in the sampler is what makes it exact --
 * every source texel covers precisely the same number of destination pixels, which is the whole
 * point and is not something a fragment shader can arrange for itself.
 *
 * <h2>Failure is soft</h2>
 *
 * A program that will not compile disables that ONE filter for the session and falls back to point
 * sampling, rather than disabling render scale or taking the frame down. A resample that is uglier
 * than intended is a far better outcome than a black screen, and the fallback is always available
 * because point sampling is the simplest shader here.
 */
public final class ScaleResolve {

	/** Compiled programs by filter name; a null VALUE means "tried and failed, do not retry". */
	private static final Map<String, Program> PROGRAMS = new HashMap<>();

	private static final float FSR_SHARPNESS =
		Float.parseFloat(System.getProperty("retrodragon.fsrSharpness", "0.25"));

	private ScaleResolve() {
	}

	private static final class Program {
		int id;
		int uSrcSize;
		int uSharpness;
	}

	/**
	 * Resolves {@code texture} ({@code srcW} x {@code srcH}) onto the currently bound framebuffer at
	 * {@code dstW} x {@code dstH}.
	 *
	 * <p>The caller has already set the viewport, disabled everything that would interfere and pushed
	 * the attribute state. This leaves the program bound at 0 on the way out.
	 */
	public static void draw(int texture, int srcW, int srcH, int dstW, int dstH, String filter) {
		Program program = program(filter);
		if (program == null) {
			// Even point sampling failed to compile. Nothing sensible is left; the frame is skipped
			// rather than drawn with whatever program happened to be bound.
			return;
		}

		// Point-sampled filters want the attachment itself sampled without interpolation, so a tap
		// that lands fractionally between texels cannot smear. The shader snaps to texel centres too,
		// so this is belt and braces -- but the two disagree on a driver that rounds differently, and
		// pixel art is exactly where that shows.
		boolean point = RenderScale.NEAREST.equals(filter) || RenderScale.INTEGER.equals(filter);
		int mode = point ? GL11.GL_NEAREST : GL11.GL_LINEAR;
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mode);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mode);

		GL20.glUseProgram(program.id);
		if (program.uSrcSize >= 0) {
			GL20.glUniform2f(program.uSrcSize, srcW, srcH);
		}
		if (program.uSharpness >= 0) {
			GL20.glUniform1f(program.uSharpness, FSR_SHARPNESS);
		}

		// The destination rectangle, in the 0..1 ortho the caller set up. Only integer snapping uses
		// anything other than the full quad.
		float x0 = 0.0F;
		float y0 = 0.0F;
		float x1 = 1.0F;
		float y1 = 1.0F;
		if (RenderScale.INTEGER.equals(filter)) {
			int ratio = Math.max(1, Math.min(dstW / Math.max(1, srcW), dstH / Math.max(1, srcH)));
			float spanX = (float) (srcW * ratio) / dstW;
			float spanY = (float) (srcH * ratio) / dstH;
			// Centred, so the border is split evenly rather than piling up on one side.
			x0 = (1.0F - spanX) * 0.5F;
			y0 = (1.0F - spanY) * 0.5F;
			x1 = x0 + spanX;
			y1 = y0 + spanY;
			if (spanX < 1.0F || spanY < 1.0F) {
				// Whatever the border exposes is undefined otherwise: the window still holds the
				// previous frame's world there, which reads as a smear at the edges.
				GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			}
		}

		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(0.0F, 0.0F);
		GL11.glVertex2f(x0, y0);
		GL11.glTexCoord2f(1.0F, 0.0F);
		GL11.glVertex2f(x1, y0);
		GL11.glTexCoord2f(1.0F, 1.0F);
		GL11.glVertex2f(x1, y1);
		GL11.glTexCoord2f(0.0F, 1.0F);
		GL11.glVertex2f(x0, y1);
		GL11.glEnd();

		GL20.glUseProgram(0);
	}

	/** The program for a filter, compiling it on first use, falling back to point sampling. */
	private static Program program(String filter) {
		String wanted = shaderFor(filter);
		if (PROGRAMS.containsKey(wanted)) {
			Program cached = PROGRAMS.get(wanted);
			if (cached != null) {
				return cached;
			}
			// Known-broken. Fall through to point, unless point is what already failed.
			return wanted.equals(shaderFor(RenderScale.NEAREST))
				? null
				: program(RenderScale.NEAREST);
		}
		Program built = build(wanted);
		PROGRAMS.put(wanted, built);
		if (built != null) {
			return built;
		}
		RetroDragon.LOGGER.warn("scale filter '{}' unavailable; falling back to point sampling", filter);
		return wanted.equals(shaderFor(RenderScale.NEAREST)) ? null : program(RenderScale.NEAREST);
	}

	private static String shaderFor(String filter) {
		return switch (filter) {
			case RenderScale.BILINEAR -> "scale_bilinear.fsh";
			case RenderScale.BICUBIC -> "scale_bicubic.fsh";
			case RenderScale.FSR1 -> "scale_fsr1.fsh";
			// NEAREST and INTEGER share a shader; see the class notes.
			default -> "scale_point.fsh";
		};
	}

	private static Program build(String fragmentName) {
		int vertex = compile(GL20.GL_VERTEX_SHADER, "post.vsh");
		int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentName);
		try {
			if (vertex == 0 || fragment == 0) {
				return null;
			}
			int id = GL20.glCreateProgram();
			GL20.glAttachShader(id, vertex);
			GL20.glAttachShader(id, fragment);
			GL20.glLinkProgram(id);
			if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				RetroDragon.LOGGER.error("{} link failed: {}", fragmentName,
					GL20.glGetProgramInfoLog(id, 2048));
				GL20.glDeleteProgram(id);
				return null;
			}
			Program program = new Program();
			program.id = id;
			GL20.glUseProgram(id);
			GL20.glUniform1i(GL20.glGetUniformLocation(id, "tex"), 0);
			GL20.glUseProgram(0);
			program.uSrcSize = GL20.glGetUniformLocation(id, "srcSize");
			program.uSharpness = GL20.glGetUniformLocation(id, "sharpness");
			RetroDragon.detail("scale resolve program ready: {}", fragmentName);
			return program;
		} finally {
			if (vertex != 0) {
				GL20.glDeleteShader(vertex);
			}
			if (fragment != 0) {
				GL20.glDeleteShader(fragment);
			}
		}
	}

	private static int compile(int type, String name) {
		try (InputStream in = ScaleResolve.class
				.getResourceAsStream("/assets/retrodragon/shaders/" + name)) {
			if (in == null) {
				RetroDragon.LOGGER.error("shader resource missing: {}", name);
				return 0;
			}
			int id = GL20.glCreateShader(type);
			GL20.glShaderSource(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			GL20.glCompileShader(id);
			if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
				RetroDragon.LOGGER.error("{} failed to compile: {}", name,
					GL20.glGetShaderInfoLog(id, 2048));
				GL20.glDeleteShader(id);
				return 0;
			}
			return id;
		} catch (Exception e) {
			RetroDragon.LOGGER.error("shader load failed: {}", name, e);
			return 0;
		}
	}
}
