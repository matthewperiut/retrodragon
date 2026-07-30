package com.periut.retrodragon.window;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.system.Configuration;

public class RetroWindowPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		String os = System.getProperty("os.name", "").toLowerCase();

		if (os.contains("linux")) {
			setupLinux();
		} else if (os.contains("mac")) {
			setupMacOS();
		}

	}

	private void setupLinux() {
		// GNOME lacks server-side decorations, so GLFW relies on libdecor's GTK
		// plugin for client-side decorations. The LWJGL-bundled GLFW has a
		// different libdecor build, so use the system GLFW on GNOME Wayland.
		String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
		if (waylandDisplay == null || waylandDisplay.isEmpty()) {
			return;
		}

		String desktop = System.getenv("XDG_CURRENT_DESKTOP");
		if (desktop == null || !desktop.toUpperCase().contains("GNOME")) {
			return;
		}

		String arch = System.getProperty("os.arch", "");

		// Try common system GLFW paths, preferring arch-appropriate directories
		String[] candidates;
		if (arch.contains("aarch64")) {
			candidates = new String[]{
				"/usr/lib/aarch64-linux-gnu/libglfw.so.3",
				"/usr/lib/aarch64-linux-gnu/libglfw.so",
				"/usr/lib64/libglfw.so.3",
				"/usr/lib64/libglfw.so",
				"/usr/lib/libglfw.so.3",
				"/usr/lib/libglfw.so",
			};
		} else if (arch.contains("64")) {
			candidates = new String[]{
				"/usr/lib/x86_64-linux-gnu/libglfw.so.3",
				"/usr/lib/x86_64-linux-gnu/libglfw.so",
				"/usr/lib64/libglfw.so.3",
				"/usr/lib64/libglfw.so",
				"/usr/lib/libglfw.so.3",
				"/usr/lib/libglfw.so",
			};
		} else {
			candidates = new String[]{
				"/usr/lib/libglfw.so.3",
				"/usr/lib/libglfw.so",
			};
		}

		for (String path : candidates) {
			if (new java.io.File(path).exists()) {
				Configuration.GLFW_LIBRARY_NAME.set(path);
				System.out.println("[RetroDragon] GNOME Wayland detected, using system GLFW: " + path);
				return;
			}
		}
	}

	private void setupMacOS() {
		// The two backends want opposite things from thread 0, so macOS has two launch shapes.
		//
		//   SDL3: needs -XstartOnFirstThread AND a thread 0 that never returns, because its Cocoa
		//         video backend initializes nowhere else. MacBootstrap provides both.
		//   GLFW: needs -XstartOnFirstThread GONE, because fabric-loader's applet wrapper builds a
		//         java.awt.Frame and a non-headless AWT deadlocks against that flag. glfw_async
		//         dispatches Cocoa calls to the main thread internally instead.
		if (com.periut.retrodragon.window.sdl.Sdl3Window.ENABLED) {
			setupMacOSSdl();
			return;
		}
		setupMacOSGlfw();
	}

	private void setupMacOSSdl() {
		if (com.periut.retrodragon.window.MainThread.available()) {
			System.out.println("[RetroDragon] macOS SDL3 backend, thread 0 held by MacBootstrap");
			return;
		}
		// Either the flag is missing or the JVM entered through Knot directly. Both are fixed by
		// the same relaunch, so don't bother distinguishing them.
		relaunchThroughBootstrap();
	}

	private void setupMacOSGlfw() {
		long pid = ProcessHandle.current().pid();
		if ("1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid))) {
			relaunchWithoutStartOnFirstThread();
			return;
		}

		// On macOS without -XstartOnFirstThread, use LWJGL's glfw_async library
		// which dispatches Cocoa calls to the main thread internally.
		// This avoids the need for -XstartOnFirstThread entirely.
		Configuration.GLFW_CHECK_THREAD0.set(false);
		Configuration.GLFW_LIBRARY_NAME.set("glfw_async");

		System.out.println("[RetroDragon] macOS detected, using the GLFW backend (glfw_async)");
	}

	/**
	 * Respawns the JVM with {@code -XstartOnFirstThread} and {@link com.periut.retrodragon.window.MacBootstrap}
	 * as the main class, so thread 0 belongs to SDL3 rather than to a Knot call that returns.
	 *
	 * <p>The bootstrap lives in this mod jar, which in a production install sits in {@code mods/}
	 * and is therefore invisible to the JVM launcher. Its jar is appended to {@code -cp} so the
	 * system loader can find it; Knot still loads the mod normally from {@code mods/}, giving two
	 * copies of these classes in different loaders. That is why MainThread hands its queue across
	 * through system properties instead of a static field.
	 */
	private void relaunchThroughBootstrap() {
		if (System.getProperty("retrowindow.relaunched") != null) {
			System.err.println("[RetroDragon] MacBootstrap did not take thread 0 after relaunch;"
					+ " falling back to the GLFW backend. Run with -Dretrowindow.sdl=false to silence this.");
			Configuration.GLFW_CHECK_THREAD0.set(false);
			Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
			return;
		}
		try {
			java.util.List<String> command = relaunchCommandPrefix();
			command.add("-XstartOnFirstThread");
			// Appended, never prepended: loom matches its dev-environment mod groups against exact
			// classpath entries, and reordering them makes loader discover no mods at all. All this
			// entry has to do is let the system loader find MacBootstrap in a production install,
			// where the mod otherwise lives only in mods/.
			command.add("-cp");
			command.add(System.getProperty("java.class.path")
					+ java.io.File.pathSeparator + ownJarPath());

			command.add(com.periut.retrodragon.window.MacBootstrap.class.getName());
			command.addAll(java.util.Arrays.asList(
					net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE.getLaunchArguments(false)));

			System.out.println("[RetroDragon] macOS SDL3 backend needs thread 0; relaunching through MacBootstrap...");
			exec(command);
		} catch (Exception e) {
			System.err.println("[RetroDragon] MacBootstrap relaunch failed: " + e
					+ " -- falling back to the GLFW backend.");
			Configuration.GLFW_CHECK_THREAD0.set(false);
			Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
		}
	}

	/** Filesystem path of the jar (or classes dir, in dev) this mod was loaded from. */
	private static String ownJarPath() throws Exception {
		return new java.io.File(com.periut.retrodragon.window.MacBootstrap.class
				.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
	}

	/**
	 * Respawns the JVM without -XstartOnFirstThread, pipes its output through
	 * this process (so launchers stay attached to their child and keep showing
	 * logs), and exits with the game's exit code.
	 *
	 * The command is rebuilt from the running JVM (input args + classpath +
	 * Knot + the game's launch args) rather than the original argv: launcher
	 * wrappers like Prism's EntryPoint read their config from stdin, which is
	 * already consumed, so re-running the original command would hang.
	 */
	private void relaunchWithoutStartOnFirstThread() {
		if (System.getProperty("retrowindow.relaunched") != null) {
			// Relaunch guard: something re-added the flag; don't loop forever.
			System.err.println("[RetroDragon] -XstartOnFirstThread still present after relaunch, giving up."
					+ " Remove it from your launcher's Java arguments (Prism: the FirstThreadOnMacOS trait).");
			return;
		}

		try {
			java.util.List<String> command = relaunchCommandPrefix();
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));

			boolean client = net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
					== net.fabricmc.api.EnvType.CLIENT;
			command.add(client
					? "net.fabricmc.loader.impl.launch.knot.KnotClient"
					: "net.fabricmc.loader.impl.launch.knot.KnotServer");
			command.addAll(java.util.Arrays.asList(
					net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE.getLaunchArguments(false)));

			System.out.println("[RetroDragon] -XstartOnFirstThread deadlocks AWT on macOS; relaunching without it...");
			exec(command);
		} catch (Exception e) {
			System.err.println("[RetroDragon] Relaunch failed: " + e.getMessage()
					+ " -- remove -XstartOnFirstThread from your launcher's Java arguments manually"
					+ " (Prism: the FirstThreadOnMacOS trait).");
		}
	}

	/**
	 * java binary + the current JVM's own arguments, minus -XstartOnFirstThread.
	 *
	 * Rebuilt from the running JVM rather than the original argv because launcher wrappers like
	 * Prism's EntryPoint read their config from stdin, which is already consumed by the time we get
	 * here -- re-running the original command would hang waiting for input that never comes.
	 */
	private static java.util.List<String> relaunchCommandPrefix() {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add(ProcessHandle.current().info().command()
				.orElse(System.getProperty("java.home") + "/bin/java"));
		command.add("-Dretrowindow.relaunched=true");

		// In a dev environment loom launches through DevLaunchInjector, which sets loader's
		// classpath-group and remap properties plus the LWJGL native paths at RUNTIME. They are not
		// JVM arguments, so a command rebuilt from getInputArguments() loses them and the child then
		// discovers almost no mods -- which shows up much later as a NoSuchMethodError from a shim
		// that was never injected. Carry the resolved values across explicitly.
		for (String key : new String[]{"java.library.path", "org.lwjgl.librarypath"}) {
			String value = System.getProperty(key);
			if (value != null) {
				command.add("-D" + key + "=" + value);
			}
		}
		for (String name : System.getProperties().stringPropertyNames()) {
			if (name.startsWith("fabric.") && !name.equals("fabric.dli.main")) {
				command.add("-D" + name + "=" + System.getProperty(name));
			}
		}
		// -XstartOnFirstThread is consumed by the java launcher and not reported here, but filter
		// defensively in case a JVM passes it: callers add it back only when they want it.
		for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (!"-XstartOnFirstThread".equals(arg)) {
				command.add(arg);
			}
		}
		return command;
	}

	/** Runs the child with our stdio so launchers stay attached, then exits with its code. */
	private static void exec(java.util.List<String> command) throws Exception {
		Process child = new ProcessBuilder(command).inheritIO().start();
		Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
		System.exit(child.waitFor());
	}
}
