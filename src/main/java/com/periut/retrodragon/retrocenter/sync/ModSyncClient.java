package com.periut.retrodragon.retrocenter.sync;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.net.RetroSyncPacket;
import com.periut.retrodragon.retrocenter.profile.ServerProfiles;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientNetworkHandler;

/**
 * Client download logic for the pre-login probe -- fully tick-driven
 * (no worker threads blocking on the network; the held
 * connection is pumped by ModSyncScreen.tick()).
 */
public final class ModSyncClient {

	private static final int PIPELINE_WINDOW = 4;
	private static final long CHUNK_TIMEOUT_MS = 15_000;

	private static volatile DownloadSession session;

	private ModSyncClient() {
	}

	/**
	 * Server mods this instance does not have loaded at the exact version.
	 * Client-side extras are deliberately ignored: more mods than the server
	 * is fine; missing or version-mismatched server mods are not.
	 */
	public static List<ModManifest.Entry> missingMods(ModManifest manifest) {
		List<ModManifest.Entry> missing = new ArrayList<>();
		FabricLoader loader = FabricLoader.getInstance();
		for (ModManifest.Entry entry : manifest.entries) {
			boolean satisfied = loader.getModContainer(entry.modId)
					.map(container -> container.getMetadata().getVersion().getFriendlyString().equals(entry.version))
					.orElse(false);
			if (!satisfied) {
				missing.add(entry);
			}
		}
		return missing;
	}

