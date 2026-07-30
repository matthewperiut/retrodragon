package com.periut.retrodragon.retrocenter.profile;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.periut.retrodragon.retrocenter.RetroCenter;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Per-server isolated game directories:
 *
 * <pre>
 * &lt;gameDir&gt;/servers/
 *   .cache/                     sha256-keyed downloaded mod jars (shared)
 *   &lt;host&gt;_&lt;port&gt;/
 *     mods/                     synced server mods + seeded infra mods
 *     stats/ options.txt ...    created by the child game itself
 * </pre>
 *
 * Because the child boots with --gameDir pointing here, per-server stats,
 * options and screenshots come for free.
 */
public final class ServerProfiles {

	/** Mod jars copied from the hub's mods dir into every child profile. */
	private static final String[] INFRA_MOD_PREFIXES = {"retroauth"};

	private ServerProfiles() {
	}

	public static String profileKey(String host, int port) {
		String safeHost = host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return safeHost + "_" + port;
	}

	public static Path serversRoot() {
		return FabricLoader.getInstance().getGameDir().resolve("servers");
	}

	public static Path cacheDir() throws IOException {
		Path dir = serversRoot().resolve(".cache");
		Files.createDirectories(dir);
		return dir;
	}

	public static Path cachedFile(String sha256) throws IOException {
		return cacheDir().resolve(sha256 + ".jar");
	}

	/** Creates (if needed) and returns the profile dir for a server. */
	public static Path profileDir(String host, int port) throws IOException {
		Path dir = serversRoot().resolve(profileKey(host, port));
		Files.createDirectories(dir.resolve("mods"));
		Files.createDirectories(dir.resolve("stats"));
		return dir;
	}

	public static Path modsDir(Path profileDir) {
		return profileDir.resolve("mods");
	}

	/**
	 * Seeds the child's mods folder with the infrastructure the child needs
	 * beyond the server-synced mods:
	 * 1. the RetroDragon "childshim" jar (RetroDragon minus org/lwjgl, see ChildClassLoader),
	 * 2. local infra mods from the hub's own mods dir (retroauth).
	 *
	 * The local infra mods (2) are best-effort: a server that does not need
	 * them still boots. The shim (1) is NOT -- see below.
	 *
	 * @throws IllegalStateException if the childshim jar cannot be located
	 */
	public static void seedInfraMods(Path profileDir) {
		Path mods = modsDir(profileDir);

		Path shim = locateChildShim();
		if (shim == null) {
			// Hard failure, not a warning. There is no fallback for this: the child's classpath
			// deliberately strips the project's build/ tree (InProcessChildLauncher) and
			// ChildClassLoader.getResources hides parent copies of isolated resources, so the
			// child's loader cannot discover RetroDragon as a classpath mod even in a dev run. A
			// child booted without the shim gets none of the RetroCenter mixins -- including
			// MinecraftStopExitMixin, so leaving that child runs the un-redirected System.exit(0)
			// and kills the whole JVM, hub included. "The game just closed" is not a debuggable
			// symptom; ModSyncScreen.launchChild turns this into a visible sync failure instead.
			throw new IllegalStateException("childshim jar not found -- build it with ./gradlew childShimJar"
					+ " (or place it at config/retrocenter/childshim.jar)");
		}
		copyIfNewer(shim, mods.resolve("retrodragon-childshim.jar"));

		// Remove any full RetroDragon jar that earlier syncs placed here -- the
		// shim supersedes it (duplicate mod id + re-bundled LWJGL otherwise).
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "retrodragon-*.jar")) {
			for (Path jar : stream) {
				if (!jar.getFileName().toString().equals("retrodragon-childshim.jar")) {
					Files.deleteIfExists(jar);
					RetroCenter.log("removed superseded " + jar.getFileName() + " from child profile");
				}
			}
		} catch (IOException ignored) {
		}

		Path hubMods = FabricLoader.getInstance().getGameDir().resolve("mods");
		if (Files.isDirectory(hubMods)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(hubMods, "*.jar")) {
				for (Path jar : stream) {
					String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
					for (String prefix : INFRA_MOD_PREFIXES) {
						if (name.startsWith(prefix)) {
							copyIfNewer(jar, mods.resolve(jar.getFileName().toString()));
							break;
						}
					}
				}
			} catch (IOException e) {
				RetroCenter.log("could not seed infra mods: " + e);
			}
		}
	}

	private static Path locateChildShim() {
		// 1. explicit override (used by loom dev runs, pointing at the
		//    named-mappings shim in build/libs)
		String prop = System.getProperty("retrocenter.childShimJar");
		if (prop != null) {
			Path p = Path.of(prop);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		// 2. embedded in the RetroDragon jar itself (intermediary-mapped, added
		//    by the remapJar task) -- self-extract, no install step needed
		Path extracted = extractEmbeddedShim();
		if (extracted != null) {
			return extracted;
		}
		// 3. manually placed file
		Path configured = FabricLoader.getInstance().getGameDir()
				.resolve("config").resolve("retrocenter").resolve("childshim.jar");
		if (Files.isRegularFile(configured)) {
			return configured;
		}
		return null;
	}

	/**
	 * Extracts the shim bundled at /retrocenter/childshim.jar (production
	 * jars only -- dev classes dirs don't carry it) into config/retrocenter/,
	 * refreshing it when the embedded content changed (e.g. a RetroDragon update).
	 */
	private static Path extractEmbeddedShim() {
		try (java.io.InputStream in = ServerProfiles.class.getResourceAsStream("/retrocenter/childshim.jar")) {
			if (in == null) {
				return null;
			}
			byte[] embedded = in.readAllBytes();
			Path target = FabricLoader.getInstance().getGameDir()
					.resolve("config").resolve("retrocenter").resolve("childshim.jar");
			if (!Files.isRegularFile(target)
					|| Files.size(target) != embedded.length
					|| !java.util.Arrays.equals(embedded, Files.readAllBytes(target))) {
				Files.createDirectories(target.getParent());
				Files.write(target, embedded);
				RetroCenter.log("extracted embedded childshim to " + target);
			}
			return target;
		} catch (IOException e) {
			RetroCenter.log("could not extract embedded childshim: " + e);
			return null;
		}
	}

	private static void copyIfNewer(Path source, Path target) {
		try {
			if (Files.exists(target)
					&& Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
				return;
			}
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			RetroCenter.log("could not copy " + source + " -> " + target + ": " + e);
		}
	}
}
