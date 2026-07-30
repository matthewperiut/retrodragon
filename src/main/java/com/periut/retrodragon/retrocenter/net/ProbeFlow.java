package com.periut.retrodragon.retrocenter.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.periut.retrodragon.window.mixin.lwjgl3.MinecraftAccessor;
import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.gui.ModSyncScreen;
import com.periut.retrodragon.retrocenter.sync.ModManifest;
import com.periut.retrodragon.retrocenter.sync.ModSyncClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.network.NetworkHandler;
import net.minecraft.client.network.ClientNetworkHandler;
import net.minecraft.network.packet.handshake.HandshakePacket;

/**
 * Client-side pre-login probe, leveraging the very first connection attempt
 * (client classes only -- never loaded server-side).
 *
 * When the server's handshake reply arrives -- the earliest bidirectional
 * moment, BEFORE LoginHello -- the login is held and a sync QUERY goes out
 * on the same connection:
 *
 * - server answers with its manifest:
 *     - mod set satisfied (client extras are fine) → release the held
 *       handshake, login proceeds on this same socket (retroauth's join
 *       logic runs as normal on the re-invocation),
 *     - mods missing → ModSyncScreen takes over the held connection
 *       (consent → download → child handoff),
 * - server kicks us ("Bad packet id" -- no retrocenter) or stays silent past
 *   the deadline → remember "no retrocenter" for this host:port and
 *   reconnect cleanly WITHOUT probing: vanilla servers behave vanilla.
 */
public final class ProbeFlow {

	private static final long PROBE_TIMEOUT_MS = 4_000;

	private enum Phase {IDLE, PROBING, SYNCING, PASSED}

	/** host:port → does the server speak retrocenter? Session-scoped. */
	private static final Map<String, Boolean> verdicts = new ConcurrentHashMap<>();

	private static Phase phase = Phase.IDLE;
	private static ClientNetworkHandler handler;
	private static HandshakePacket heldHandshake;
	private static String host = "";
	private static int port;
	private static long deadline;

	private ProbeFlow() {
	}

	/** ClientNetworkHandler ctor TAIL: a new outgoing connection exists. */
	public static void onConnectionStart(ClientNetworkHandler newHandler, String newHost, int newPort) {
		handler = newHandler;
		host = newHost;
		port = newPort;
		RetroCenter.lastConnectHost = newHost;
		RetroCenter.lastConnectPort = newPort;
		if (phase != Phase.SYNCING) {
			phase = Phase.IDLE;
		}
	}

	/**
	 * onHandshake HEAD. Returns true to hold the login while probing.
	 * Children probe too: their mod set matches, so they pass straight
	 * through on the same socket.
	 */
	public static boolean holdLogin(ClientNetworkHandler h, HandshakePacket packet) {
		if (phase == Phase.PASSED) {
			phase = Phase.IDLE; // re-invocation after a successful probe
			return false;
		}
		Boolean known = verdicts.get(key());
		if (known != null && !known) {
			return false; // known vanilla server -- connect normally
		}
		handler = h;
		heldHandshake = packet;
		phase = Phase.PROBING;
		deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
		RetroCenter.log("probing " + key() + " for retrocenter (login held)");
		h.sendPacket(RetroSyncPacket.make(RetroSyncPacket.OP_QUERY,
				out -> out.writeInt(RetroCenter.SYNC_PROTOCOL_VERSION)));
		return true;
	}

	/** ClientNetworkHandler.tick HEAD: probe deadline. */
	public static void clientTick(ClientNetworkHandler h) {
		if (phase == Phase.PROBING && h == handler && System.currentTimeMillis() > deadline) {
			RetroCenter.log("probe timed out -- treating " + key() + " as non-retrocenter");
			noRetrocenter();
		}
	}

	/**
	 * onDisconnected HEAD. While probing, a disconnect (typically the "Bad
	 * packet id" kick from a server without the mod) is the no-support
	 * signal: swallow it and reconnect without probing. Returns true to
	 * cancel vanilla disconnect handling.
	 */
	public static boolean onDisconnected(ClientNetworkHandler h, String reason) {
		if (phase != Phase.PROBING || h != handler) {
			return false;
		}
		RetroCenter.log("probe rejected (" + reason + ") -- " + key() + " has no retrocenter, reconnecting vanilla");
		noRetrocenter();
		return true;
	}

	/** Incoming sync packet on the client side (any phase). */
	public static void onSyncPacket(NetworkHandler h, RetroSyncPacket packet) {
		if (packet.op == RetroSyncPacket.OP_MANIFEST && phase == Phase.PROBING) {
			verdicts.put(key(), true);
			ModManifest manifest;
			try (DataInputStream in = packet.bodyIn()) {
				manifest = ModManifest.read(in);
			} catch (IOException e) {
				RetroCenter.log("bad manifest: " + e);
				noRetrocenter();
				return;
			}
			List<ModManifest.Entry> missing = ModSyncClient.missingMods(manifest);
			if (missing.isEmpty()) {
				RetroCenter.log("mod set satisfied (" + manifest.entries.size()
						+ " server mods) -- proceeding with login on the same connection");
				proceedWithLogin();
			} else {
				RetroCenter.log(missing.size() + "/" + manifest.entries.size()
						+ " server mods missing -- opening sync screen");
				phase = Phase.SYNCING;
				Minecraft mc = MinecraftAccessor.getInstance();
				mc.setScreen(new ModSyncScreen(mc, manifest, host, port, handler));
			}
		} else if (packet.op == RetroSyncPacket.OP_FILE_CHUNK && phase == Phase.SYNCING) {
			ModSyncClient.onChunk(packet);
		}
	}

	/** The sync screen finished (or aborted) -- connection is done with. */
	public static void syncSessionEnded() {
		phase = Phase.IDLE;
		heldHandshake = null;
	}

	private static void proceedWithLogin() {
		phase = Phase.PASSED;
		HandshakePacket held = heldHandshake;
		heldHandshake = null;
		handler.onHandshake(held); // re-invoke: vanilla/retroauth login flow runs now
	}

	private static void noRetrocenter() {
		verdicts.put(key(), false);
		phase = Phase.IDLE;
		heldHandshake = null;
		ClientNetworkHandler dead = handler;
		try {
			dead.disconnect();
		} catch (Throwable ignored) {
		}
		// reconnect cleanly; the cached verdict skips the probe this time
		Minecraft mc = MinecraftAccessor.getInstance();
		mc.setScreen(new ConnectScreen(mc, host, port));
	}

	private static String key() {
		return host + ":" + port;
	}
}
