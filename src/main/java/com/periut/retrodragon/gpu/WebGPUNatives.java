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
 *
 * <p><b>Windows is the exception, and gets Dawn's own {@code webgpu_dawn.dll}</b> -- jWebGPU's
 * Windows native exports no {@code wgpu*} symbol at all, so the FFM bindings cannot reach it. See
 * {@link #resourcePath()} for the full reason and {@link DawnFeatures} for the one consequence.
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

	/**
	 * The Windows library's own version, which is a build-dawn release tag rather than a jWebGPU
	 * artifact version -- the two come from different places and move independently.
	 *
	 * <p>It keys the cache directory on Windows for exactly the reason {@link #DAWN_VERSION} does
	 * elsewhere. Without it, bumping {@code dawn_windows_tag} while {@code dawn_version} stood still
	 * would extract a different library to the same path under the same name, and the only thing
	 * standing between that and loading a stale DLL forever would be the two builds happening to
	 * differ in size. Keep it in sync with {@code dawn_windows_tag} in gradle.properties.
	 */
	private static final String DAWN_WINDOWS_TAG = "2026-07-26";
	private static boolean loaded;

	private WebGPUNatives() {
	}

	/**
	 * An explicit Dawn library to load instead of the bundled one, as an absolute path.
	 *
	 * <p>{@code -Dretrogpu.dawnLibrary=/path/to/webgpu_dawn.dll}. The escape hatch for running
	 * against a Dawn that is not the one shipped: a newer build to check whether a driver bug is
	 * already fixed upstream, a local debug build, or -- on Windows -- a build that actually exports
	 * the C API. Nothing is extracted in this mode; the path is handed to {@link System#load} as-is,
	 * so the caller owns making sure it is the right architecture.
	 */
	private static final String LIBRARY_OVERRIDE = System.getProperty("retrogpu.dawnLibrary", "");

	/** Idempotent; safe to call from anywhere before the first WebGPU call. */
	public static synchronized void load() {
		if (loaded) {
			return;
		}
		if (!LIBRARY_OVERRIDE.isEmpty()) {
			Path explicit = Path.of(LIBRARY_OVERRIDE);
			if (!Files.isRegularFile(explicit)) {
				throw new UnsatisfiedLinkError("retrogpu.dawnLibrary=" + LIBRARY_OVERRIDE
					+ " is not a file");
			}
			System.load(explicit.toAbsolutePath().toString());
			loaded = true;
			return;
		}
		String resource = resourcePath();
		try (InputStream in = WebGPUNatives.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new UnsatisfiedLinkError("No Dawn native on the classpath for this platform: "
					+ resource + " -- add the matching webgpu-desktop-jni-dawn_* dependency.");
			}
			Path dir = Path.of(System.getProperty("java.io.tmpdir"),
				"retrodragon-dawn-" + (isWindows() ? DAWN_WINDOWS_TAG : DAWN_VERSION)
					+ "-" + System.getProperty("user.name", "shared"));
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

	/**
	 * Whether this is Windows, which is the platform served by {@code webgpu_dawn.dll} rather than by
	 * jWebGPU's native -- so it is also the platform whose Dawn extension numbering differs. Public
	 * for {@link DawnFeatures}, which is the only thing that needs to care.
	 */
	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/**
	 * Matches the layout inside jWebGPU's native jars -- except on Windows, which is served by Dawn's
	 * own {@code webgpu_dawn.dll} instead. See {@link DawnFeatures} for why that matters.
	 *
	 * <p><b>Windows cannot use jWebGPU's native, and this is not a configuration mistake.</b> The
	 * bindings in {@code com.periut.webgpu} resolve through {@code SymbolLookup.loaderLookup()},
	 * which needs the {@code wgpu*} C API to be an EXPORTED symbol. jWebGPU's library is a JNI
	 * bridge with Dawn linked in statically, and on ELF and Mach-O every one of those 263 symbols
	 * keeps default visibility, so they are exported incidentally and the FFM path works. MSVC
	 * exports nothing without {@code __declspec(dllexport)} or a {@code .def}, and jParser's build
	 * declares only its own JNI entry points -- {@code jWebGPU64.dll} exports 1423 names, none of
	 * them {@code wgpu*}. Dawn is in there and completely unreachable, which surfaces as
	 * {@code NoSuchElementException: Symbol not found: wgpuCreateInstance} on the first call.
	 *
	 * <p>So Windows gets {@code webgpu_dawn.dll} from Dawn's own monolithic-shared-library build,
	 * which does export the C API. {@code downloadWindowsDawn} in build.gradle fetches it.
	 */
	private static String resourcePath() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm64");

		if (os.contains("mac") || os.contains("darwin")) {
			return arm ? "native/dawn/libjWebGPUarm64.dylib" : "native/dawn/libjWebGPU64.dylib";
		}
		if (os.contains("win")) {
			return "native/dawn/webgpu_dawn.dll";
		}
		if (os.contains("linux")) {
			return "native/dawn/libjWebGPU64.so";
		}
		throw new UnsatisfiedLinkError("Unsupported platform for Dawn: " + os + "/" + arch);
	}
}
