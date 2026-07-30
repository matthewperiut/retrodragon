package com.periut.retrodragon.render;

import com.periut.retrodragon.gpu.WebGPUContext;
import com.periut.retrodragon.window.sdl.Sdl3Events;
import com.periut.retrodragon.window.sdl.Sdl3Window;

import java.lang.foreign.MemorySegment;

/**
 * Frame-loop throughput against a real surface. {@code ./gradlew webgpuLoopBench}.
 *
 * <h2>Why this exists separately from {@link WebGpuBench}</h2>
 *
 * WebGpuBench measures how fast the GPU chews through terrain-shaped geometry, offscreen, with no
 * window and no swapchain. It reports sub-millisecond numbers -- and it reported them on a machine
 * whose real frame rate was far below what the OpenGL path managed. Both can be true at once,
 * because the thing it measures is not the thing that was slow: an EMPTY frame still has to acquire,
 * encode, submit, wait on the previous frame, drive Dawn's callbacks and tear down an arena, and
 * none of that is geometry.
 *
 * <p>So this measures the cost of a frame that draws nothing at all. Whatever it reports is the
 * ceiling the renderer can never exceed however cheap the drawing gets, which makes it the number to
 * optimise first. The split between {@code beginFrame} and {@code endFrame} says which half to look
 * at, and {@code -Dretroperf.pacing=max} is what makes the uncapped path reachable at all -- without
 * it the loop runs at vsync and every result is really the display's refresh rate.
 *
 * <p>Reports a median rather than a mean, and discards warmup frames: the first frames pay JIT and
 * swapchain allocation, and one 40 ms hiccup skews a mean over 2000 frames by more than any change
 * being measured here would move it.
 */
public final class WebGpuLoopBench {
	public static void main(String[] args) throws Exception {
		int warmup = Integer.getInteger("retroperf.warmup", 300);
		int frames = Integer.getInteger("retroperf.frames", 3000);
		boolean hidden = !Boolean.getBoolean("retroperf.visible");

		if (!Sdl3Window.setNoGl(true)) {
			fail("window already exists");
		}
		if (!Sdl3Window.create(854, 480, "RetroDragon -- loop bench", hidden)) {
			fail("SDL3 window creation failed");
		}

		long[] begin = new long[frames];
		long[] end = new long[frames];
		long[] total = new long[frames];
		int presented = 0;
		int skipped = 0;

		try (WebGPUContext ctx = WebGPUContext.create()) {
			try (WebGpuRenderer renderer =
					WebGpuRenderer.create(ctx, Sdl3Window.width(), Sdl3Window.height())) {
				System.out.println("device     = " + ctx.backendName() + " -- " + ctx.adapterName());
				System.out.println("pacing     = " + FramePacing.describe());
				System.out.println("window     = " + renderer.width() + "x" + renderer.height()
					+ (hidden ? " (hidden)" : " (visible)"));
				System.out.println("frames     = " + warmup + " warmup + " + frames + " measured");
				System.out.println();

				long wallStart = 0L;
				for (int i = -warmup; i < frames; i++) {
					if (i == 0) {
						wallStart = System.nanoTime();
					}
					Sdl3Events.pump();

					long t0 = System.nanoTime();
					boolean ok = renderer.beginFrame();
					long t1 = System.nanoTime();
					if (!ok) {
						skipped++;
						continue;
					}
					// One cleared pass and nothing else. The geometry cost is WebGpuBench's job; what
					// is being timed here is the machinery that runs whether or not anything is drawn.
					MemorySegment pass = renderer.frame().beginPass(renderer.frameArena(),
						renderer.colorView(), true, 0.1F, 0.2F, 0.3F, 1.0F, renderer.depthView(), true);
					if (pass.equals(MemorySegment.NULL)) {
						skipped++;
						continue;
					}
					long t2 = System.nanoTime();
					renderer.endFrame();
					long t3 = System.nanoTime();

					if (i >= 0) {
						begin[i] = t1 - t0;
						end[i] = t3 - t2;
						total[i] = t3 - t0;
					}
					presented++;
				}
				long wallNanos = System.nanoTime() - wallStart;

				report(begin, end, total, wallNanos, frames, skipped);
			}
		}
		Sdl3Window.destroy();
		if (presented == 0) {
			fail("no frame completed");
		}
	}

	private static void report(long[] begin, long[] end, long[] total, long wallNanos,
			int frames, int skipped) {
		System.out.printf("wall           %8.3f ms for %d frames%n", wallNanos / 1e6, frames);
		System.out.printf("throughput     %8.0f fps%n", frames / (wallNanos / 1e9));
		System.out.println();
		System.out.printf("%-14s %10s %10s %10s %10s%n", "stage", "median", "mean", "p99", "max");
		row("beginFrame", begin);
		row("pass+encode", diff(total, begin, end));
		row("endFrame", end);
		row("frame total", total);
		if (skipped > 0) {
			System.out.println();
			System.out.println("skipped        " + skipped + " frames (no backbuffer)");
		}
	}

	/** total - begin - end, i.e. what the caller's own recording cost. */
	private static long[] diff(long[] total, long[] begin, long[] end) {
		long[] out = new long[total.length];
		for (int i = 0; i < total.length; i++) {
			out[i] = Math.max(0L, total[i] - begin[i] - end[i]);
		}
		return out;
	}

	private static void row(String name, long[] samples) {
		long[] sorted = samples.clone();
		java.util.Arrays.sort(sorted);
		double mean = 0.0;
		for (long s : samples) {
			mean += s;
		}
		mean /= samples.length;
		System.out.printf("%-14s %9.3fu %9.3fu %9.3fu %9.3fu%n", name,
			sorted[sorted.length / 2] / 1e3,
			mean / 1e3,
			sorted[(int) (sorted.length * 0.99)] / 1e3,
			sorted[sorted.length - 1] / 1e3);
	}

	private static void fail(String message) {
		System.err.println("LOOP BENCH FAILED: " + message);
		System.exit(1);
	}
}
