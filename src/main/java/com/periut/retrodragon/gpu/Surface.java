package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUChainedStruct;
import com.periut.webgpu.WGPUSurfaceCapabilities;
import com.periut.webgpu.WGPUSurfaceConfiguration;
import com.periut.webgpu.WGPUSurfaceDescriptor;
import com.periut.webgpu.WGPUSurfaceSourceMetalLayer;
import com.periut.webgpu.WGPUSurfaceSourceWaylandSurface;
import com.periut.webgpu.WGPUSurfaceSourceWindowsHWND;
import com.periut.webgpu.WGPUSurfaceSourceXlibWindow;
import com.periut.webgpu.WGPUSurfaceTexture;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * The window's WebGPU surface and its swapchain configuration.
 *
 * <p>Constructed from the native handles {@code Sdl3Window} already exposes -- which is precisely
 * why SDL3 was chosen over GLFW for the window layer in the first place. On macOS the handle must
 * be a {@code CAMetalLayer}, not the {@code NSWindow}: Dawn's Metal backend attaches to the layer,
 * so the caller supplies one via {@code SDL_Metal_CreateView} / {@code SDL_Metal_GetLayer}.
 *
 * <p><b>The window must have been created without a GL context.</b> A surface cannot be built over
 * a window that already owns one, which is the whole reason the backend is chosen before the window
 * is created rather than after.
 */
public final class Surface implements AutoCloseable {
	private final MemorySegment surface;
	private final WebGPUContext ctx;
	private int format;
	private int width;
	private int height;
	private int alphaMode;
	private int presentMode;
	/** Held between {@link #currentView} and {@link #present} so both can be released exactly once. */
	private MemorySegment currentTexture = MemorySegment.NULL;
	private MemorySegment currentView = MemorySegment.NULL;
	/** Set when the last acquire reported the configuration is stale; the caller should reconfigure. */
	private boolean outdated;

	private Surface(WebGPUContext ctx, MemorySegment surface) {
		this.ctx = ctx;
		this.surface = surface;
	}

	/** macOS: {@code layer} is a CAMetalLayer pointer. */
	public static Surface fromMetalLayer(WebGPUContext ctx, Arena arena, long layer) {
		MemorySegment source = WGPUSurfaceSourceMetalLayer.allocate(arena);
		WGPUChainedStruct.sType(WGPUSurfaceSourceMetalLayer.chain(source),
			WGPUSType_SurfaceSourceMetalLayer());
		WGPUSurfaceSourceMetalLayer.layer(source, MemorySegment.ofAddress(layer));
		return create(ctx, arena, source);
	}

	/** Windows: {@code hwnd} plus the module handle, which Dawn wants for the window class. */
	public static Surface fromWin32(WebGPUContext ctx, Arena arena, long hinstance, long hwnd) {
		MemorySegment source = WGPUSurfaceSourceWindowsHWND.allocate(arena);
		WGPUChainedStruct.sType(WGPUSurfaceSourceWindowsHWND.chain(source),
			WGPUSType_SurfaceSourceWindowsHWND());
		WGPUSurfaceSourceWindowsHWND.hinstance(source, MemorySegment.ofAddress(hinstance));
		WGPUSurfaceSourceWindowsHWND.hwnd(source, MemorySegment.ofAddress(hwnd));
		return create(ctx, arena, source);
	}

	/** X11. */
	public static Surface fromXlib(WebGPUContext ctx, Arena arena, long display, long window) {
		MemorySegment source = WGPUSurfaceSourceXlibWindow.allocate(arena);
		WGPUChainedStruct.sType(WGPUSurfaceSourceXlibWindow.chain(source),
			WGPUSType_SurfaceSourceXlibWindow());
		WGPUSurfaceSourceXlibWindow.display(source, MemorySegment.ofAddress(display));
		WGPUSurfaceSourceXlibWindow.window(source, window);
		return create(ctx, arena, source);
	}

