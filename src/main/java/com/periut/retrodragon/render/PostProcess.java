package com.periut.retrodragon.render;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.RetroDragon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Renders the frame to an offscreen colour texture, then resolves it to the window through FXAA.
 *
 * This replaces MSAA, which is unusable here: MSAA anti-aliases every quad boundary, and beta's
 * terrain is thousands of adjacent coplanar 1x1 quads, so it opens a one-pixel seam on the edge of
 * every block. A post-process pass reads the already-flattened image and cannot do that.
 *
 * Fails soft: any shader or framebuffer problem disables the pass for the session and the frame is
 * presented normally.
 */
public final class PostProcess {
	private static int fbo;
	private static int colorTexture;
	private static int depthBuffer;
	private static int width;
	private static int height;

	private static int program;
	private static int uRcpFrame;
	private static int uThreshold;
	private static int uThresholdMin;

	private static boolean broken;
	private static boolean active;

	private PostProcess() {
	}

	/** @return true if the caller must call {@link #resolve}. */
	public static boolean begin(int frameWidth, int frameHeight) {
		if (broken || !Config.FXAA || frameWidth <= 0 || frameHeight <= 0) {
			return false;
		}
		if (!ensureTarget(frameWidth, frameHeight) || !ensureProgram()) {
			return false;
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		active = true;
		return true;
	}

	public static void resolve() {
		if (!active) {
			return;
		}
		active = false;
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

		// Save every enable/blend/depth/texture/viewport bit this pass is about to stomp. The HUD
		// draws IMMEDIATELY after this call and beta never re-enables GL_BLEND for it -- it assumes
		// the state the world left. Without this, the font sheet and icons.png render unblended, and
		// both store black RGB under their transparent texels, so text and the hearts came out as
		// black boxes.
		GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
			| GL11.GL_TEXTURE_BIT | GL11.GL_VIEWPORT_BIT);

		// Beta leaves arbitrary fixed-function state behind; the fullscreen pass needs its own.
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glColorMask(true, true, true, true);
		GL11.glDepthMask(false);
		GL11.glViewport(0, 0, width, height);

		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		GL11.glOrtho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();

		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);

		GL20.glUseProgram(program);
		GL20.glUniform2f(uRcpFrame, 1.0F / width, 1.0F / height);
		GL20.glUniform1f(uThreshold, Config.FXAA_THRESHOLD);
		GL20.glUniform1f(uThresholdMin, Config.FXAA_THRESHOLD_MIN);

		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(0.0F, 0.0F);
		GL11.glVertex2f(0.0F, 0.0F);
		GL11.glTexCoord2f(1.0F, 0.0F);
		GL11.glVertex2f(1.0F, 0.0F);
		GL11.glTexCoord2f(1.0F, 1.0F);
		GL11.glVertex2f(1.0F, 1.0F);
		GL11.glTexCoord2f(0.0F, 1.0F);
		GL11.glVertex2f(0.0F, 1.0F);
		GL11.glEnd();

		GL20.glUseProgram(0);

		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPopMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPopMatrix();
		GL11.glPopAttrib();
	}

	private static boolean ensureTarget(int frameWidth, int frameHeight) {
		if (fbo != 0 && frameWidth == width && frameHeight == height) {
			return true;
		}
		free();
		width = frameWidth;
		height = frameHeight;

		fbo = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

		colorTexture = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
		// FXAA samples between texels, so this must be LINEAR, and clamped so edge taps do not wrap.
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D, colorTexture, 0);

		depthBuffer = GL30.glGenRenderbuffers();
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL30.GL_RENDERBUFFER, depthBuffer);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			RetroDragon.LOGGER.warn("FXAA framebuffer incomplete (0x{}), disabled",
				Integer.toHexString(status));
			free();
			broken = true;
			return false;
		}
		RetroDragon.detail("FXAA enabled at {}x{}", width, height);
		return true;
	}

	private static boolean ensureProgram() {
		if (program != 0) {
			return true;
		}
		int vertex = compile(GL20.GL_VERTEX_SHADER, "post.vsh");
		int fragment = compile(GL20.GL_FRAGMENT_SHADER, "fxaa.fsh");
		try {
			if (vertex == 0 || fragment == 0) {
				broken = true;
				return false;
			}
			int id = GL20.glCreateProgram();
			GL20.glAttachShader(id, vertex);
			GL20.glAttachShader(id, fragment);
			GL20.glLinkProgram(id);
			if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
				RetroDragon.LOGGER.error("FXAA link failed: {}", GL20.glGetProgramInfoLog(id, 2048));
				GL20.glDeleteProgram(id);
				broken = true;
				return false;
			}
			program = id;
			GL20.glUseProgram(program);
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0);
			GL20.glUseProgram(0);
			uRcpFrame = GL20.glGetUniformLocation(program, "rcpFrame");
			uThreshold = GL20.glGetUniformLocation(program, "edgeThreshold");
			uThresholdMin = GL20.glGetUniformLocation(program, "edgeThresholdMin");
			return true;
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
		try (InputStream in = PostProcess.class.getResourceAsStream("/assets/retrodragon/shaders/" + name)) {
			if (in == null) {
				RetroDragon.LOGGER.error("shader resource missing: {}", name);
				return 0;
			}
			int id = GL20.glCreateShader(type);
			GL20.glShaderSource(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			GL20.glCompileShader(id);
			if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
				RetroDragon.LOGGER.error("{} failed to compile: {}", name, GL20.glGetShaderInfoLog(id, 2048));
				GL20.glDeleteShader(id);
				return 0;
			}
			return id;
		} catch (Exception e) {
			RetroDragon.LOGGER.error("shader load failed: {}", name, e);
			return 0;
		}
	}

	private static void free() {
		if (colorTexture != 0) {
			GL11.glDeleteTextures(colorTexture);
			colorTexture = 0;
		}
		if (depthBuffer != 0) {
			GL30.glDeleteRenderbuffers(depthBuffer);
			depthBuffer = 0;
		}
		if (fbo != 0) {
			GL30.glDeleteFramebuffers(fbo);
			fbo = 0;
		}
	}
}
