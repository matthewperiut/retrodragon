package com.periut.retrodragon.retrocenter.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small sha256 helpers. */
public final class Sha256 {

	private Sha256() {
	}

	public static String of(byte[] data) {
		return toHex(digest().digest(data));
	}

	public static String ofFile(Path file) throws IOException {
		MessageDigest md = digest();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				md.update(buffer, 0, read);
			}
		}
		return toHex(md.digest());
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