	/** Wayland. */
	public static Surface fromWayland(WebGPUContext ctx, Arena arena, long display, long wlSurface) {
		MemorySegment source = WGPUSurfaceSourceWaylandSurface.allocate(arena);
		WGPUChainedStruct.sType(WGPUSurfaceSourceWaylandSurface.chain(source),
			WGPUSType_SurfaceSourceWaylandSurface());
		WGPUSurfaceSourceWaylandSurface.display(source, MemorySegment.ofAddress(display));
		WGPUSurfaceSourceWaylandSurface.surface(source, MemorySegment.ofAddress(wlSurface));
		return create(ctx, arena, source);
	}

	private static Surface create(WebGPUContext ctx, Arena arena, MemorySegment source) {
		MemorySegment desc = WGPUSurfaceDescriptor.allocate(arena);
		WGPUSurfaceDescriptor.nextInChain(desc, source);
		MemorySegment handle = wgpuInstanceCreateSurface(ctx.instance(), desc);
		if (handle.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuInstanceCreateSurface returned NULL -- is the "
				+ "window GL-free, and is the handle the right kind for this platform?");
		}
		return new Surface(ctx, handle);
	}

	/**
	 * (Re)configures the swapchain. Call on creation and on every resize.
	 *
	 * <p>The format is taken from the surface's own capabilities rather than hardcoded: the
	 * preferred format differs by platform (BGRA8 on Metal, commonly RGBA8 elsewhere) and using the
	 * wrong one costs a per-frame blit at best.
	 */
	public void configure(Arena arena, int width, int height) {
		configure(arena, width, height, WGPUPresentMode_Fifo());
	}

	/**
	 * @param wantedPresentMode one of {@code WGPUPresentMode_*}; falls back to Fifo, the only mode
	 *                          WebGPU guarantees, when the surface does not support it
	 */
	public void configure(Arena arena, int width, int height, int wantedPresentMode) {
		// Configuring replaces the swapchain, so a backbuffer acquired from the OLD one must go back
		// first -- releasing it afterwards returns it to a swapchain that no longer exists, which is
		// the leaked-drawable hazard that wedges the macOS compositor rather than merely leaking.
		// No caller currently reconfigures with a drawable outstanding (WebGpuRenderer resizes before
		// it acquires), but that is an ordering rule in another class and this is where it is cheap to
		// make unconditional.
		releaseCurrent();
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);

		MemorySegment caps = WGPUSurfaceCapabilities.allocate(arena);
		wgpuSurfaceGetCapabilities(surface, ctx.adapter(), caps);
		this.format = preferredColorFormat(WGPUSurfaceCapabilities.formats(caps),
			WGPUSurfaceCapabilities.formatCount(caps), WGPUTextureFormat_BGRA8Unorm());
		// OPAQUE if the surface offers it, because a game window is not a compositing layer. Under
		// premultiplied alpha -- which is what CAMetalLayer reports first on macOS -- a frame whose
		// alpha channel is zero composites to nothing, and beta clears to alpha 0 constantly. The
		// window is then perfectly transparent while the renderer is running correctly, which is an
		// exceptionally confusing thing to debug.
		this.alphaMode = preferred(WGPUSurfaceCapabilities.alphaModes(caps),
			WGPUSurfaceCapabilities.alphaModeCount(caps), WGPUCompositeAlphaMode_Opaque());
		// Fifo is the only mode WebGPU guarantees, so it is the fallback rather than the choice.
		this.presentMode = supports(WGPUSurfaceCapabilities.presentModes(caps),
			WGPUSurfaceCapabilities.presentModeCount(caps), wantedPresentMode)
			? wantedPresentMode
			: WGPUPresentMode_Fifo();

		MemorySegment config = WGPUSurfaceConfiguration.allocate(arena);
		WGPUSurfaceConfiguration.device(config, ctx.device());
		WGPUSurfaceConfiguration.format(config, format);
		// COPY_SRC as well as RENDER_ATTACHMENT so the presented frame can be read back. That is the
		// only way to see what the renderer actually produced when the screen itself cannot be
		// captured -- a screenshot tool without recording permission returns the desktop and nothing
		// else, which looks exactly like a window that renders nothing.
		// COPY_DST as well, so an offscreen frame can be blitted in. That is what lets the renderer
		// run uncapped while presenting at the display's rate: frames are rendered to a texture of
		// our own and only the latest is copied to a drawable.
		WGPUSurfaceConfiguration.usage(config, Flags.TEXTURE_USAGE_RENDER_ATTACHMENT
			| Flags.TEXTURE_USAGE_COPY_SRC | Flags.TEXTURE_USAGE_COPY_DST);
		WGPUSurfaceConfiguration.width(config, this.width);
		WGPUSurfaceConfiguration.height(config, this.height);
		WGPUSurfaceConfiguration.presentMode(config, presentMode);
		WGPUSurfaceConfiguration.alphaMode(config, alphaMode);

		wgpuSurfaceConfigure(surface, config);
		wgpuSurfaceCapabilitiesFreeMembers(caps);
		outdated = false;
	}

	/**
	 * The first advertised format with four 8-bit UNORM channels, or the surface's own first choice
	 * when it offers none.
	 *
	 * <p><b>Not simply {@code formats[0]}.</b> That is the surface's preferred format, and on
	 * Linux/Wayland with RADV the preferred format is {@code RGB10A2Unorm} -- 54 formats advertised,
	 * cycling {@code 30 23 22 28 27 40}, with the packed 10-bit one first. Rendering into it looks
	 * correct on screen, because the compositor knows the layout. Every CPU-side reader of the frame
	 * does not: {@code Readback}, {@code WebGpuRenderer.readPixels}, {@code screenshot} and
	 * {@code Readback.pixel} all decode four independent bytes per pixel, which for a packed
	 * {@code A:2 B:10 G:10 R:10} word gives structurally perfect, wildly miscoloured output -- the
	 * stride is 4 bytes either way, so geometry, rows and the GL flip all survive and only the colour
	 * is nonsense. That made every screenshot and every appearance A/B unusable while the game itself
	 * looked fine, which is a genuinely misleading way to lose a whole diagnostic path.
	 *
	 * <p>sRGB variants are deliberately NOT accepted. They are 8-bit, but the hardware would apply
	 * the sRGB encode on write and beta's colours are authored for a linear UNORM target.
	 *
	 * <p>The 2-bit alpha was a second, quieter consequence: {@code alphaMode} is Opaque so nothing
	 * composited wrong, but any alpha the frame wrote was quantised to four levels.
	 */
	private static int preferredColorFormat(MemorySegment array, long count, int fallback) {
		if (count <= 0 || array.equals(MemorySegment.NULL)) {
			return fallback;
		}
		MemorySegment values = array.reinterpret(count * 4L);
		for (long i = 0; i < count; i++) {
			int candidate = values.getAtIndex(ValueLayout.JAVA_INT, i);
			if (candidate == WGPUTextureFormat_BGRA8Unorm()
					|| candidate == WGPUTextureFormat_RGBA8Unorm()) {
				return candidate;
			}
		}
		// No 8-bit UNORM at all. Take the surface's preference and let the readback be wrong rather
		// than fail to configure: the game rendering is worth more than the screenshots.
		return values.getAtIndex(ValueLayout.JAVA_INT, 0);
	}

	/**
	 * {@code wanted} if the surface supports it, otherwise its first reported option.
	 *
	 * <p>Configuring with a value the surface does not advertise is a validation error, not a silent
	 * downgrade, so a preference has to be checked rather than simply asked for.
	 */
	private static boolean supports(MemorySegment array, long count, int wanted) {
		if (count <= 0 || array.equals(MemorySegment.NULL)) {
			return false;
		}
		MemorySegment values = array.reinterpret(count * 4L);
		for (long i = 0; i < count; i++) {
			if (values.getAtIndex(ValueLayout.JAVA_INT, i) == wanted) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the surface offers a present mode. Queried rather than assumed: configuring with an
	 * unsupported mode is a validation error, and a silent fallback to Fifo makes an uncapped setting
	 * behave exactly like vsync with nothing to say why.
	 */
	public boolean supportsPresentMode(int mode) {
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment caps = WGPUSurfaceCapabilities.allocate(tmp);
			wgpuSurfaceGetCapabilities(surface, ctx.adapter(), caps);
			boolean supported = supports(WGPUSurfaceCapabilities.presentModes(caps),
				WGPUSurfaceCapabilities.presentModeCount(caps), mode);
			wgpuSurfaceCapabilitiesFreeMembers(caps);
			return supported;
		}
	}

	/** The present mode actually in use, which may not be the one asked for. */
	public int presentMode() {
		return presentMode;
	}

	private static int preferred(MemorySegment array, long count, int wanted) {
		if (count <= 0 || array.equals(MemorySegment.NULL)) {
			return wanted;
		}
		MemorySegment values = array.reinterpret(count * 4L);
		for (long i = 0; i < count; i++) {
			if (values.getAtIndex(ValueLayout.JAVA_INT, i) == wanted) {
				return wanted;
			}
		}
		return values.getAtIndex(ValueLayout.JAVA_INT, 0);
	}

	/**
	 * Acquires this frame's backbuffer view.
	 *
	 * <p>The texture and its view are held until {@link #present}, which releases both. Dawn hands
	 * out an owned reference per acquire; dropping it on the floor leaks a full-screen texture every
	 * frame, which on a 4K display is about 32 MB a second.
	 *
	 * @return the texture view to render into, or {@link MemorySegment#NULL} if the frame should be
	 *         skipped -- check {@link #isOutdated()} to decide whether to reconfigure first.
	 */
	public MemorySegment currentView(Arena arena) {
		releaseCurrent();
		MemorySegment st = WGPUSurfaceTexture.allocate(arena);
		wgpuSurfaceGetCurrentTexture(surface, st);
		int status = WGPUSurfaceTexture.status(st);
		MemorySegment texture = WGPUSurfaceTexture.texture(st);

		// Suboptimal still renders correctly -- it just means the swapchain no longer matches the
		// window, typically mid-resize. Render this frame, reconfigure before the next one.
		outdated = status == WGPUSurfaceGetCurrentTextureStatus_Outdated()
			|| status == WGPUSurfaceGetCurrentTextureStatus_Lost()
			|| status == WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal();

		if (texture.equals(MemorySegment.NULL)) {
			return MemorySegment.NULL;
		}
		currentTexture = texture;
		currentView = wgpuTextureCreateView(texture, MemorySegment.NULL);
		return currentView;
	}

	/** True when the swapchain no longer matches the window and {@link #configure} should be re-run. */
	public boolean isOutdated() {
		return outdated;
	}

	/** This frame's backbuffer TEXTURE, valid between {@link #currentView} and {@link #present}. */
	public MemorySegment currentTexture() {
		return currentTexture;
	}

	public void present() {
		wgpuSurfacePresent(surface);
		releaseCurrent();
	}

	private void releaseCurrent() {
		if (!currentView.equals(MemorySegment.NULL)) {
			wgpuTextureViewRelease(currentView);
			currentView = MemorySegment.NULL;
		}
		if (!currentTexture.equals(MemorySegment.NULL)) {
			wgpuTextureRelease(currentTexture);
			currentTexture = MemorySegment.NULL;
		}
	}

	public int format() {
		return format;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/**
	 * Releases the swapchain and the surface.
	 *
	 * <p><b>Two preconditions, and both have cost a hung desktop rather than a crash.</b>
	 *
	 * <p>First, the GPU must be idle. Unconfiguring hands the drawable pool back; doing that while
	 * submitted work is still executing leaves the compositor holding a reference to something being
	 * destroyed. {@code WebGpuFrame.shutdown} drains before it calls anything on this path, and
	 * abandons the whole teardown rather than proceed if the drain fails.
	 *
	 * <p>Second, on macOS this must run on thread 0. Both calls below reach into the window's
	 * {@code CAMetalLayer}, and thread 0 is running AppKit against that same layer on its idle pump
	 * until the window is destroyed. {@code WebGpuRenderer.close} marshals accordingly -- this class
	 * has no window dependency to do it itself, which is the same division of labour that puts
	 * {@code WindowSurface} rather than this class in charge of construction.
	 */
	@Override
	public void close() {
		releaseCurrent();
		if (!surface.equals(MemorySegment.NULL)) {
			wgpuSurfaceUnconfigure(surface);
			wgpuSurfaceRelease(surface);
		}
	}
}
