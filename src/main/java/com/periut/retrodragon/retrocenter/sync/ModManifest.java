package com.periut.retrodragon.retrocenter.sync;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's offer: which mod jars a client needs to play here.
 * Plain DataInput/DataOutput, carried over the pre-login probe.
 */
public final class ModManifest {

	public static final class Entry {
		public final String modId;
		public final String version;
		public final String fileName;
		public final String sha256;
		public final long size;

		public Entry(String modId, String version, String fileName, String sha256, long size) {
			this.modId = modId;
			this.version = version;
			this.fileName = fileName;
			this.sha256 = sha256;
			this.size = size;
		}
	}

	public final List<Entry> entries = new ArrayList<>();

	public long totalSize() {
		long total = 0;
		for (Entry e : entries) {
			total += e.size;
		}
		return total;
	}

	public void write(DataOutput out) throws IOException {
		out.writeInt(entries.size());
		for (Entry e : entries) {
			out.writeUTF(e.modId);
			out.writeUTF(e.version);
			out.writeUTF(e.fileName);
			out.writeUTF(e.sha256);
			out.writeLong(e.size);
		}
	}

	public static ModManifest read(DataInput in) throws IOException {
		ModManifest manifest = new ModManifest();
		int count = in.readInt();
		if (count < 0 || count > 10_000) {
			throw new IOException("implausible manifest entry count: " + count);
		}
		for (int i = 0; i < count; i++) {
			String modId = in.readUTF();
			String version = in.readUTF();
			String fileName = in.readUTF();
			String sha256 = in.readUTF();
			long size = in.readLong();
			manifest.entries.add(new Entry(modId, version, fileName, sha256, size));
		}
		return manifest;
	}
}
