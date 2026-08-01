package com.periut.retrodragon.render;

import com.periut.retrodragon.RetroDragon;

/**
 * Apple MetalFX spatial upscaling, for the WebGPU backend on macOS.
 *
 * <h2>Why this one and not DLSS or XeSS</h2>
 *
 * {@code MTLFXSpatialScaler} needs no motion vectors, no depth and no jitter. Every other
 * vendor upscaler worth having -- FSR 2 and 3, XeSS, DLSS -- is temporal and needs all three, none
 * of which this renderer produces, and producing them means threading a previous-frame transform
 * through a fixed-function translation layer that has no notion of object identity across frames.
 *
 * <p>The second reason is reachability. DLSS targets D3D11/D3D12/Vulkan and has no OpenGL path and no
 * macOS build at all; XeSS is in the same position. Neither can be called from either of this mod's
 * backends. MetalFX can, because Dawn can share textures with Metal through IOSurface and this jar
 * already ships the bindings for it ({@code WGPUSharedTextureMemoryIOSurfaceDescriptor},
 * {@code WGPUSharedFenceMTLSharedEventDescriptor}).
 *
 * <h2>Status: probe only</h2>
 *
 * {@link #isSupported()} performs the real platform and framework check, but {@link #IMPLEMENTED} is
 * false until the interop below is written, so the filter is never advertised by
 * {@code RenderScale.availableFilters} and a mod asking for it falls back rather than getting a
 * silently broken resolve. Flipping that one constant is what turns it on.
 *
 * <p>What remains, in order:
 *
 * <ol>
 *   <li>Allocate the low-res world target and the high-res output as IOSurface-backed
 *       {@code MTLTexture}s.</li>
 *   <li>Import both into WebGPU with {@code wgpuDeviceImportSharedTextureMemory} and create wgpu
 *       textures from them, so the existing world pass renders into the low-res one unchanged.</li>
 *   <li>{@code EndAccess} the low-res texture and export the resulting shared fence as an
 *       {@code MTLSharedEvent}.</li>
 *   <li>On a Metal command buffer, wait on that event, run {@code MTLFXSpatialScaler}, signal a
 *       second event.</li>
 *   <li>{@code BeginAccess} the high-res texture against that second fence and composite it to the
 *       swapchain, then draw the GUI over it at native resolution.</li>
 * </ol>
 *
 * <p><b>The hazard to respect while doing that.</b> Dawn does not expose its own {@code MTLDevice}
 * through the public header, so step 4 runs on a device created here with
 * {@code MTLCreateSystemDefaultDevice}. On Apple Silicon that is the same physical GPU and IOSurface
 * sharing is fine, but the two devices are then synchronised entirely by those shared events. A
 * missed wait is not a visual glitch, it is the compositor holding a surface that is being written
 * underneath it, which is the same failure class that hung this machine twice already (see
 * {@code WebGpuRenderer} and {@code Sdl3Window.destroy}). Every access must be fenced in both
 * directions before {@link #IMPLEMENTED} flips.
 */
public final class MetalFxScaler {

	/**
	 * Guards the whole feature until the interop above exists.
	 *
	 * <p>Deliberately separate from {@link #isSupported()}'s platform probe: the probe is already
	 * correct and testable, and keeping the two apart means turning this on is a one-line change with
	 * an obvious blast radius rather than an edit to a condition someone has to re-derive.
	 */
	private static final boolean IMPLEMENTED = false;

	private static Boolean probed;

	private MetalFxScaler() {
	}

	/**
	 * Whether this run could use MetalFX: macOS, the WebGPU backend, and a MetalFX framework new
	 * enough to have a spatial scaler.
	 *
	 * <p>The framework check is a real ObjC class lookup rather than an OS version comparison, which
	 * is both cheaper to get right and the thing actually being depended on.
	 */
	public static boolean isSupported() {
		if (!IMPLEMENTED) {
			return false;
		}
		Boolean cached = probed;
		if (cached != null) {
			return cached;
		}
		boolean available = probe();
		probed = available;
		return available;
	}

	private static boolean probe() {
		// Sdl3Window.MACOS is package-private, and widening it for this would put a platform flag on
		// that class's public surface for one caller. Same expression, same cost.
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
			return false;
		}
		if (!RenderBackend.isWebGpu()) {
			// The GL backend has no Metal texture to hand MetalFX, and no way to get one.
			return false;
		}
		try {
			long cls = org.lwjgl.system.macosx.ObjCRuntime.objc_getClass("MTLFXSpatialScalerDescriptor");
			if (cls == 0L) {
				RetroDragon.detail("MetalFX unavailable: MTLFXSpatialScalerDescriptor not found");
				return false;
			}
			return true;
		} catch (Throwable t) {
			RetroDragon.detail("MetalFX probe failed: " + t);
			return false;
		}
	}
}
