package com.periut.retrodragon.api;

import com.periut.retrodragon.gpu.Frame;
import com.periut.retrodragon.gpu.WebGPUContext;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The frame being replayed, handed to a {@link ShaderExtension}'s render hooks.
 *
 * <p>Everything here is valid only for the duration of the call. A hook runs BETWEEN render passes,
 * with the frame's command encoder open and no pass on it -- which is the only point a full-screen
 * pass, a texture copy or a compute dispatch can be recorded. Holding any of it past the call means
 * writing into an encoder that has already been submitted.
 *
 * <h2>Why the hooks are here and not where the game draws</h2>
 *
 * Beta issues its draws immediately, but this renderer records them and replays the whole frame at
 * the buffer swap. So "after the opaque world, before translucents" is not a moment during the
 * game's code at all -- by then nothing has been submitted. It is a position in the recorded list,
 * which the engine finds from the phase stamped on each batch and calls back at.
 */
public interface ShaderFrame {
	WebGPUContext context();

	/** The frame's command encoder, with no render pass open. */
	Frame frame();

	/** An arena that closes at the end of the frame -- for pass descriptors and scratch structs. */
	Arena arena();

	int width();

	int height();

	/**
	 * The colour attachment the world was rendered into.
	 *
	 * <p>The extension's own target when it asked for one through
	 * {@link ShaderApi#requestWorldTarget}, and the swapchain otherwise -- so a composite hook that
	 * reads it works either way.
	 */
	MemorySegment worldView();

	/** The world's depth attachment, shared by the world and the GUI as beta expects. */
	MemorySegment depthView();

	/** The swapchain image this frame presents. A composite chain's last pass writes here. */
	MemorySegment outputView();

	int outputFormat();

	/** The extension's resource group; set uniforms and textures on it before opening a pass. */
	ShaderResources resources();

	/**
	 * The engine-managed colour targets, indexed as the extension requested them.
	 *
	 * <p>Index 0 is the world target itself. Higher indices are the auxiliary attachments the world
	 * pass wrote alongside it -- a G-buffer, in the usual arrangement.
	 *
	 * @return the sampled view, or {@link MemorySegment#NULL} if that index was never requested
	 */
	MemorySegment targetView(int index);

	int targetFormat(int index);

	/** How many frames the engine has presented; a stable counter for temporal effects. */
	long frameCounter();

	/**
	 * Whether this frame drew any world at all.
	 *
	 * <p>False on the title screen, in a menu with no world behind it, and on the loading screen. A
	 * skybox and a composite chain must both stand down there: there is nothing to tonemap, and
	 * painting the screen anyway means grading the main menu -- which comes out looking exactly like
	 * a gamma bug, because it is one.
	 */
	boolean hasWorld();
}
