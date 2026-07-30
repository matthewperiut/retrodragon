package com.periut.retrodragon.gpu;

import com.periut.webgpu.WGPUAdapterInfo;
import com.periut.webgpu.WGPUDeviceDescriptor;
import com.periut.webgpu.WGPUQueueWorkDoneCallback;
import com.periut.webgpu.WGPUQueueWorkDoneCallbackInfo;
import com.periut.webgpu.WGPURequestAdapterCallback;
import com.periut.webgpu.WGPURequestAdapterCallbackInfo;
import com.periut.webgpu.WGPURequestDeviceCallback;
import com.periut.webgpu.WGPURequestDeviceCallbackInfo;
import com.periut.webgpu.WGPUSupportedFeatures;
import com.periut.webgpu.WGPUUncapturedErrorCallback;
import com.periut.webgpu.WGPUUncapturedErrorCallbackInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static com.periut.webgpu.webgpu_h.*;

/**
 * Owns the WebGPU objects that outlive a frame: instance, adapter, device and queue.
 *
 * <p>Everything else in the renderer hangs off this. It is deliberately separate from surface and
 * swapchain handling, because a device is useful without a window -- headless is how the pipeline
 * and shader permutations get tested in CI, where there is no display at all.
 *
 * <p><b>Threading.</b> A WebGPU device is not thread-affine the way a GL context is, but Dawn's
 * callbacks only run inside {@link #processEvents()}. Call that from whichever thread drives the
 * frame, and do not assume a callback has fired until it has.
 *
 * <p>Held in a long-lived {@link Arena}: the callback stubs registered with Dawn must stay alive as
 * long as the device can invoke them. Letting them be collected turns a later error report into a
 * JVM crash inside native code, which is a spectacularly unhelpful way to learn about a validation
 * error.
 */
public final class WebGPUContext implements AutoCloseable {
	private final Arena arena = Arena.ofShared();
	private MemorySegment instance = MemorySegment.NULL;
	private MemorySegment adapter = MemorySegment.NULL;
	private MemorySegment device = MemorySegment.NULL;
	private MemorySegment queue = MemorySegment.NULL;
	private int backendType = -1;

	private WebGPUContext() {
	}

	/**
	 * Brings up instance, adapter and device, blocking until each resolves.
	 *
	 * @throws IllegalStateException if any stage fails; there is no partial success worth returning.
	 */
	public static WebGPUContext create() {
		WebGPUNatives.load();
		WebGPUContext ctx = new WebGPUContext();
		try {
			ctx.init();
			return ctx;
		} catch (RuntimeException e) {
			ctx.close();
			throw e;
		}
	}

	private void init() {
		instance = wgpuCreateInstance(instanceDescriptor());
		if (instance.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuCreateInstance returned NULL");
		}

		adapter = awaitAdapter();
		MemorySegment info = WGPUAdapterInfo.allocate(arena);
		wgpuAdapterGetInfo(adapter, info);
		backendType = WGPUAdapterInfo.backendType(info);

		device = awaitDevice();
		queue = wgpuDeviceGetQueue(device);
		if (queue.equals(MemorySegment.NULL)) {
			throw new IllegalStateException("wgpuDeviceGetQueue returned NULL");
		}
	}

	private MemorySegment instanceDescriptor() {
		MemorySegment desc = com.periut.webgpu.WGPUInstanceDescriptor.allocate(arena);
		com.periut.webgpu.WGPUInstanceDescriptor.nextInChain(desc, toggles());
		return desc;
	}

