package com.periut.retrodragon;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RetroDragon's own settings, stored as extra lines in beta's {@code options.txt}.
 *
 * <h2>Why options.txt and not a file of our own</h2>
 *
 * These are settings a player shares when they share their config, and a second file is a second
 * thing to forget. b1.7.3's parser makes this safe almost by accident: {@code GameOptions.load()} is
 * an if-chain over the key with a per-line try/catch, so a line it does not recognise is skipped
 * rather than fatal. Keys are namespaced with a {@code retrodragon.} prefix so nothing can collide
 * with a vanilla key or with another mod doing the same thing.
 *
 * <p>The one thing vanilla does NOT do is preserve them: {@code save()} rewrites the file from its
 * own fields and anything else is gone. So {@code GameOptionsMixin} appends these back after every
 * save. Appending after vanilla's writer has closed, rather than injecting into it, is what keeps
 * this compatible -- vanilla's output is untouched, and a mod that appends its own lines at the same
 * point simply lands next to ours.
 *
 * <h2>These take effect at startup, not live</h2>
 *
 * Both settings are read in {@code preLaunch}, before the window exists, because that is when they
 * are needed: the backend decides whether the window gets a GL context at all, and the retina flag
 * decides the drawable size it is created with. Editing them applies on the next launch. See
 * {@link com.periut.retrodragon.render.RenderBackend} for why the backend in particular cannot be a
 * runtime switch.
 *
 * <h2>Precedence</h2>
 *
 * <ol>
 *   <li>a system property -- {@code -Dretrodragon.backend=gl}, {@code -Dretroperf.retina=false} and
 *       the older spellings. A launch argument is someone overriding their own config on purpose, so
 *       it wins and is never written back to the file.</li>
 *   <li>{@code options.txt}.</li>
 *   <li>the platform default below.</li>
 * </ol>
 */
public final class RetroOptions {

	/** The options.txt key for the renderer: {@code gl} or {@code webgpu}. */
	public static final String BACKEND_KEY = "retrodragon.backend";

	/** The options.txt key for the HiDPI drawable: {@code true} or {@code false}. */
	public static final String RETINA_KEY = "retrodragon.retina";

	/**
	 * Property spellings accepted for the backend, most preferred first.
	 *
	 * <p>{@code retroperf.backend} predates the rename and is still what every note, run
	 * configuration and bug report in the repo says, so it keeps working.
	 */
	private static final String[] BACKEND_PROPERTIES = {"retrodragon.backend", "retroperf.backend"};

	/** Same for retina; {@code retroperf.retina} and {@code retrowindow.highDpi} came first. */
	private static final String[] RETINA_PROPERTIES =
		{"retrodragon.retina", "retroperf.retina", "retrowindow.highDpi"};

	private static final boolean WINDOWS =
		System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	/** Values read from options.txt; null means the file did not mention the key. */
	private static volatile String fileBackend;
	private static volatile Boolean fileRetina;
	private static volatile boolean loaded;

	private RetroOptions() {
	}

	/**
	 * The renderer to try for, before any of the reasons it might not be possible.
	 *
	 * <p><b>Windows defaults to GL, everywhere else to WebGPU.</b> Not a statement about Dawn's
	 * quality on Windows -- it is that the two paths reach the screen through different amounts of
	 * driver on each platform, and GL is the one with a decade of beta running on it there.
	 * {@code RenderBackend} still degrades to GL anywhere a device cannot be created, so this only
	 * decides what is ATTEMPTED.
	 */
	public static String backend() {
		String property = firstProperty(BACKEND_PROPERTIES);
		if (property != null) {
			return normalizeBackend(property, "-D" + BACKEND_PROPERTIES[0]);
		}
		if (fileBackend != null) {
			return fileBackend;
		}
		return WINDOWS ? "gl" : "webgpu";
	}

	/** True when the drawable should match the display's physical pixels. Defaults to ON everywhere. */
	public static boolean retina() {
		for (String key : RETINA_PROPERTIES) {
			Boolean value = parseBoolean(System.getProperty(key), "-D" + key);
			if (value != null) {
				return value;
			}
		}
		if (fileRetina != null) {
			return fileRetina;
		}
		// On for every platform and both backends, including the ones where it changes nothing (a
		// non-scaled display makes it a no-op). A setting that means the same thing everywhere is one
		// a player can share, and "my config makes your game blurry" is the failure this avoids.
		return true;
	}

	/** True once {@link #load} has seen a file, so callers can tell "absent" from "not read yet". */
	public static boolean isLoaded() {
		return loaded;
	}

