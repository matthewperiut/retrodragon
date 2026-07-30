package com.periut.retrodragon.retrocenter.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.sync.ManifestStore;

import net.minecraft.network.NetworkHandler;
import net.minecraft.server.network.ServerLoginNetworkHandler;

/**
 * Server side of the pre-login probe (only ever classloaded on the server
 * side -- see RetroSyncPacket.apply).
 *
 * Stateless serving: every FILE_REQ names (sha256, chunkIndex) and gets one
 * FILE_CHUNK back. The login handler is marked active so the login timeout
 * is frozen while a client is syncing (ServerLoginHandlerSyncMixin).
 */
public final class ServerProbe {

	private static final Map<NetworkHandler, Boolean> ACTIVE =
			Collections.synchronizedMap(new WeakHashMap<>());

	private ServerProbe() {
	}

	public static boolean isActive(NetworkHandler handler) {
		return ACTIVE.containsKey(handler);
	}

	public static void handle(NetworkHandler handler, RetroSyncPacket packet) {
		if (!(handler instanceof ServerLoginNetworkHandler)) {
			return; // the probe only runs during login
		}
		ServerLoginNetworkHandler login = (ServerLoginNetworkHandler) handler;
		try {
			switch (packet.op) {
				case RetroSyncPacket.OP_QUERY: {
					try (DataInputStream in = packet.bodyIn()) {
						int protocol = in.readInt();
						if (protocol != RetroCenter.SYNC_PROTOCOL_VERSION) {
							RetroCenter.log("probe with protocol " + protocol + " (server has "
									+ RetroCenter.SYNC_PROTOCOL_VERSION + ") -- ignoring");
							return;
						}
					}
					ACTIVE.put(handler, Boolean.TRUE);
					login.connection.sendPacket(RetroSyncPacket.make(RetroSyncPacket.OP_MANIFEST,
							out -> ManifestStore.get().write(out)));
					break;
				}
				case RetroSyncPacket.OP_FILE_REQ: {
					String sha;
					int chunkIndex;
					try (DataInputStream in = packet.bodyIn()) {
						sha = in.readUTF();
						chunkIndex = in.readInt();
					}
					ManifestStore.Chunk chunk = ManifestStore.readChunk(sha, chunkIndex);
					if (chunk == null) {
						RetroCenter.log("file_req for unknown sha/chunk " + sha + "#" + chunkIndex);
						return;
					}
					login.connection.sendPacket(RetroSyncPacket.make(RetroSyncPacket.OP_FILE_CHUNK, out -> {
						out.writeUTF(sha);
						out.writeInt(chunkIndex);
						out.writeInt(chunk.chunkCount);
						out.writeInt(chunk.data.length);
						out.write(chunk.data);
					}));
					break;
				}
				default:
					RetroCenter.log("unexpected probe opcode " + packet.op);
			}
		} catch (IOException e) {
			RetroCenter.log("probe handling failed: " + e);
		}
	}
}
