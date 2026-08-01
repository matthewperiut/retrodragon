package com.periut.retrodragon.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles every {@code .wgsl} file in a directory against a real device and reports which ones
 * failed.
 *
 * <p>The only way to find out whether a shader is valid, short of launching the game. WGSL is
 * compiled by the driver at pipeline-build time, so a typo in a program that is reached only at
 * dusk in the nether is a crash at dusk in the nether -- and a shader mod registers a dozen
 * programs most of which a given world never touches. Compiling all of them offline turns that into
 * a build step.
 *
 * <p>Headless: no window, no surface, no swapchain. Dawn will create a device without any of them,
 * which is what makes this runnable in CI and on a machine with the game closed.
 *
 * <pre>./gradlew wgslCheck                  # this project's own programs
 *./gradlew wgslCheck -Pdir=build/wgsl # a companion mod's dump</pre>
 */
public final class WgslCheck {
	private WgslCheck() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: WgslCheck <directory>");
			System.exit(2);
		}
		Path dir = Path.of(args[0]);
		if (!Files.isDirectory(dir)) {
			System.err.println("not a directory: " + dir.toAbsolutePath());
			System.exit(2);
		}

		List<Path> sources = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".wgsl")).sorted()
				.forEach(sources::add);
		}
		if (sources.isEmpty()) {
			System.err.println("no .wgsl files in " + dir.toAbsolutePath());
			System.exit(2);
		}

		WebGPUNatives.load();
		int failed = 0;
		try (WebGPUContext ctx = WebGPUContext.create();
				Arena arena = Arena.ofShared()) {
			for (Path source : sources) {
				String name = source.getFileName().toString();
				String wgsl = Files.readString(source);
				int before = ctx.errorCount();
				MemorySegment module = Shaders.compile(ctx, arena, name, wgsl);
				// Dawn reports a WGSL error through the device's uncaptured-error callback and may
				// still hand back a module, so a null check alone passes a broken shader. Draining
				// first makes sure a callback that has not fired yet is not counted against the NEXT
				// file, which would blame the wrong shader.
				ctx.drain(2000);
				int after = ctx.errorCount();
				boolean ok = !module.equals(MemorySegment.NULL) && after == before;
				System.out.println((ok ? "  ok   " : "  FAIL ") + name);
				if (!ok) {
					failed++;
				}
			}
		}

		System.out.println(sources.size() - failed + "/" + sources.size() + " programs compiled");
		if (failed > 0) {
			System.exit(1);
		}
	}
}
