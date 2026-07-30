package com.periut.retrodragon.retrocenter.child;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.bridge.ChildConfig;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;

/**
 * Boots a second Minecraft instance INSIDE this JVM: a fresh fabric-loader
 * (KnotClient) in an isolating {@link ChildClassLoader}, pointed at the
 * per-server game directory. The shared org.lwjgl facade hands the window
 * over (see HubBridge); when the child's game loop returns, ownership goes
 * back to the hub.
 *
 * Classpath handling: the child must not see the full RetroDragon artifact
 * (in-tree org.lwjgl would break window sharing), so entries belonging to
 * this mod's code source are filtered out; the child instead loads the
 * "childshim" jar from its mods folder (seeded by ServerProfiles).
 *
 * EXPERIMENTAL: the in-process boot is the designed-for path but has only
 * compile-time verification so far; runtime debugging is expected. The
 * java.class.path system property is rewritten for the duration of the
 * child's life (fabric-loader reads it during boot) and restored on exit.
 */
public final class InProcessChildLauncher {

	private InProcessChildLauncher() {
	}

	/** Launches the child boot thread. Returns immediately. */
	public static void launch(ChildConfig config) {
		Thread thread = new Thread(() -> run(config), "RetroCenter-Child");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run(ChildConfig config) {
		RetroCenter.log("child booting: " + config);

		String originalClasspath = System.getProperty("java.class.path", "");
		// Applet-era launchers point the game's run directory at this
		// property; the child must see its own profile dir. The hub's
		// Minecraft already cached its runDirectory, so this is safe.
		String originalTargetDir = System.getProperty("minecraft.applet.TargetDirectory");
		System.setProperty("minecraft.applet.TargetDirectory", config.gameDir);
		String reason = null;
		try {
			List<String> filtered = buildChildClasspath(originalClasspath);
			String childClasspath = String.join(File.pathSeparator, filtered);
			System.setProperty("java.class.path", childClasspath);

			ChildClassLoader loader = new ChildClassLoader(toUrls(filtered), HubBridge.class.getClassLoader());
			Thread.currentThread().setContextClassLoader(loader);
			RetroCenter.log("child #" + loader.instanceId + " classloader ready");

			String[] args = buildArgs(config);
			Class<?> knot = Class.forName("net.fabricmc.loader.impl.launch.knot.KnotClient", false, loader);
			Method main = knot.getMethod("main", String[].class);
			main.invoke(null, (Object) args);

			// b1.7.3's applet launch path returns from main immediately --
			// the game continues on the AWT EventQueue / its own game
			// thread. The REAL end-of-game signal comes from the child's
			// game hooks (suppressed System.exit, Display detach).
			RetroCenter.log("child main returned -- waiting for the child's game to end");
			if (!HubBridge.awaitChildGameEnd(12 * 60 * 60 * 1000L)) {
				reason = "Child instance timed out";
			}
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			cause.printStackTrace();
			reason = "Child instance crashed: " + cause;
			HubBridge.noteChildCrash(stackTraceOf(cause));
		} catch (Throwable t) {
			t.printStackTrace();
			reason = "Child instance failed to boot: " + t;
			HubBridge.noteChildCrash(stackTraceOf(t));
		} finally {
			System.setProperty("java.class.path", originalClasspath);
			if (originalTargetDir != null) {
				System.setProperty("minecraft.applet.TargetDirectory", originalTargetDir);
			} else {
				System.clearProperty("minecraft.applet.TargetDirectory");
			}
			HubBridge.returnOwnershipToHub(reason);
			// Kick the hub's event-pump park loop awake immediately. Backend-agnostic:
			// Display routes this to glfwPostEmptyEvent or SDL_PushEvent(SDL_EVENT_USER)
			// depending on which windowing backend is live.
			try {
				org.lwjgl.opengl.Display.wakeEventLoop();
			} catch (Throwable ignored) {
			}
			RetroCenter.log("child ended: " + (reason != null ? reason : "game ended"));
		}
	}

	/**
	 * The child classpath: the union of what the hub's fabric launcher
	 * actually resolved (launchers like Prism include loader libraries --
	 * Ornithe's flap etc. -- that never appear on the raw java.class.path
	 * property) and the property itself, minus this mod's own code source
	 * (classes dir or jar) and its sibling build dirs -- the child gets the
	 * shim jar via its mods folder instead.
	 */
	static List<String> buildChildClasspath(String classpathProperty) {
		java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
		for (String entry : RetroCenter.launchClasspath()) {
			entries.add(entry);
		}
		for (String entry : classpathProperty.split(File.pathSeparator)) {
			if (!entry.isEmpty()) {
				entries.add(entry);
			}
		}
		// NOTE deliberately NOT added: -javaagent jars (e.g. Ornithe's flap
		// mod-variant remapper). Agent classes are JVM-global singletons --
		// flap registers a jimfs filesystem with a fixed JVM-wide name at
		// class init, so a child-local COPY can never initialize
		// (FileSystemAlreadyExistsException). The child instead reaches the
		// hub's already-initialized agent classes through ChildClassLoader's
		// system-classloader fallback. Launcher-agnostic by construction.

		String ownCode = ownCodeSourcePath();
		String ownBuildRoot = null;
		if (ownCode != null) {
			// dev: .../build/classes/java/main → filter everything under .../build/
			int idx = ownCode.indexOf(File.separator + "build" + File.separator);
			if (idx > 0) {
				ownBuildRoot = ownCode.substring(0, idx) + File.separator + "build" + File.separator;
			}
		}
		List<String> result = new ArrayList<>();
		for (String entry : entries) {
			if (ownCode != null && entry.equals(ownCode)) {
				continue;
			}
			if (ownBuildRoot != null && entry.startsWith(ownBuildRoot)) {
				continue; // classes + resources of this project (dev runs)
			}
			if (isLwjglEntry(entry)) {
				// Launchers ship the REAL LWJGL2 for b1.7.3. The child's
				// Knot is child-first: with this jar on its classpath it
				// loads org.lwjgl from it instead of delegating to the
				// hub's shared facade -- wrong library, clashing natives.
				continue;
			}
			result.add(entry);
		}
		return result;
	}

	private static boolean isLwjglEntry(String entry) {
		String fileName = entry.substring(entry.lastIndexOf(File.separatorChar) + 1).toLowerCase(java.util.Locale.ROOT);
		return fileName.startsWith("lwjgl")
				|| entry.contains(File.separator + "org" + File.separator + "lwjgl" + File.separator)
				|| entry.contains("org.lwjgl");
	}

	private static String stackTraceOf(Throwable t) {
		java.io.StringWriter stack = new java.io.StringWriter();
		t.printStackTrace(new java.io.PrintWriter(stack));
		return stack.toString();
	}

	private static String ownCodeSourcePath() {
		try {
			URL location = RetroCenter.class.getProtectionDomain().getCodeSource().getLocation();
			return Paths.get(location.toURI()).toAbsolutePath().toString();
		} catch (Throwable t) {
			return null;
		}
	}

	private static URL[] toUrls(List<String> entries) throws Exception {
		URL[] urls = new URL[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			urls[i] = Paths.get(entries.get(i)).toUri().toURL();
		}
		return urls;
	}

	/**
	 * The hub's original launch arguments with --gameDir replaced by the
	 * child's isolated directory. Session/server overrides flow through
	 * HubBridge.childConfig() instead of args (read by MinecraftAppletMixin
	 * in the child tree), so no secrets land in argument lists.
	 */
	static String[] buildArgs(ChildConfig config) {
		String[] original = RetroCenter.launchArguments();
		Map<String, String> options = new LinkedHashMap<>();
		List<String> positional = new ArrayList<>();
		for (int i = 0; i < original.length; i++) {
			String arg = original[i];
			if (arg.startsWith("--") && i + 1 < original.length && !original[i + 1].startsWith("--")) {
				options.put(arg, original[++i]);
			} else if (arg.startsWith("--")) {
				options.put(arg, null);
			} else {
				positional.add(arg);
			}
		}
		options.put("--gameDir", config.gameDir);

		List<String> args = new ArrayList<>(positional);
		for (Map.Entry<String, String> e : options.entrySet()) {
			args.add(e.getKey());
			if (e.getValue() != null) {
				args.add(e.getValue());
			}
		}
		return args.toArray(new String[0]);
	}

	/** For tests/debugging. */
	static List<String> debugFilteredClasspath() {
		return buildChildClasspath(System.getProperty("java.class.path", ""));
	}
}
