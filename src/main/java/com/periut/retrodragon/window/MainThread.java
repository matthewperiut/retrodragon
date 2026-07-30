package com.periut.retrodragon.window;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches work onto the process's FIRST thread (thread 0).
 *
 * macOS requires SDL3's Cocoa video backend -- SDL_Init, SDL_CreateWindow, the event pump -- to run
 * on thread 0, which is the JVM's main thread only under {@code -XstartOnFirstThread}. Beta renders
 * on a thread spawned by MinecraftApplet, so those calls have to be marshalled. Everything else
 * (SDL_GL_MakeCurrent, all GL, SDL_GL_SwapWindow) is legal off thread 0 and stays on the game
 * thread, so this costs one queue drain per frame rather than a round trip per GL call.
 *
 * <h2>Why the queue arrives through system properties</h2>
 *
 * {@link MacBootstrap} runs on the system classloader (it is the JVM main class), while this class
 * is loaded by Knot from the mod jar. They are different {@code MainThread} classes with separate
 * statics, so the queue cannot simply be a field here. It is created by the bootstrap and published
 * through {@link System#getProperties()}, which is one shared Hashtable both loaders see.
 *
 * Only JDK types cross that boundary -- {@link Queue}, {@link Runnable}, {@link CountDownLatch} --
 * because those come from the bootstrap loader and are therefore the same class on both sides. Pass
 * anything from this mod across and it fails with a ClassCastException that names two identical
 * class names, which is a miserable thing to debug.
 */
public final class MainThread {
	/** Shared with {@link MacBootstrap}; must stay a plain string constant on both sides. */
	public static final String QUEUE_KEY = "retrowindow.mainThreadQueue";

	/**
	 * Work thread 0 runs while the queue is empty. {@link Runnable} is a JDK type, so it crosses the
	 * classloader boundary the same way the queue does.
	 */
	public static final String IDLE_KEY = "retrowindow.mainThreadIdle";

	@SuppressWarnings("unchecked")
	private static final Queue<Runnable> QUEUE =
		(Queue<Runnable>) System.getProperties().get(QUEUE_KEY);

	private MainThread() {
	}

	/**
	 * True when the bootstrap claimed thread 0 and is draining the queue. When false every
	 * {@link #run} call executes inline, which is exactly right on Linux and Windows: their video
	 * backends have no main-thread rule, so marshalling would be pure overhead.
	 */
	public static boolean available() {
		return QUEUE != null;
	}

	/**
	 * Registers work for thread 0 to do whenever it has nothing queued.
	 *
	 * <p>AppKit only delivers window events while something services the Cocoa run loop, and the
	 * only thing doing that was the game thread marshalling its per-frame event pump. So the moment
	 * the game stopped looping -- shutting down, or blocked -- the window stopped responding and
	 * looked hung. Giving thread 0 its own pump makes responsiveness independent of the game thread.
	 */
	public static void setIdleTask(Runnable task) {
		if (available()) {
			System.getProperties().put(IDLE_KEY, task);
		}
	}

	/** Runs {@code task} on thread 0 and blocks until it finishes, propagating any exception. */
	public static void run(Runnable task) {
		if (!available() || onMainThread()) {
			task.run();
			return;
		}
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		QUEUE.add(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				thrown.set(t);
			} finally {
				done.countDown();
			}
		});
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted waiting on thread 0", e);
		}
		Throwable t = thrown.get();
		if (t != null) {
			// Rethrow on the caller so the game's own handler sees it, not thread 0's drain loop.
			if (t instanceof RuntimeException re) throw re;
			if (t instanceof Error err) throw err;
			throw new IllegalStateException(t);
		}
	}

	/** Blocking {@link #run} that returns a value. */
	public static <T> T get(java.util.function.Supplier<T> task) {
		if (!available() || onMainThread()) {
			return task.get();
		}
		AtomicReference<T> result = new AtomicReference<>();
		run(() -> result.set(task.get()));
		return result.get();
	}

	/**
	 * The bootstrap parks thread 0 in {@link MacBootstrap}, so "am I thread 0" is really "am I the
	 * thread that drains". Checked by name because the bootstrap's Thread object is not reachable
	 * from this classloader.
	 */
	private static boolean onMainThread() {
		return "main".equals(Thread.currentThread().getName());
	}
}