	/**
	 * Reads the two keys out of an options.txt.
	 *
	 * <p>Deliberately not a full parse: every other line belongs to vanilla or to another mod, and
	 * this must not care what is in them. A missing file is normal -- a first run has none, and the
	 * platform defaults are the right answer then.
	 */
	public static void load(File optionsFile) {
		loaded = true;
		if (optionsFile == null || !optionsFile.isFile()) {
			return;
		}
		try (BufferedReader reader = new BufferedReader(new FileReader(optionsFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Vanilla's own separator. Values here never contain one, so a limit is unnecessary.
				int separator = line.indexOf(':');
				if (separator <= 0) {
					continue;
				}
				String key = line.substring(0, separator).trim();
				String value = line.substring(separator + 1).trim();
				if (BACKEND_KEY.equals(key)) {
					fileBackend = normalizeBackend(value, BACKEND_KEY);
				} else if (RETINA_KEY.equals(key)) {
					fileRetina = parseBoolean(value, RETINA_KEY);
				}
			}
		} catch (IOException e) {
			RetroDragon.LOGGER.warn("could not read {} from {}: {}",
				BACKEND_KEY, optionsFile, e.toString());
		}
	}

	/**
	 * Locates and reads options.txt from the game directory. Used at preLaunch, where the game --
	 * and therefore {@code GameOptions} and the {@code File} it was handed -- does not exist yet.
	 */
	public static void loadFromGameDir() {
		try {
			load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
				.resolve("options.txt").toFile());
		} catch (Throwable t) {
			// Any failure here means the platform defaults are used, which is a working game.
			loaded = true;
			RetroDragon.LOGGER.warn("could not locate options.txt, using platform defaults: {}", t.toString());
		}
	}

	/**
	 * Re-appends our keys after vanilla has rewritten the file without them.
	 *
	 * <p>Writes what is EFFECTIVE minus the property overrides -- a launch argument is for this run,
	 * and baking {@code -Dretrodragon.backend=gl} into the config of someone testing a backend is how
	 * a temporary flag becomes permanent without anyone deciding it should be. With no property set,
	 * this writes the resolved value, so a first save records the platform default explicitly and the
	 * file thereafter says what the game is actually doing.
	 *
	 * <p>Existing copies are stripped first. Vanilla's {@code save()} rewrites from scratch so there
	 * should never be one, but a mod that preserves unknown lines would otherwise make them
	 * accumulate.
	 */
	public static void save(File optionsFile) {
		if (optionsFile == null) {
			return;
		}
		String backend = persistedBackend();
		String retina = String.valueOf(persistedRetina());
		try {
			List<String> kept = new ArrayList<>();
			if (optionsFile.isFile()) {
				for (String line : Files.readAllLines(optionsFile.toPath(), StandardCharsets.UTF_8)) {
					int separator = line.indexOf(':');
					String key = separator <= 0 ? line : line.substring(0, separator).trim();
					if (!BACKEND_KEY.equals(key) && !RETINA_KEY.equals(key)) {
						kept.add(line);
					}
				}
			}
			kept.add(BACKEND_KEY + ":" + backend);
			kept.add(RETINA_KEY + ":" + retina);
			try (Writer writer = Files.newBufferedWriter(optionsFile.toPath(), StandardCharsets.UTF_8)) {
				try (PrintWriter out = new PrintWriter(writer)) {
					for (String line : kept) {
						out.println(line);
					}
				}
			}
		} catch (IOException e) {
			// The player's vanilla settings are already safely written at this point; only ours are
			// lost, and they fall back to defaults next launch.
			RetroDragon.LOGGER.warn("could not write RetroDragon options into {}: {}",
				optionsFile, e.toString());
		}
	}

	/** A one-line summary for the startup log, so a bug report says where each value came from. */
	public static String summary() {
		return BACKEND_KEY + "=" + backend() + " (" + originOf(BACKEND_PROPERTIES, fileBackend != null) + "), "
			+ RETINA_KEY + "=" + retina() + " (" + originOf(RETINA_PROPERTIES, fileRetina != null) + ")";
	}

	private static String originOf(String[] properties, boolean inFile) {
		String property = firstPropertyKey(properties);
		if (property != null) {
			return "-D" + property;
		}
		return inFile ? "options.txt" : "platform default";
	}

	/**
	 * What {@link #save} records: the value this run would use if no launch argument were present.
	 *
	 * <p>Same shape for both settings -- the file's value if it has one, otherwise the platform
	 * default written out explicitly, so the file ends up saying what the game is actually doing
	 * rather than staying silent and relying on a default that could change.
	 */
	private static String persistedBackend() {
		return fileBackend != null ? fileBackend : (WINDOWS ? "gl" : "webgpu");
	}

	private static boolean persistedRetina() {
		return fileRetina != null ? fileRetina : true;
	}

	private static String firstProperty(String[] keys) {
		String key = firstPropertyKey(keys);
		return key == null ? null : System.getProperty(key);
	}

	private static String firstPropertyKey(String[] keys) {
		for (String key : keys) {
			if (System.getProperty(key) != null) {
				return key;
			}
		}
		return null;
	}

	/**
	 * Accepts the spellings {@code RenderBackend} already documented, so the property and the file
	 * agree on what a backend is called.
	 */
	private static String normalizeBackend(String value, String source) {
		switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "gl", "opengl":
				return "gl";
			case "webgpu", "wgpu", "dawn":
				return "webgpu";
			default:
				RetroDragon.LOGGER.warn("{}={} is not a known backend (gl|webgpu), ignoring it",
					source, value);
				return WINDOWS ? "gl" : "webgpu";
		}
	}

	/** Tri-state: true, false, or null for absent/unparseable. Bare (empty) counts as true. */
	private static Boolean parseBoolean(String value, String source) {
		if (value == null) {
			return null;
		}
		switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "", "true", "on", "yes", "1":
				return Boolean.TRUE;
			case "false", "off", "no", "0":
				return Boolean.FALSE;
			default:
				RetroDragon.LOGGER.warn("{}={} is not a boolean, ignoring it", source, value);
				return null;
		}
	}
}
