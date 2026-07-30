package com.periut.retrodragon.window;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * JVM main class for macOS: claims thread 0 for SDL3 and runs Knot beside it.
 *
 * With {@code -XstartOnFirstThread} the JVM's main thread IS the process's first thread, which is
 * the only thread SDL3's Cocoa backend will initialize video on. Knot cannot simply run there --
 * it returns once the game thread is spawned, and a returned main thread stops being able to host
 * anything. So Knot moves to its own thread and thread 0 stays here forever, draining work that
 * {@link MainThread} sends it.
 *
 * <p>This class is loaded by the SYSTEM classloader, not Knot's, so it must touch nothing from this
 * mod beyond the queue key. Knot is reached reflectively for the same reason: naming
 * {@code KnotClient} directly would resolve against whatever the launcher happens to have on the
 * classpath at build time.
 *
 * <p>AWT is forced headless. A non-headless AWT on macOS demands ownership of the main run loop,
 * which is precisely the thread SDL3 needs; the two cannot both have it. Nothing on the normal path
 * needs a real AWT window now that BrnoMinecraft builds its crash Frame lazily.
 */
public final class MacBootstrap {
	private MacBootstrap() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");

		Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
		System.getProperties().put(MainThread.QUEUE_KEY, queue);

		Thread knot = new Thread(() -> {
			try {
				launchHeadless(args);
			} catch (Throwable t) {
				t.printStackTrace();
				System.exit(1);
			}
		}, "knot");
		// Non-daemon: the JVM must outlive this bootstrap's drain loop, not the other way round.
		knot.setDaemon(false);
		knot.start();

		System.out.println("[RetroDragon] thread 0 claimed for SDL3; Knot running on 'knot'");
		drain(knot);
	}

	/**
	 * Does what {@code KnotClient.main} does, minus the AWT window.
	 *
	 * <p>{@code Knot.launch} is {@code init(args)} followed by {@code provider.launch(classLoader)},
	 * and only that second half is a problem: loader hardcodes {@code AppletMain} as the b1.7.3
	 * client entrypoint, which builds a {@link java.awt.Frame}. Under {@code -XstartOnFirstThread}
	 * AWT hangs the moment a Frame is realized (verified: it blocks in {@code dispose()}), because
	 * AppKit wants the main run loop that SDL3 is holding.
	 *
	 * <p>Nor can the applet be kept and merely un-framed: {@link java.applet.Applet}'s constructor
	 * throws {@link java.awt.HeadlessException} on its own. So the first half runs untouched -- same
	 * classloader, same mods, same mixins, same preLaunch -- and the second is replaced with
	 * {@link HeadlessLaunch}, which builds the game directly. The applet was only ever a container
	 * for a window SDL3 now supplies.
	 *
	 * <p>Reflective throughout because these are loader-internal classes, and HeadlessLaunch must
	 * come from Knot's classloader so the game classes it touches are transformed.
	 */
	private static void launchHeadless(String[] args) throws Exception {
		Class<?> envTypeCls = Class.forName("net.fabricmc.api.EnvType");
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object client = Enum.valueOf((Class<Enum>) envTypeCls.asSubclass(Enum.class), "CLIENT");

		Class<?> knotCls = Class.forName("net.fabricmc.loader.impl.launch.knot.Knot");
		Object knot = knotCls.getConstructor(envTypeCls).newInstance(client);
		ClassLoader target = (ClassLoader) knotCls.getMethod("init", String[].class)
				.invoke(knot, (Object) args);

		// Hand off to the mod's own copy, loaded by Knot so the game classes it touches are
		// transformed. Reflective because this class lives on the system classloader.
		Class.forName("com.periut.retrodragon.window.HeadlessLaunch", true, target)
				.getMethod("start", String[].class)
				.invoke(null, (Object) args);
	}

	/**
	 * Thread 0's whole life. Parks on the queue rather than spinning: SDL work arrives a couple of
	 * times a frame at most, and a busy-wait here would burn a core doing nothing.
	 */
	private static void drain(Thread knot) {
		Queue<Runnable> queue = queue();
		while (true) {
			Runnable task = queue.poll();
			if (task != null) {
				try {
					task.run();
				} catch (Throwable t) {
					// A failed task already reported to its caller through MainThread.run;
					// thread 0 must survive it or every later SDL call hangs forever.
					t.printStackTrace();
				}
				continue;
			}
			// Nothing queued: service the platform event loop so the window stays responsive even
			// when the game thread is busy or shutting down.
			Runnable idle = (Runnable) System.getProperties().get(MainThread.IDLE_KEY);
			if (idle != null) {
				try {
					idle.run();
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}

			// The launching thread finishes as soon as it has spawned the game, so quitting on it
			// would kill the pump mid-startup. Follow the game thread once it exists.
			Thread game = (Thread) System.getProperties().get(HeadlessLaunch.GAME_THREAD_KEY);
			Thread alive = game != null ? game : knot;
			if (!alive.isAlive() && queue.isEmpty()) {
				return;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Queue<Runnable> queue() {
		return (Queue<Runnable>) System.getProperties().get(MainThread.QUEUE_KEY);
	}
}
