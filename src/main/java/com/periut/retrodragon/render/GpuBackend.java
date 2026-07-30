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
		try {
			context = WebGPUContext.create();
			status = "ready on " + context.backendName();
			System.out.println("[RetroDragon] WebGPU " + status);
		} catch (Throwable t) {
			// Throwable, not Exception: a missing or mismatched native surfaces as
			// UnsatisfiedLinkError, which is exactly the case that must not take the game down.
			status = "unavailable (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
			System.out.println("[RetroDragon] WebGPU " + status + " -- continuing on OpenGL");
			context = null;
		}
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