	/**
	 * sha256 → jar of everything in the HUB's local mods folder, built
	 * lazily once per session. Server mods whose hash matches a local jar
	 * are copied instead of downloaded.
	 */
	private static Map<String, Path> indexLocalMods() {
		Map<String, Path> bySha = new HashMap<>();
		Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
		if (Files.isDirectory(mods)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
				for (Path jar : stream) {
					try {
						bySha.put(Sha256.ofFile(jar), jar);
					} catch (IOException e) {
						RetroCenter.log("could not hash local mod " + jar.getFileName() + ": " + e);
					}
				}
			} catch (IOException e) {
				RetroCenter.log("could not index local mods: " + e);
			}
		}
		RetroCenter.log("indexed " + bySha.size() + " local mods for hash reuse");
		return bySha;
	}

	/** Routed from ProbeFlow for FILE_CHUNK packets. */
	public static void onChunk(RetroSyncPacket packet) {
		DownloadSession s = session;
		if (s != null) {
			s.onChunk(packet);
		}
	}

	public static DownloadSession startSession(ModManifest manifest, Path profileDir, ClientNetworkHandler handler) {
		DownloadSession s = new DownloadSession(manifest, profileDir, handler);
		session = s;
		return s;
	}

	public static void endSession() {
		session = null;
	}

	/**
	 * Tick-driven downloader: requests chunks with a small pipeline window,
	 * assembles files in memory, sha256-verifies, stores once in
	 * servers/.cache and copies into the profile's mods dir.
	 */
	public static final class DownloadSession {

		private final Path profileDir;
		private final ClientNetworkHandler handler;
		private final Deque<ModManifest.Entry> pending = new ArrayDeque<>();
		private final long totalBytes;

		private Map<String, Path> localModsBySha;
		private ModManifest.Entry current;
		private byte[] buffer;
		private int chunkCount = -1;
		private int nextToRequest;
		private int receivedChunks;
		private int outstanding;
		private long lastProgressAt = System.currentTimeMillis();

		private long bytesDone;
		private volatile String error;
		private volatile boolean finished;

		DownloadSession(ModManifest manifest, Path profileDir, ClientNetworkHandler handler) {
			this.profileDir = profileDir;
			this.handler = handler;
			this.pending.addAll(manifest.entries);
			this.totalBytes = manifest.totalSize();
		}

		public long bytesDone() {
			return bytesDone;
		}

		public long bytesTotal() {
			return totalBytes;
		}

		public String currentFile() {
			ModManifest.Entry e = current;
			return e != null ? e.fileName : "";
		}

		public boolean isFinished() {
			return finished;
		}

		public String error() {
			return error;
		}

		/** Called every screen tick (after the connection has been pumped). */
		public void tick() {
			if (finished || error != null) {
				return;
			}
			try {
				if (current == null && !advance()) {
					return;
				}
				// fill the pipeline
				while (current != null && outstanding < PIPELINE_WINDOW
						&& (chunkCount < 0 ? nextToRequest == 0 : nextToRequest < chunkCount)) {
					requestChunk(nextToRequest++);
				}
				if (current != null && System.currentTimeMillis() - lastProgressAt > CHUNK_TIMEOUT_MS) {
					error = "timed out downloading " + current.fileName;
				}
			} catch (Exception e) {
				e.printStackTrace();
				error = e.toString();
			}
		}

		/**
		 * Next entry; serves cached and hash-identical local jars without
		 * any network transfer. Returns false when all done.
		 */
		private boolean advance() throws IOException {
			while (true) {
				current = pending.poll();
				if (current == null) {
					finished = true;
					ModSyncClient.endSession();
					return false;
				}
				if ("retrodragon".equals(current.modId)) {
					// The childshim (RetroDragon minus org/lwjgl) supersedes the
					// full RetroDragon jar in child profiles -- installing both
					// would duplicate the mod id and re-bundle LWJGL.
					RetroCenter.log("skipping " + current.fileName + " (childshim supersedes it)");
					bytesDone += current.size;
					continue;
				}
				Path cached = ServerProfiles.cachedFile(current.sha256);
				if (Files.isRegularFile(cached) && Sha256.ofFile(cached).equals(current.sha256)) {
					install(cached);
					bytesDone += current.size;
					continue; // cached -- next entry
				}
				// hash-identical jar already in the hub's local mods folder?
				if (localModsBySha == null) {
					localModsBySha = indexLocalMods();
				}
				Path local = localModsBySha.get(current.sha256);
				if (local != null && Files.isRegularFile(local)) {
					RetroCenter.log("reusing local " + local.getFileName() + " for " + current.fileName + " (hash match)");
					Files.copy(local, cached, StandardCopyOption.REPLACE_EXISTING);
					install(cached);
					bytesDone += current.size;
					continue; // copied from local mods -- next entry
				}
				buffer = new byte[(int) current.size];
				chunkCount = -1;
				nextToRequest = 0;
				receivedChunks = 0;
				outstanding = 0;
				lastProgressAt = System.currentTimeMillis();
				return true;
			}
		}

		private void requestChunk(int index) {
			outstanding++;
			String sha = current.sha256;
			handler.sendPacket(RetroSyncPacket.make(RetroSyncPacket.OP_FILE_REQ, out -> {
				out.writeUTF(sha);
				out.writeInt(index);
			}));
		}

		void onChunk(RetroSyncPacket packet) {
			try (DataInputStream in = packet.bodyIn()) {
				String sha = in.readUTF();
				int index = in.readInt();
				int count = in.readInt();
				int length = in.readInt();
				byte[] data = new byte[length];
				in.readFully(data);

				ModManifest.Entry entry = current;
				if (entry == null || !entry.sha256.equals(sha)) {
					return; // stale chunk from a previous file
				}
				long offset = (long) index * ManifestStore.CHUNK_SIZE;
				if (offset + length > buffer.length) {
					error = "chunk overflow for " + entry.fileName;
					return;
				}
				if (chunkCount < 0) {
					chunkCount = count;
				}
				System.arraycopy(data, 0, buffer, (int) offset, length);
				receivedChunks++;
				outstanding = Math.max(0, outstanding - 1);
				bytesDone += length;
				lastProgressAt = System.currentTimeMillis();

				if (receivedChunks >= chunkCount) {
					completeCurrentFile();
				}
			} catch (Exception e) {
				e.printStackTrace();
				error = e.toString();
			}
		}

		private void completeCurrentFile() throws IOException {
			String actual = Sha256.of(buffer);
			if (!actual.equals(current.sha256)) {
				error = "sha256 mismatch for " + current.fileName;
				return;
			}
			Path cached = ServerProfiles.cachedFile(current.sha256);
			Files.write(cached, buffer);
			install(cached);
			buffer = null;
			current = null;
			advance();
		}

		private void install(Path cached) throws IOException {
			Files.copy(cached, ServerProfiles.modsDir(profileDir).resolve(current.fileName),
					StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