	/** Every feature the adapter advertises, for diagnosing what is and is not available. */
	public java.util.List<Integer> features() {
		java.util.List<Integer> names = new java.util.ArrayList<>();
		if (adapter.equals(MemorySegment.NULL)) {
			return names;
		}
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment supported = WGPUSupportedFeatures.allocate(tmp);
			wgpuAdapterGetFeatures(adapter, supported);
			long count = WGPUSupportedFeatures.featureCount(supported);
			MemorySegment list = WGPUSupportedFeatures.features(supported).reinterpret(count * 4L);
			for (long i = 0; i < count; i++) {
				names.add(list.getAtIndex(ValueLayout.JAVA_INT, i));
			}
		}
		return names;
	}

	/**
	 * A {@code WGPUDawnTogglesDescriptor} enabling {@code allow_unsafe_apis}.
	 *
	 * <p>Dawn hides non-standard features behind that toggle -- multi-draw indirect among them, which
	 * it implements on Metal via {@code MTLIndirectCommandBuffer}. The shipped native does contain
	 * it: {@code wgpuRenderPassEncoderMultiDrawIndirect} is an exported symbol, and the binary
	 * carries the string {@code "Feature %s is guarded by toggle allow_unsafe_apis."}. So the
	 * capability is present and the only question is whether it is advertised.
	 *
	 * <p>Chained onto the ADAPTER request rather than the instance. An earlier attempt put it on the
	 * instance descriptor and the adapter still reported 38 features with multi-draw absent, which
	 * is consistent with Dawn evaluating toggles per adapter.
	 */
	private MemorySegment toggles() {
		MemorySegment names = arena.allocate(ValueLayout.ADDRESS, 1);
		names.setAtIndex(ValueLayout.ADDRESS, 0, arena.allocateFrom("allow_unsafe_apis"));

		MemorySegment toggles = com.periut.webgpu.WGPUDawnTogglesDescriptor.allocate(arena);
		com.periut.webgpu.WGPUChainedStruct.sType(
			com.periut.webgpu.WGPUDawnTogglesDescriptor.chain(toggles),
			WGPUSType_DawnTogglesDescriptor());
		com.periut.webgpu.WGPUDawnTogglesDescriptor.enabledToggleCount(toggles, 1);
		com.periut.webgpu.WGPUDawnTogglesDescriptor.enabledToggles(toggles, names);
		return toggles;
	}

	private MemorySegment awaitAdapter() {
		MemorySegment[] out = {MemorySegment.NULL};
		int[] status = {-1};
		MemorySegment cbInfo = WGPURequestAdapterCallbackInfo.allocate(arena);
		WGPURequestAdapterCallbackInfo.mode(cbInfo, WGPUCallbackMode_AllowProcessEvents());
		WGPURequestAdapterCallbackInfo.callback(cbInfo,
			WGPURequestAdapterCallback.allocate((st, ad, msg, u1, u2) -> {
				status[0] = st;
				out[0] = ad;
			}, arena));

		MemorySegment options = com.periut.webgpu.WGPURequestAdapterOptions.allocate(arena);
		com.periut.webgpu.WGPURequestAdapterOptions.nextInChain(options, toggles());
		wgpuInstanceRequestAdapter(arena, instance, options, cbInfo);
		pumpUntil(() -> status[0] != -1, "adapter");
		if (out[0].equals(MemorySegment.NULL)) {
			throw new IllegalStateException("no WebGPU adapter (status " + status[0] + ")");
		}
		return out[0];
	}

	private MemorySegment awaitDevice() {
		MemorySegment desc = WGPUDeviceDescriptor.allocate(arena);
		// Registered up front rather than after creation: a validation error during the very first
		// pipeline build is exactly when you most want to be told, and Dawn reports those through
		// this callback rather than a return code.
		MemorySegment errInfo = WGPUDeviceDescriptor.uncapturedErrorCallbackInfo(desc);
		WGPUUncapturedErrorCallbackInfo.callback(errInfo,
			WGPUUncapturedErrorCallback.allocate((dev, type, msg, u1, u2) -> report(type, msg),
				arena));

		MemorySegment[] out = {MemorySegment.NULL};
		int[] status = {-1};
		String[] message = {""};
		MemorySegment cbInfo = WGPURequestDeviceCallbackInfo.allocate(arena);
		WGPURequestDeviceCallbackInfo.mode(cbInfo, WGPUCallbackMode_AllowProcessEvents());
		WGPURequestDeviceCallbackInfo.callback(cbInfo,
			WGPURequestDeviceCallback.allocate((st, dev, msg, u1, u2) -> {
				status[0] = st;
				out[0] = dev;
				message[0] = readMessage(msg);
			}, arena));

		wgpuAdapterRequestDevice(arena, adapter, desc, cbInfo);
		pumpUntil(() -> status[0] != -1, "device");
		if (out[0].equals(MemorySegment.NULL)) {
			throw new IllegalStateException("no WebGPU device (status " + status[0] + "): " + message[0]);
		}
		return out[0];
	}

	private int errorsReported;
	private String lastError = "";
	private int repeats;
	private final java.util.concurrent.atomic.AtomicInteger errorCount =
		new java.util.concurrent.atomic.AtomicInteger();
	private volatile String firstError;

	/**
	 * Every validation error Dawn has reported.
	 *
	 * <p>Exposed so tests can ASSERT on it. Printing to stderr is not enough: a test that renders
	 * successfully-looking pixels while the device logs a thousand validation failures still passes,
	 * and that is precisely how a bug that made every draw in a terrain-only frame fail survived a
	 * suite with pixel-exact checks in it. Any test that submits work should end by requiring this
	 * to be zero.
	 */
	public int errorCount() {
		return errorCount.get();
	}

	/** The first error, which is almost always the one that caused the rest. */
	public String firstError() {
		return firstError;
	}

	/**
	 * Logs a validation error, but refuses to let one keep logging forever.
	 *
	 * <p>A persistent per-draw fault produces several multi-line messages PER DRAW. At a few hundred
	 * draws a frame that is tens of thousands of formatted strings a second going to stderr, which
	 * buries the first and most useful message, starves the render thread, and makes a recoverable
	 * mistake look like a hang. The first occurrences are printed in full, repeats are counted, and
	 * the total is capped.
	 */
	private void report(int type, MemorySegment message) {
		String text = readMessage(message);
		if (errorCount.getAndIncrement() == 0) {
			firstError = text;
		}
		if (text.equals(lastError)) {
			// Same fault again: count it rather than reprinting it. A frame-after-frame failure
			// says nothing new after the first time.
			repeats++;
			return;
		}
		if (repeats > 0) {
			System.err.println("[RetroDragon] ...and " + repeats + " more of the previous error");
			repeats = 0;
		}
		lastError = text;
		if (++errorsReported > MAX_ERRORS_REPORTED) {
			if (errorsReported == MAX_ERRORS_REPORTED + 1) {
				System.err.println("[RetroDragon] too many distinct WebGPU errors; silencing further"
					+ " reports. The first ones above are the ones worth reading.");
			}
			return;
		}
		System.err.println("[RetroDragon] uncaptured WebGPU error (type " + type + "): " + text);
	}

	private static final int MAX_ERRORS_REPORTED =
		Integer.getInteger("retrogpu.maxErrorsReported", 40);

	/** Dawn hands back a WGPUStringView (pointer + length), not a NUL-terminated C string. */
	private static String readMessage(MemorySegment stringView) {
		try {
			if (stringView.equals(MemorySegment.NULL)) {
				return "";
			}
			MemorySegment view = stringView.reinterpret(16);
			MemorySegment data = view.get(ValueLayout.ADDRESS, 0);
			long length = view.get(ValueLayout.JAVA_LONG, 8);
			if (data.equals(MemorySegment.NULL) || length <= 0) {
				return "";
			}
			byte[] bytes = data.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		} catch (RuntimeException e) {
			return "<unreadable message>";
		}
	}

	private void pumpUntil(java.util.function.BooleanSupplier done, String what) {
		for (int i = 0; i < 500 && !done.getAsBoolean(); i++) {
			wgpuInstanceProcessEvents(instance);
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted awaiting " + what, e);
			}
		}
		if (!done.getAsBoolean()) {
			throw new IllegalStateException("timed out awaiting " + what);
		}
	}

	/** Drives Dawn's callbacks. Call once per frame. */
	public void processEvents() {
		wgpuInstanceProcessEvents(instance);
	}

	/** Frames submitted whose work the GPU has not yet reported finished. */
	private final java.util.concurrent.atomic.AtomicInteger inFlight =
		new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Registers a completion callback for everything submitted so far, and counts it as in flight.
	 *
	 * <p>Call once per frame, after submitting. Pairs with {@link #awaitFramesInFlight}.
	 */
	public void markFrameSubmitted() {
		inFlight.incrementAndGet();
		MemorySegment info = WGPUQueueWorkDoneCallbackInfo.allocate(arena);
		WGPUQueueWorkDoneCallbackInfo.mode(info, WGPUCallbackMode_AllowProcessEvents());
		WGPUQueueWorkDoneCallbackInfo.callback(info,
			WGPUQueueWorkDoneCallback.allocate(
				(status, message, u1, u2) -> inFlight.decrementAndGet(), arena));
		wgpuQueueOnSubmittedWorkDone(arena, queue, info);
	}

	/**
	 * Blocks until at most {@code limit} frames are still executing.
	 *
	 * <p><b>Without this the renderer can outrun the compositor without bound.</b> Nothing in a
	 * submit-and-present loop waits for the GPU, so a cheap CPU frame -- world load, where most of the
	 * cost is upload rather than draw -- queues work far faster than it retires. On macOS that starves
	 * the window server's drawable pool, and the symptom is not a dropped frame or a slow game: the
	 * whole desktop stops responding and the machine needs a restart.
	 *
	 * <p>Two frames is the usual choice: enough for the CPU to prepare the next while the GPU works
	 * on the last, and not enough to build a backlog.
	 */
	public void awaitFramesInFlight(int limit) {
		if (inFlight.get() <= limit) {
			// The overwhelmingly common case. Checked first so the fence costs one atomic read on a
			// frame that is not actually behind.
			return;
		}
		// Bounded rather than unbounded: if a callback is somehow never delivered, a stuck renderer
		// is still better than a stuck machine, so give up and carry on.
		for (int spins = 0; inFlight.get() > limit && spins < 10_000; spins++) {
			wgpuInstanceProcessEvents(instance);
			if (spins < 64) {
				Thread.onSpinWait();
			} else {
				// Past a short spin, YIELD. A tight loop calling processEvents burns a core, and on
				// this renderer the game logic and the driver's own threads are competing for it --
				// which showed up as "Max FPS" running SLOWER than vsync, the opposite of what
				// removing the frame cap is supposed to do.
				Thread.yield();
			}
		}
	}

	public int framesInFlight() {
		return inFlight.get();
	}

	/**
	 * Waits until the GPU has retired everything submitted, or {@code timeoutMillis} elapses.
	 *
	 * <p>For TEARDOWN, where {@link #awaitFramesInFlight} is the wrong tool: that one is spin-bounded
	 * because it sits on the per-frame path and must never stall a frame for long. Here the opposite
	 * is wanted -- the pipeline has to be genuinely empty before a surface is released, and it is
	 * worth waiting milliseconds to be sure.
	 *
	 * <p>Releasing a surface while work is still executing leaves the compositor holding a reference
	 * to something being destroyed. On macOS the result is not a crash in the process, it is the
	 * whole render server hanging -- the game exits cleanly and the desktop dies with it.
	 *
	 * @return true if the pipeline drained; false on timeout, which is worth logging
	 */
	public boolean drain(long timeoutMillis) {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (inFlight.get() > 0 && System.nanoTime() < deadline) {
			wgpuInstanceProcessEvents(instance);
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return inFlight.get() == 0;
	}

	public MemorySegment instance() {
		return instance;
	}

	public MemorySegment adapter() {
		return adapter;
	}

	public MemorySegment device() {
		return device;
	}

	public MemorySegment queue() {
		return queue;
	}

	/** One of {@code WGPUBackendType_*}; see {@link #backendName()}. */
	public int backendType() {
		return backendType;
	}

	public String backendName() {
		if (backendType == WGPUBackendType_Metal()) return "Metal";
		if (backendType == WGPUBackendType_Vulkan()) return "Vulkan";
		if (backendType == WGPUBackendType_D3D12()) return "D3D12";
		if (backendType == WGPUBackendType_OpenGL()) return "OpenGL";
		if (backendType == WGPUBackendType_OpenGLES()) return "OpenGLES";
		return "unknown(" + backendType + ")";
	}

	public boolean hasFeature(int feature) {
		if (adapter.equals(MemorySegment.NULL)) {
			return false;
		}
		try (Arena tmp = Arena.ofConfined()) {
			MemorySegment supported = WGPUSupportedFeatures.allocate(tmp);
			wgpuAdapterGetFeatures(adapter, supported);
			long count = WGPUSupportedFeatures.featureCount(supported);
			MemorySegment list = WGPUSupportedFeatures.features(supported).reinterpret(count * 4L);
			for (long i = 0; i < count; i++) {
				if (list.getAtIndex(ValueLayout.JAVA_INT, i) == feature) {
					return true;
				}
			}
			return false;
		}
	}

	@Override
	public void close() {
		// Reverse creation order; Dawn refcounts, so releasing a parent before its children is a
		// use-after-free rather than a no-op.
		if (!queue.equals(MemorySegment.NULL)) wgpuQueueRelease(queue);
		if (!device.equals(MemorySegment.NULL)) wgpuDeviceRelease(device);
		if (!adapter.equals(MemorySegment.NULL)) wgpuAdapterRelease(adapter);
		if (!instance.equals(MemorySegment.NULL)) wgpuInstanceRelease(instance);
		queue = device = adapter = instance = MemorySegment.NULL;
		arena.close();
	}
}
