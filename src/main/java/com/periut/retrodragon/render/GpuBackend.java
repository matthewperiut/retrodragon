package com.periut.retrodragon.render;

import com.periut.retrodragon.Config;
import com.periut.retrodragon.gpu.WebGPUContext;

/**
 * The WebGPU device RetroDragon renders through, once it renders through one.
 *
 * <p>Right now this only stands the device up and reports what it resolved to; nothing draws yet.
 * That is deliberate -- bringing the backend online is separable from moving geometry onto it, and
 * doing it first means the shim work that follows has something real to target instead of being
 * written blind.
 *
 * <p>Failure is never fatal. A machine with no working Dawn (an old driver, a headless CI box, a
 * platform whose native we do not ship) must still run the GL path exactly as before, so every
 * problem here degrades to {@link #available()} returning false.
 */
public final class GpuBackend {
	private static WebGPUContext context;
	private static boolean attempted;
	private static String status = "not initialized";

	private GpuBackend() {
	}

	/**
	 * Brings the device up once, on first call. Safe to call from anywhere; safe to call after a
	 * previous failure (it will not retry, because a second failure is never a different failure).
	 */
	public static synchronized void init() {
		if (attempted) {
			return;
		}
		attempted = true;

		if (!Config.WEBGPU) {
			status = "disabled by config";
			return;
		}
		// Neither outcome is logged here: RenderBackend.settle() is the only caller and reports the
		// API that was actually settled on, status and all. Two lines for one decision is how the
		// startup log got noisy.
		try {
			context = WebGPUContext.create();
			status = "ready on " + context.backendName();
		} catch (Throwable t) {
			// Throwable, not Exception: a missing or mismatched native surfaces as
			// UnsatisfiedLinkError, which is exactly the case that must not take the game down.
			status = "unavailable (" + describe(t) + ")";
			context = null;
		}
	}

	/**
	 * The throwable, and its causes, as one line.
	 *
	 * <p>Following the cause chain rather than printing {@code t.getMessage()} alone. A class-init
	 * failure anywhere under {@code com.periut.webgpu} arrives as {@code ExceptionInInitializerError},
	 * whose own message is always null -- the entire explanation lives in the cause. Reporting just
	 * the message gave "unavailable (ExceptionInInitializerError: null)", which says only that
	 * something went wrong somewhere, and cost a Windows-only 32-bit-C-`long` layout cast (see
	 * {@code webgpu_h$shared.C_LONG}) rather more debugging than it should have.
	 */
	private static String describe(Throwable t) {
		StringBuilder out = new StringBuilder();
		// Bounded, and identity-tracked: a self-referential or cyclic cause chain must not spin here.
		java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Throwable at = t; at != null && seen.add(at) && seen.size() <= 8; at = at.getCause()) {
			if (!out.isEmpty()) {
				out.append(" <- ");
			}
			out.append(at.getClass().getSimpleName());
			if (at.getMessage() != null) {
				out.append(": ").append(at.getMessage());
			}
		}
		return out.toString();
	}

	public static boolean available() {
		return context != null;
	}

	/** Null until {@link #init()} succeeds; callers must tolerate that. */
	public static WebGPUContext context() {
		return context;
	}

	/** Human-readable state, for the debug overlay and startup logging. */
	public static String status() {
		return status;
	}

	public static synchronized void shutdown() {
		if (context != null) {
			context.close();
			context = null;
			status = "shut down";
		}
	}
}
