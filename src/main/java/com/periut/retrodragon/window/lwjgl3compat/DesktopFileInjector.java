package com.periut.retrodragon.window.lwjgl3compat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.periut.retrodragon.window.Starac;
import com.periut.retrodragon.window.lwjgl3compat.util.XDGPathResolver;
import org.apache.commons.io.IOUtils;

public class DesktopFileInjector {
	public static final String APP_ID = "com.mojang.minecraft";
	private static final String ICON_NAME = "minecraft.png";
	private static final String FILE_NAME = APP_ID + ".desktop";
	private static final String RESOURCE_LOCATION = "/assets/legacy-lwjgl3/" + FILE_NAME;
	private static final List<Path> injectedLocations = new ArrayList<>();

	public static void inject() {
		if (Boolean.getBoolean("legacy_lwjgl3.disable_desktopfile_injection") || System.getenv("LEGACY_LWJGL3_DISABLE_DESKTOPFILE_INJECTION") != null) {
			return;
		}
		if (!injectedLocations.isEmpty()) return; // already injected
		Runtime.getRuntime().addShutdownHook(new Thread(DesktopFileInjector::uninject));

		// Write the largest bundled icon first so the desktop file can reference it
		Path iconDest = XDGPathResolver.getUserDataLocation().resolve("icons").resolve(ICON_NAME);
		try (InputStream iconStream = DesktopFileInjector.class.getResourceAsStream("/assets/retrodragon/icons/256.png")) {
			if (iconStream != null) {
				Files.createDirectories(iconDest.getParent());
				Files.copy(iconStream, iconDest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				injectedLocations.add(iconDest);
			}
		} catch (IOException e) {
			LegacyLWJGL3.LOGGER.error("Failed to write icon: ", e);
		}

		try (InputStream stream = DesktopFileInjector.class.getResourceAsStream(RESOURCE_LOCATION)) {
			Path location = getDesktopFileLocation();

			// Use absolute path for Icon= so KDE picks it up immediately
			String iconPath = iconDest.toAbsolutePath().toString();
			injectFile(location, String.format(IOUtils.toString(Objects.requireNonNull(stream)),
					Starac.WINDOW_TITLE, iconPath).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LegacyLWJGL3.LOGGER.error("Failed to inject desktop file: ", e);
		}
		updateDesktopDatabase();
	}

	public static int setIcon(ByteBuffer[] icons) {
		// Find the largest icon for the desktop file absolute path reference
		ByteBuffer largest = icons[0];
		for (ByteBuffer buf : icons) {
			if (buf.remaining() > largest.remaining()) largest = buf;
		}

		for (ByteBuffer buf : icons) {
			try {
				int oldPos = buf.position();
				int[] pixels = new int[buf.remaining() / 4];
				for (int i = 0; i < pixels.length; i++) {
					int base = oldPos + i * 4;
					int a = buf.get(base) & 0xFF;
					int r = buf.get(base + 1) & 0xFF;
					int g = buf.get(base + 2) & 0xFF;
					int b = buf.get(base + 3) & 0xFF;
					pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
				}
				int size = (int) Math.sqrt(pixels.length);
				BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
				image.setRGB(0, 0, size, size, pixels, 0, size);
				Path target = getIconFileLocation(image.getWidth(), image.getHeight());
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				ImageIO.write(image, "png", outputStream);

				injectFile(target, outputStream.toByteArray());

				// Also write the largest icon to a fixed path for direct desktop file reference
				if (buf == largest) {
					Path directIcon = XDGPathResolver.getUserDataLocation().resolve("icons").resolve(ICON_NAME);
					injectFile(directIcon, outputStream.toByteArray());
				}
			} catch (IOException e) {
				return 1;
			}
		}
		updateIconSystem();
		return 0;
	}

	public static void updateTitle(String title) {
		if (injectedLocations.isEmpty()) return;
		Path desktopFile = getDesktopFileLocation();
		try {
			String content = new String(Files.readAllBytes(desktopFile), StandardCharsets.UTF_8);
			content = content.replaceFirst("(?m)^Name=.*$", "Name=" + title);
			Files.write(desktopFile, content.getBytes(StandardCharsets.UTF_8));
			updateDesktopDatabase();
		} catch (IOException e) {
			LegacyLWJGL3.LOGGER.error("Failed to update desktop file title: ", e);
		}
	}

	private static void injectFile(Path target, byte[] data) {
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
			injectedLocations.add(target);
		} catch (IOException e) {
			LegacyLWJGL3.LOGGER.error("Failed to inject file: ", e);
		}
	}


	private static Path getIconFileLocation(int width, int height) {
		return XDGPathResolver.getUserDataLocation().resolve("icons/hicolor").resolve(width + "x" + height)
				.resolve("apps").resolve(ICON_NAME);
	}

	private static Path getDesktopFileLocation() {
		return XDGPathResolver.getUserDataLocation().resolve("applications").resolve(FILE_NAME);
	}

	private static void updateDesktopDatabase() {
		Path appDir = getDesktopFileLocation().getParent();
		ProcessBuilder builder = new ProcessBuilder("update-desktop-database", appDir.toAbsolutePath().toString());
		try {
			builder.start();
		} catch (IOException ignored) {
		}
	}

	private static void updateIconSystem() {
		ProcessBuilder builder = new ProcessBuilder("xdg-icon-resource", "forceupdate");
		try {
			builder.start();
		} catch (IOException ignored) {
		}
	}

	private static void uninject() {
		injectedLocations.forEach(p -> {
			try {
				Files.deleteIfExists(p);
			} catch (IOException ignored) {

			}
		});
		updateDesktopDatabase();
		updateIconSystem();
	}
}
