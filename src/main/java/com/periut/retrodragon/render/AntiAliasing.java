package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.RetroDragon;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Multisample anti-aliasing for the whole frame, resolved to the window at the end.
 *
 * Three distinct aliasing sources need three different fixes, which is why MSAA alone is not
 * enough to match a modern renderer:
 *
 *  - **Block edges / geometry silhouettes** -- MSAA. Handled here.
 *  - **Cutout transparency (leaves, grass, glass panes)** -- MSAA does nothing for these, because
 *    the jaggedness is inside the triangle, produced by the alpha test discarding whole fragments.
 *    The fix is alpha-to-coverage: the alpha value becomes a sample coverage mask, so a partially
 *    covered leaf texel lights a fraction of the samples. Handled here.
 *  - **Texture interiors at distance** (shimmering stone/plank patterns) -- needs mipmaps, which are
 *    a texture-upload concern rather than a framebuffer one. Not handled here.
 *
 * The window itself cannot provide MSAA: starac creates the GL context through GLFW and never
 * passes GLFW_SAMPLES, and PixelFormat.samples is ignored. So we render into our own multisample
 * framebuffer and blit-resolve. That is better anyway -- it keeps the sample count under our control
 * and independent of the window pixel format.
 */
public final class AntiAliasing {
	private static int fbo;
	private static int colorBuffer;
	private static int depthBuffer;
	private static int width;
	private static int height;
	private static int samples;
	private static boolean unavailable;
	private static boolean active;

	private AntiAliasing() {
	}

	/**
	 * Binds the multisample framebuffer for this frame.
	 *
	 * @return true if the caller must call {@link #resolve} before presenting.
	 */
	public static boolean begin(int frameWidth, int frameHeight) {
		if (unavailable || Config.MSAA_SAMPLES <= 1 || frameWidth <= 0 || frameHeight <= 0) {
			return false;
		}
		if (!ensureTarget(frameWidth, frameHeight)) {
			return false;
		}
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL11.glEnable(GL13.GL_MULTISAMPLE);
		active = true;
		return true;
	}

	/**
	 * Enables alpha-to-coverage for a cutout pass, and only for that pass.
	 *
	 * This must never be on globally. Alpha-to-coverage reinterprets a fragment's alpha as a sample
	 * coverage mask, so any genuinely *translucent* draw -- water, the sky gradient -- stops blending
	 * and becomes a stipple pattern instead. Verified: enabling it for the whole frame put a visible
	 * checkerboard over the entire image.
	 */
	public static void beginCutout() {
		if (active && Config.ALPHA_TO_COVERAGE) {
			GL11.glEnable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
		}
	}

	public static void endCutout() {
		if (active && Config.ALPHA_TO_COVERAGE) {
			GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
		}
	}

	/** Resolves the multisample buffer into the window framebuffer. */
	public static void resolve() {
		if (!active) {
			return;
		}
		active = false;
		// Restore what the blit disables: the HUD draws straight after and inherits this state.
		GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
		GL11.glDisable(GL13.GL_MULTISAMPLE);
		// glBlitFramebuffer is clipped by the draw framebuffer's scissor, and beta leaves scissor
		// state behind from GUI rendering -- a stale box would crop the resolve to a fragment of
		// the screen. Disabling it here is not optional.
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
		GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GL11.glPopAttrib();
	}

	private static boolean ensureTarget(int frameWidth, int frameHeight) {
		int wanted = Math.max(2, Config.MSAA_SAMPLES);
		if (fbo != 0 && frameWidth == width && frameHeight == height && wanted == samples) {
			return true;
		}
		free();
		width = frameWidth;
		height = frameHeight;
		samples = Math.min(wanted, GL11.glGetInteger(GL30.GL_MAX_SAMPLES));
		if (samples < 2) {
			RetroDragon.LOGGER.warn("multisampling unsupported by this driver, anti-aliasing off");
			unavailable = true;
			return false;
		}

		fbo = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

		colorBuffer = GL30.glGenRenderbuffers();
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorBuffer);
		GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL11.GL_RGBA8, width, height);
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL30.GL_RENDERBUFFER, colorBuffer);

		depthBuffer = GL30.glGenRenderbuffers();
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
		GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL14.GL_DEPTH_COMPONENT24, width, height);
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL30.GL_RENDERBUFFER, depthBuffer);

		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			RetroDragon.LOGGER.warn("multisample framebuffer incomplete (0x{}), anti-aliasing off",
				Integer.toHexString(status));
			free();
			unavailable = true;
			return false;
		}
		RetroDragon.detail("anti-aliasing: {}x MSAA at {}x{}{}", samples, width, height,
			Config.ALPHA_TO_COVERAGE ? " + alpha-to-coverage" : "");
		return true;
	}

	private static void free() {
		if (colorBuffer != 0) {
			GL30.glDeleteRenderbuffers(colorBuffer);
			colorBuffer = 0;
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
