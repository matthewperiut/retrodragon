package com.periut.retrodragon.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Locates and loads the Dawn shared library for the running platform.
 *
 * <p>The libraries ride inside the {@code webgpu-desktop-jni-dawn_*} jars, so they have to be
 * unpacked to a real path before {@link System#load} will take them. The target is a stable cache
 * directory keyed on the Dawn version and the user name rather than a fresh temp file per launch:
 * the macOS library is ~9 MB, and leaving a new copy behind on every start is pure litter.
 *
 * <p>Only the natives from those jars are used. Their accompanying Java API is not on the classpath
 * and must not be added -- the published mac_arm64 JNI bridge exports no implementations, so it
 * fails at runtime in ways that look like driver bugs. The FFM bindings in
 * {@code com.periut.webgpu} call the exported C API directly instead.
 */
public final class WebGPUNatives {
	/**
	 * The Dawn natives version -- deliberately the {@code dawn_version} from {@code gradle.properties}
	 * (the {@code webgpu-desktop-jni-dawn_*} artifact version), NOT RetroDragon's own version. The
	 * cache directory is keyed on this so that a mod update which does not change Dawn reuses the
	 * already-extracted library, and a Dawn bump gets a fresh directory instead of overwriting one
	 * an older still-running instance may have mapped. Keep it in sync with
	 * {@code dawn_version} in gradle.properties.
	 */
	private static final String DAWN_VERSION = "0.3.4";
	private static boolean loaded;

	private WebGPUNatives() {
	}

	/** Idempotent; safe to call from anywhere before the first WebGPU call. */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		String resource = resourcePath();
		try (InputStream in = WebGPUNatives.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new UnsatisfiedLinkError("No Dawn native on the classpath for this platform: "
					+ resource + " -- add the matching webgpu-desktop-jni-dawn_* dependency.");
			}
			Path dir = Path.of(System.getProperty("java.io.tmpdir"),
				"retrodragon-dawn-" + DAWN_VERSION + "-" + System.getProperty("user.name", "shared"));
			Files.createDirectories(dir);
			String name = resource.substring(resource.lastIndexOf('/') + 1);
			Path target = dir.resolve(name);
			byte[] library = in.readAllBytes();
			// Extract-then-rename, never write into `target` in place.
			//
			// The old code did Files.copy(REPLACE_EXISTING) on every load. That truncates and
			// rewrites the exact file that any OTHER live instance -- a second launcher window, a
			// RetroCenter child, a still-shutting-down previous run -- has dlopen'd and mmap'd, and
			// touching a mapped library's pages under it is a SIGBUS, not an IOException.
			//
			// Skipping when the size already matches keeps the reason that REPLACE_EXISTING was
			// there in the first place: a partial extraction from a killed process has the wrong
			// size, so it is replaced rather than loaded. The replacement goes to a temp file in the
			// SAME directory (so the rename stays on one filesystem) and is then moved into place
			// atomically, which means concurrent starts can race harmlessly -- a reader either sees
			// the old complete file or the new complete file, never a half-written one.
			if (!Files.isRegularFile(target) || Files.size(target) != library.length) {
				Path tmp = Files.createTempFile(dir, name + ".", ".part");
				try {
					Files.write(tmp, library);
					// createTempFile is owner-only (0600) while the old Files.copy produced a
					// umask-default file, and the cache dir is shared when user.name is missing.
					// Best effort: no POSIX permissions on Windows, where this is moot anyway.
					try {
						Files.setPosixFilePermissions(tmp,
							java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
					} catch (UnsupportedOperationException | IOException ignored) {
					}
					moveIntoPlace(tmp, target, library.length);
				} finally {
					// A no-op after a successful move; cleans up if the write or move failed.
					Files.deleteIfExists(tmp);
				}
			}
			System.load(target.toAbsolutePath().toString());
			loaded = true;
		} catch (Exception e) {
			throw new UnsatisfiedLinkError("Failed to extract Dawn native " + resource + ": " + e);
		}
	}

	private static void moveIntoPlace(Path tmp, Path target, long expectedSize) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Windows refuses to replace a file another process holds open, and a few filesystems
			// have no atomic replace at all. If what is on disk is already the right library
			// (another instance won the race and got there first), just use it.
			if (Files.isRegularFile(target) && Files.size(target) == expectedSize) {
				return;
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Matches the layout inside jWebGPU's native jars. */
	private static String resourcePath() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");

		if (os.contains("mac") || os.contains("darwin")) {
			return arm ? "native/dawn/libjWebGPUarm64.dylib" : "native/dawn/libjWebGPU64.dylib";
		}
		if (os.contains("win")) {
			return "native/dawn/jWebGPU64.dll";
		}
		if (os.contains("linux")) {
			return "native/dawn/libjWebGPU64.so";
		}
		throw new UnsatisfiedLinkError("Unsupported platform for Dawn: " + os + "/" + arch);
	}
}
