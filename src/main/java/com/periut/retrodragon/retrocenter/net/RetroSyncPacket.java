package com.periut.retrodragon.retrocenter.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import net.minecraft.network.NetworkHandler;
import net.minecraft.network.packet.Packet;

/**
 * The pre-login carrier packet for retrocenter's mod-sync protocol.
 *
 * b1.7.3's packet table is global, not phase-gated, so a custom carrier
 * packet works during login too. This packet is registered at {@link #ID}
 * (configurable via -Dretrocenter.packetId) on both sides by
 * RetroCenterMain and routes itself in {@link #apply}: server side → the login
 * handler's probe service, client side → the probe flow.
 *
 * Against a server WITHOUT retrocenter, sending this packet gets the client
 * kicked with "Bad packet id" -- which the client treats as the definitive
 * "no retrocenter here" signal and falls back to a clean vanilla connect.
 *
 * Opcodes:
 *   0 QUERY      c→s  { int protocolVersion }
 *   1 MANIFEST   s→c  { ModManifest }
 *   2 FILE_REQ   c→s  { utf sha256, int chunkIndex }
 *   3 FILE_CHUNK s→c  { utf sha256, int chunkIndex, int chunkCount, int len, bytes }
 */
public class RetroSyncPacket extends Packet {

	public static final int ID = Integer.getInteger("retrocenter.packetId", 230);

	public static final byte OP_QUERY = 0;
	public static final byte OP_MANIFEST = 1;
	public static final byte OP_FILE_REQ = 2;
	public static final byte OP_FILE_CHUNK = 3;

	/** Sanity cap for the carried body (a chunk plus headers fits easily). */
	private static final int MAX_BODY = 1 << 20;

	public byte op;
	public byte[] body = new byte[0];

	public RetroSyncPacket() {
	}

	public RetroSyncPacket(byte op, byte[] body) {
		this.op = op;
		this.body = body;
	}

	@Override
	public void read(DataInputStream in) throws IOException {
		op = in.readByte();
		int length = in.readInt();
		if (length < 0 || length > MAX_BODY) {
			throw new IOException("retrocenter sync body too large: " + length);
		}
		body = new byte[length];
		in.readFully(body);
	}

	@Override
	public void write(DataOutputStream out) throws IOException {
		out.writeByte(op);
		out.writeInt(body.length);
		out.write(body);
	}

	@Override
	public int size() {
		return 1 + 4 + body.length;
	}

	@Override
	public void apply(NetworkHandler handler) {
		if (handler.isServerSide()) {
			// ServerProbe is only ever classloaded on the server side, so
			// client jars missing server classes stay safe.
			ServerProbe.handle(handler, this);
		} else {
			ProbeFlow.onSyncPacket(handler, this);
		}
	}

	public DataInputStream bodyIn() {
		return new DataInputStream(new ByteArrayInputStream(body));
	}

	public static RetroSyncPacket make(byte op, BodyWriter writer) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			writer.write(out);
			return new RetroSyncPacket(op, bytes.toByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@FunctionalInterface
	public interface BodyWriter {
		void write(DataOutputStream out) throws IOException;
	}
}
