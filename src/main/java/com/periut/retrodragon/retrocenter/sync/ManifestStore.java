package com.periut.retrodragon.retrocenter.sync;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import com.periut.retrodragon.retrocenter.RetroCenter;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side manifest of syncable mod jars (loader API only).
 * Scans mods/, hashes each jar, extracts id/version from fabric.mod.json.
 * Jars listed in config/retrocenter/no-sync.txt (one file name per line)
 * are excluded -- for server-only plugins/admin tools.
 */
public final class ManifestStore {

	public static final int CHUNK_SIZE = 28_000;

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

	private static volatile ModManifest manifest;
	private static final Map<String, Path> filesBySha = new HashMap<>();

	private ManifestStore() {
	}

	public static ModManifest get() {
		ModManifest m = manifest;
		if (m == null) {
			synchronized (ManifestStore.class) {
				m = manifest;
				if (m == null) {
					manifest = m = build();
				}
			}
		}
		return m;
	}

	public static final class Chunk {
		public final int chunkCount;
		public final byte[] data;

		Chunk(int chunkCount, byte[] data) {
			this.chunkCount = chunkCount;
			this.data = data;
		}
	}

	/** Reads one chunk of a served file; null when unknown/out of range. */
	public static Chunk readChunk(String sha256, int chunkIndex) throws IOException {
		Path file;
		synchronized (filesBySha) {
			file = filesBySha.get(sha256);
		}
		if (file == null) {
			get(); // make sure the manifest was built at least once
			synchronized (filesBySha) {
				file = filesBySha.get(sha256);
			}
			if (file == null) {
				return null;
			}
		}
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			long length = raf.length();
			int chunkCount = (int) ((length + CHUNK_SIZE - 1) / CHUNK_SIZE);
			long offset = (long) chunkIndex * CHUNK_SIZE;
			if (chunkIndex < 0 || offset >= length) {
				return null;
			}
			byte[] data = new byte[(int) Math.min(CHUNK_SIZE, length - offset)];
			raf.seek(offset);
			raf.readFully(data);
			return new Chunk(chunkCount, data);
		}
	}

	private static ModManifest build() {
		ModManifest m = new ModManifest();
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
		Set<String> excluded = readExclusions();
		if (!Files.isDirectory(modsDir)) {
			return m;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
			for (Path jar : stream) {
				String fileName = jar.getFileName().toString();
				if (excluded.contains(fileName)) {
					continue;
				}
				try {
					String sha = Sha256.ofFile(jar);
					long size = Files.size(jar);
					String[] idVersion = readModIdAndVersion(jar);
					m.entries.add(new ModManifest.Entry(idVersion[0], idVersion[1], fileName, sha, size));
					synchronized (filesBySha) {
						filesBySha.put(sha, jar);
					}
				} catch (IOException e) {
					RetroCenter.log("skipping unreadable mod jar " + fileName + ": " + e);
				}
			}
		} catch (IOException e) {
			RetroCenter.log("could not scan mods dir: " + e);
		}
		RetroCenter.log("serving " + m.entries.size() + " mods (" + (m.totalSize() / 1024) + " KiB)");
		return m;
	}

	private static Set<String> readExclusions() {
		Set<String> excluded = new HashSet<>();
		Path file = FabricLoader.getInstance().getGameDir()
				.resolve("config").resolve("retrocenter").resolve("no-sync.txt");
		if (Files.isRegularFile(file)) {
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					line = line.trim();
					if (!line.isEmpty() && !line.startsWith("#")) {
						excluded.add(line);
					}
				}
			} catch (IOException e) {
				RetroCenter.log("could not read no-sync.txt: " + e);
			}
		}
		return excluded;
	}

	/** Crude fabric.mod.json id/version extraction (no JSON lib on b1.7.3). */
	private static String[] readModIdAndVersion(Path jar) {
		String id = jar.getFileName().toString();
		String version = "unknown";
		try (JarFile jarFile = new JarFile(jar.toFile())) {
			ZipEntry entry = jarFile.getEntry("fabric.mod.json");
			if (entry != null) {
				String json = new String(jarFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
				Matcher idMatcher = ID_PATTERN.matcher(json);
				if (idMatcher.find()) {
					id = idMatcher.group(1);
				}
				Matcher versionMatcher = VERSION_PATTERN.matcher(json);
				if (versionMatcher.find()) {
					version = versionMatcher.group(1);
				}
			}
		} catch (IOException ignored) {
		}
		return new String[]{id, version};
	}
}
