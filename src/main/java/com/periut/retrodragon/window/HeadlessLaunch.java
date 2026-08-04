package com.periut.retrodragon.window;

import net.minecraft.client.util.Session;

/**
 * Starts the game without an applet, for the macOS SDL3 path.
 *
 * <p>Loader's normal b1.7.3 client entrypoint wraps the game in {@code AppletLauncher} inside an
 * {@code AppletFrame}. Both are unusable here: {@link java.applet.Applet}'s own constructor throws
 * {@link java.awt.HeadlessException}, and a non-headless AWT on macOS deadlocks against
 * {@code -XstartOnFirstThread} (verified: it blocks in {@code Frame.dispose()}). The applet was
 * only ever a container for a window that SDL3 now supplies, so it is skipped entirely.
 *
 * <p>This mirrors what {@code MinecraftAppletMixin.init} + {@code startThread} do, minus everything
 * AWT-shaped. It is invoked reflectively by {@link MacBootstrap} through Knot's classloader, so the
 * game classes it touches here are fully transformed.
 */
public final class HeadlessLaunch {
	private HeadlessLaunch() {
	}

	/** @param args the game's launch arguments, as loader would have passed them to the applet. */
	public static void start(String[] args) {
		adoptLoaderGameDir();

		// The applet's init() is also where OSL dispatches every mod's init entrypoint, and skipping
		// the applet skipped that too. See invokeOslEntrypoints.
		invokeOslEntrypoints(args);

		java.util.Map<String, String> a = new java.util.HashMap<>();
		java.util.List<String> loose = new java.util.ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
				a.put(args[i].substring(2), args[++i]);
			} else {
				loose.add(args[i]);
			}
		}

		int width = intOr(a.get("width"), 854);
		int height = intOr(a.get("height"), 480);

		BrnoMinecraft minecraft = new BrnoMinecraft(width, height, false);
		minecraft.session = new Session(username(a, loose), sessionId(a, loose));
		// Beta appends this to skin/cape URLs; the applet took it from the document base, which a
		// headless launch has no equivalent of. Empty is what the offline path effectively used.
		minecraft.hostAddress = "";
		System.out.println("Setting user: " + minecraft.session.username);

		if (a.containsKey("server") && a.containsKey("port")) {
			minecraft.setStartupServer(a.get("server"), intOr(a.get("port"), 25565));
		}

		// RetroCenter: a child instance reuses the hub's authenticated session (retroauth
		// re-derives its auth state from the raw sessionId string) and autoconnects to its
		// assigned server, exactly as MinecraftAppletMixin.init does on the applet path.
		com.periut.retrodragon.retrocenter.bridge.ChildConfig childConfig =
				com.periut.retrodragon.retrocenter.bridge.HubBridge.childConfig();
		if (com.periut.retrodragon.retrocenter.RetroCenter.isChildInstance() && childConfig != null) {
			minecraft.session = new Session(childConfig.username, childConfig.sessionId);
			minecraft.setStartupServer(childConfig.host, childConfig.port);
			System.out.println("[RetroCenter] child session + autoconnect configured for "
					+ childConfig.host + ":" + childConfig.port);
		}

		// Still its own thread, exactly as the applet path did: thread 0 belongs to SDL3 and must
        // stay free to drain MainThread work, and RetroCenter's hub/child model assumes the game
		// loop is not the main thread.
		Thread thread = new Thread(minecraft::run, "Minecraft main thread");
		thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
		thread.start();

		// Published so thread 0 knows what to outlive. This method returns immediately, so without
		// it MacBootstrap's drain loop would see its launching thread die and quit while the game
		// was still starting. Thread is a JDK type, so it survives the classloader boundary.
		System.getProperties().put(GAME_THREAD_KEY, thread);
	}

	/**
	 * Points the game at the launcher's run directory, the way loader's applet path does.
	 *
	 * <p>Loader does not tell b1.7.3 where to put its files directly. Its {@code EntrypointPatch}
	 * wraps the game's own directory lookup in {@code AppletMain.hookGameDir}, and that returns
	 * {@code AppletLauncher.gameDir} -- a static field only loader's APPLET launcher ever assigns.
	 * A launch that skips the applet, as this one must, leaves it null, so the hook returns the
	 * value it was given and the game falls back to vanilla's per-user directory
	 * ({@code ~/Library/Application Support/minecraft} on macOS) instead of {@code run/}.
	 *
	 * <p>Nothing fails loudly when that happens, which is what made it expensive to find: the game
	 * comes up, renders, and quietly keeps a second set of saves, options and texture packs. The
	 * visible symptom is silence. b1.7.3 loads every sound from {@code <gameDir>/resources}, which
	 * b1.7.3 no longer downloads (the S3 bucket is long dead), so the files only exist where the
	 * launcher put them; point the game elsewhere and every {@code playSound} finds no entry and
	 * returns without a word.
	 *
	 * <p>So this sets the game's own run-directory cache directly rather than relying on loader's
	 * hook reaching this launch path at all, and then keeps {@code AppletLauncher.gameDir} in
	 * agreement so the hook cannot overwrite it where it IS applied. Either alone is a bet on
	 * loader's internals; together they do not care which way the patch went.
	 */
	private static void adoptLoaderGameDir() {
		java.io.File gameDir;
		try {
			gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
		} catch (Throwable t) {
			System.err.println("[RetroDragon] no launcher game directory (" + t
					+ "); saves and sounds will use the vanilla per-user location instead.");
			return;
		}

		// Seed the game's own cache. This is the part that actually decides the directory: whether
		// or not loader's hook is in the picture, getRunDirectory() returns this once it is set.
		try {
			com.periut.retrodragon.window.mixin.lwjgl3.MinecraftAccessor.setRunDirectoryCache(gameDir);
		} catch (Throwable t) {
			System.err.println("[RetroDragon] could not set the game's run directory: " + t);
		}

		// And keep loader's hook agreeing with it. Where the hook IS applied it rewrites the same
		// field through AppletMain.hookGameDir, which returns AppletLauncher.gameDir -- so leaving
		// that null would let the hook overwrite what was just set with the vanilla location.
		// Reflective because AppletLauncher is loader-internal (net.fabricmc.loader.impl.*) and
		// must not become a compile-time dependency; only assigned when unset, so a real applet
		// launch stays in charge.
		try {
			java.lang.reflect.Field field = Class
					.forName("net.fabricmc.loader.impl.game.minecraft.applet.AppletLauncher")
					.getField("gameDir");
			if (field.get(null) == null) {
				field.set(null, gameDir);
			}
		} catch (Throwable t) {
			// Not fatal on its own -- the cache above already points the game at the right place.
			System.out.println("[RetroDragon] loader's applet game directory not available (" + t
					+ "); using the run directory set directly.");
		}

		System.out.println("[RetroDragon] game directory: " + gameDir);
	}

	/** Shared with {@link MacBootstrap}; keep in sync on both sides. */
	/**
	 * Runs the {@code init} and {@code client-init} entrypoints, and OSL's launch events, exactly where
	 * the applet path runs them: at the head of what would have been {@code MinecraftApplet.init}, before
	 * the game object exists.
	 *
	 * <p>This is not an optional nicety, it is the one thing the applet did that nothing else does. OSL
	 * dispatches every mod's client-side initialisation from a single {@code @Inject} at the head of
	 * {@code MinecraftApplet.init}. Skipping the applet skipped the dispatch, so on macOS every OSL mod
	 * silently never initialised: RetroAPI registered no blocks, no items and no recipes, and the game
	 * came up vanilla with the mods listed in the log and no error anywhere. It is a quiet failure by
	 * construction, because a mod that never runs cannot complain.
	 *
	 * <p>Reflective and guarded, because RetroDragon does not depend on OSL and has to keep running
	 * without it. It cannot double-fire: this path exists precisely because the applet one is not taken.
	 */
	private static void invokeOslEntrypoints(String[] args) {
		net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
		if (!loader.isModLoaded("osl-entrypoints")) {
			return;
		}
		invokeEntrypoint(loader, "net.ornithemc.osl.entrypoints.api.ModInitializer", "init");
		invokeEntrypoint(loader, "net.ornithemc.osl.entrypoints.api.client.ClientModInitializer", "initClient");

		// The launch-argument events, which is how OSL mods read their own command line options.
		try {
			Class<?> launchUtils = Class.forName("net.ornithemc.osl.entrypoints.impl.launch.LaunchUtils");
			launchUtils.getMethod("triggerLaunchEvents", String[].class)
				.invoke(null, (Object) loader.getLaunchArguments(false));
		} catch (ReflectiveOperationException | LinkageError e) {
			System.err.println("[RetroDragon] could not trigger OSL launch events: " + e);
		}
	}

	private static void invokeEntrypoint(net.fabricmc.loader.api.FabricLoader loader, String type, String method) {
		Class<?> initializer;
		try {
			initializer = Class.forName(type);
		} catch (ClassNotFoundException | LinkageError e) {
			return; // a partial OSL install: nothing to dispatch to
		}
		String key;
		java.lang.reflect.Method call;
		try {
			key = (String) initializer.getField("ENTRYPOINT_KEY").get(null);
			call = initializer.getMethod(method);
		} catch (ReflectiveOperationException e) {
			System.err.println("[RetroDragon] unrecognised OSL entrypoint interface " + type + ": " + e);
			return;
		}

		// Only the OSL half needs reflection. Loader's own containers are its public API, and going
		// through getClass() on those instead lands on loader-internal implementation classes that
		// reflection is not allowed to touch.
		for (net.fabricmc.loader.api.entrypoint.EntrypointContainer<?> container
				: loader.getEntrypointContainers(key, initializer)) {
			String modId = container.getProvider().getMetadata().getId();
			try {
				call.invoke(container.getEntrypoint());
			} catch (java.lang.reflect.InvocationTargetException e) {
				// A mod failing its own initialisation is that mod's problem, and it should look like
				// one: rethrow so the crash names it rather than a window class it never heard of.
				throw new RuntimeException("Mod '" + modId + "' failed in its '" + key + "' entrypoint",
					e.getCause());
			} catch (IllegalAccessException e) {
				System.err.println("[RetroDragon] could not run '" + key + "' for " + modId + ": " + e);
			}
		}
	}

	public static final String GAME_THREAD_KEY = "retrowindow.gameThread";

	/**
	 * The launcher's player name, or a throwaway one when the game was started without credentials.
	 *
	 * <p>Pre-1.6 versions have no named arguments for this. Prism, and every other launcher that
	 * speaks b1.7.3, passes the pair positionally ahead of the flags:
	 * {@code <username> <session> --gameDir ... --assetsDir ...}, which is exactly what the version
	 * manifest asks for ({@code "${auth_player_name} ${auth_session} --gameDir ..."}). Loader keeps
	 * those two as leftovers and its {@code AppletFrame} reads the pair back out of them; a launch
	 * that skips the applet, as this one must, has to do the same or it throws the credentials away.
	 *
	 * <p>Note the positional pair does not stay at the front. Loader parses the command line into
	 * named values plus leftovers and rebuilds it in that order, so by the time it reaches this
	 * method the credentials trail the flags. Reading them by index would be wrong; they are
	 * whatever the flag pass did not consume.
	 *
	 * <p>{@code --username} and {@code --session} still win where a launcher sends them, so this
	 * only adds a fallback and cannot regress a launcher that was already working.
	 */
	private static String username(java.util.Map<String, String> named, java.util.List<String> loose) {
		String username = named.get("username");
		if (username == null && !named.containsKey("session") && loose.size() == 2) {
			username = loose.get(0);
		}
		return username != null ? username : "Player" + System.currentTimeMillis() % 10000;
	}

	/** The launcher's session token, in the same two shapes as {@link #username}. Empty is offline. */
	private static String sessionId(java.util.Map<String, String> named, java.util.List<String> loose) {
		String session = named.get("session");
		if (session == null && loose.size() == 2) {
			session = loose.get(1);
		}
		return session != null ? session : "";
	}

	private static int intOr(String value, int fallback) {
		try {
			return value == null ? fallback : Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
