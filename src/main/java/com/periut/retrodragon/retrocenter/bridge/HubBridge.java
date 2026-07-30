package com.periut.retrodragon.retrocenter.bridge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The shared coordination point between the hub Minecraft instance and
 * in-process child instances.
 *
 * This class is loaded exactly once, by the hub's classloader, and is one of
 * the few packages a ChildClassLoader delegates to its parent -- so the hub
 * tree and every child tree see the SAME statics. The org.lwjgl facade
 * (also shared) consults it to decide who currently owns the window.
 *
 * INSTANCE IDENTITY IS CLASSLOADER-BASED, not thread-based: b1.7.3's applet
 * launch path bounces through the JVM-wide AWT EventQueue, so hub and child
 * code can run on the SAME thread at different times. A class belongs to a
 * child when its classloader chain reaches a registered ChildClassLoader;
 * everything else is hub. Per-tree classes (RetroCenter, the mixins)
 * classify themselves once; the shared org.lwjgl facade classifies its
 * CALLER via StackWalker with a per-class cache.
 *
 * Window ownership protocol ("change the screen buffer to the child"):
 * hub parks in Display.update() (releasing the GL context), the child's
 * Display.create() attaches once parked, and teardown reverses it.
 *
 * MUST reference only JDK types + ChildConfig.
 */
public final class HubBridge {

	public static final int HUB = 0;
	/** owner value meaning "whichever child is running". */
	private static final int ANY_CHILD = -1;

	private static final Object LOCK = new Object();

	/** Registered child classloaders → child instance id. */
	private static final Map<ClassLoader, Integer> childLoaders =
			Collections.synchronizedMap(new WeakHashMap<>());
	/** Classification cache: a class never migrates between trees. */
	private static final Map<Class<?>, Integer> classInstanceCache =
			Collections.synchronizedMap(new WeakHashMap<>());

	private static final StackWalker WALKER =
			StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	/** Stack frames that never identify a tree (shared/JDK infrastructure). */
	private static final String[] NEUTRAL_FRAME_PREFIXES = {
			"com.periut.retrodragon.retrocenter.bridge.",
			"org.lwjgl.",
			"java.",
			"javax.",
			"jdk.",
			"sun.",
			"com.sun.",
	};

	private static volatile int owner = HUB;
	private static volatile boolean hubParked = false;
	private static volatile boolean childRunning = false;
	private static volatile boolean childGameEnded = false;
	private static volatile ChildConfig childConfig = null;
	private static int nextChildId = 1;

	private HubBridge() {
	}

	// ------------------------------------------------------------------
	// Instance identity (classloader-based)
	// ------------------------------------------------------------------

	/**
	 * Has a ChildClassLoader ever existed in this JVM?
	 *
	 * <p>RetroDragon-specific, and a pure fast path rather than a behaviour
	 * change: {@link #callerInstance()} is called from
	 * {@code Display.update()} on EVERY frame, and a StackWalker walk of up
	 * to 32 frames per frame is not free in a mod whose entire point is
	 * frame time. Until the first child loader registers, {@link
	 * #instanceOf} cannot return anything but {@link #HUB} for any class
	 * (the loader map is empty), so answering HUB directly is exactly
	 * equivalent. Never resets: a class' tree assignment is permanent, and
	 * so is the possibility of one existing.
	 */
	private static volatile boolean anyChildLoaderRegistered = false;

	/** ChildClassLoader constructor registers itself here. */
	public static int registerChildLoader(ClassLoader loader) {
		synchronized (LOCK) {
			int id = nextChildId++;
			childLoaders.put(loader, id);
			anyChildLoaderRegistered = true;
			return id;
		}
	}

	/** Which instance does this class belong to? (HUB or a child id.) */
	public static int instanceOf(Class<?> type) {
		if (!anyChildLoaderRegistered) {
			return HUB; // see anyChildLoaderRegistered
		}
		Integer cached = classInstanceCache.get(type);
		if (cached != null) {
			return cached;
		}
		int result = HUB;
		// Walk both the parent chain and each loader's own defining loader
		// (a child Knot's CLASS is itself defined by the ChildClassLoader).
		Set<ClassLoader> visited = new HashSet<>();
		ClassLoader loader = type.getClassLoader();
		java.util.ArrayDeque<ClassLoader> queue = new java.util.ArrayDeque<>();
		if (loader != null) {
			queue.add(loader);
		}
		int steps = 0;
		while (!queue.isEmpty() && steps++ < 16) {
			ClassLoader current = queue.poll();
			if (current == null || !visited.add(current)) {
				continue;
			}
			Integer id = childLoaders.get(current);
			if (id != null) {
				result = id;
				break;
			}
			if (current.getParent() != null) {
				queue.add(current.getParent());
			}
			ClassLoader defining = current.getClass().getClassLoader();
			if (defining != null) {
				queue.add(defining);
			}
		}
		classInstanceCache.put(type, result);
		return result;
	}

	/**
	 * Which instance is calling? Walks the stack past shared infrastructure
	 * frames (org.lwjgl, the bridge, JDK) to the first frame that belongs
	 * to a tree. Used by the shared windowing facade.
	 */
	public static int callerInstance() {
		if (!anyChildLoaderRegistered) {
			return HUB; // no child tree can exist yet -- skip the stack walk
		}
		return WALKER.walk(frames -> frames
				.limit(32)
				.map(StackWalker.StackFrame::getDeclaringClass)
				.filter(c -> !isNeutralFrame(c.getName()))
				.findFirst()
				.map(HubBridge::instanceOf)
				.orElse(HUB));
	}

	private static boolean isNeutralFrame(String className) {
		for (String prefix : NEUTRAL_FRAME_PREFIXES) {
			if (className.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** Is the calling code (by classloader) part of a child instance? */
	public static boolean callerIsChild() {
		return callerInstance() != HUB;
	}

	// ------------------------------------------------------------------
	// Child configuration
	// ------------------------------------------------------------------

	public static void setChildConfig(ChildConfig config) {
		childConfig = config;
	}

	public static ChildConfig childConfig() {
		return childConfig;
	}

	// ------------------------------------------------------------------
	// Window ownership
	// ------------------------------------------------------------------

	/** Does the calling code's instance currently own the window? */
	public static boolean isOwner() {
		if (owner == HUB && !childRunning) {
			return true; // fast path: no child session -- skip the stack walk
		}
		return isOwnerInstance(callerInstance());
	}

	public static boolean isOwnerInstance(int instance) {
		int currentOwner = owner;
		if (currentOwner == ANY_CHILD) {
			return instance != HUB;
		}
		return instance == currentOwner;
	}

	public static boolean isChildRunning() {
		return childRunning;
	}

	/** Hub side: hand the window to the (already launching) child instance. */
	public static void handOwnershipToChild() {
		synchronized (LOCK) {
			owner = ANY_CHILD;
			childRunning = true;
			childGameEnded = false;
			childExitInfo = null;
			LOCK.notifyAll();
		}
	}

	/**
	 * Hub render thread parking is a pump loop in Display.update(), NOT a
	 * blocking wait: GLFW events must keep being polled on the thread that
	 * created the window (keeps the window responsive and feeds the child's
	 * input callbacks). These three calls bracket that loop.
	 */
	public static void hubParkBegin() {
		synchronized (LOCK) {
			hubParked = true;
			LOCK.notifyAll(); // wake a child waiting in awaitHubParked()
		}
	}

	/** Non-blocking: does the hub currently own the window? */
	public static boolean hubOwnsWindow() {
		return owner == HUB;
	}

	/**
	 * Runs on the hub render thread at the exact wake point (context
	 * re-acquired, BEFORE anything can render) -- the hub sets its
	 * destination screen here so its first presented frame after a child
	 * session is already the right one. No stale-screen flicker.
	 */
	private static volatile Runnable hubWakeCallback = null;

	public static void setHubWakeCallback(Runnable callback) {
		hubWakeCallback = callback;
	}

	public static Runnable consumeHubWakeCallback() {
		Runnable callback = hubWakeCallback;
		hubWakeCallback = null;
		return callback;
	}

	// ------------------------------------------------------------------
	// Present gate: the hub's last frame stays on screen (nobody overwrites
	// the front buffer while the hub is parked), so the child renders its
	// whole boot -- Mojang splash included -- into the back buffer WITHOUT
	// swapping. Presenting unlocks when the child reaches its logging-in
	// screen, so that's the first child frame the player ever sees.
	// ------------------------------------------------------------------

	private static volatile boolean childPresentEnabled = false;
	private static volatile boolean childPresentLocked = false;
	private static volatile long childAttachTime = 0;

	/** Fallback so an unexpected child flow can't freeze the window forever. */
	private static final long PRESENT_GATE_TIMEOUT_MS = 20_000;

	/** Display attach path: child took the context; hold its frames. */
	public static void noteChildAttached() {
		childPresentEnabled = false;
		childPresentLocked = false;
		childAttachTime = System.currentTimeMillis();
	}

	/** Child reached a player-facing screen -- start presenting. */
	public static void enableChildPresenting() {
		childPresentEnabled = true;
	}

	/**
	 * The child is on its way out (disconnect clicked, kicked, crashed):
	 * freeze its last gameplay frame -- the transition screens it would
	 * flicker through during teardown are never presented; the next visible
	 * frame is the hub's.
	 */
	public static void lockChildPresenting() {
		childPresentLocked = true;
	}

	public static boolean mayChildPresent() {
		if (childPresentLocked) {
			return false;
		}
		return childPresentEnabled
				|| (childAttachTime != 0 && System.currentTimeMillis() - childAttachTime > PRESENT_GATE_TIMEOUT_MS);
	}

	public static void hubParkEnd() {
		synchronized (LOCK) {
			hubParked = false;
		}
	}

	/**
	 * Child boot path: block until the hub has released the GL context.
	 * Throws if the hub doesn't park within a generous timeout.
	 */
	public static void awaitHubParked() {
		long deadline = System.currentTimeMillis() + 60_000L;
		synchronized (LOCK) {
			while (!hubParked && owner != HUB) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					throw new IllegalStateException("hub never released the window (60s timeout)");
				}
				try {
					LOCK.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			if (owner == HUB) {
				throw new IllegalStateException("ownership returned to hub before child could attach");
			}
		}
	}

	/**
	 * Child side (or its launcher's finally-block): give the window back.
	 * Safe to call multiple times.
	 */
	public static void returnOwnershipToHub(String reason) {
		synchronized (LOCK) {
			if (owner == HUB) {
				return;
			}
			owner = HUB;
			childRunning = false;
			LOCK.notifyAll();
		}
	}

	// ------------------------------------------------------------------
	// Child game lifecycle (the applet path returns from main immediately;
	// the real end-of-game signal comes from the game-side hooks)
	// ------------------------------------------------------------------

	/**
	 * Child game-end hooks (Minecraft.stop's suppressed System.exit, the
	 * child's Display detach): the child's game loop is finished.
	 */
	public static void childGameEnded(String reason) {
		synchronized (LOCK) {
			childGameEnded = true;
			LOCK.notifyAll();
		}
	}

	/**
	 * Child launcher: block until the child's game actually ends (NOT just
	 * until KnotClient.main returns -- the applet path hands the game off).
	 * Returns false on timeout.
	 */
	public static boolean awaitChildGameEnd(long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		synchronized (LOCK) {
			while (!childGameEnded) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					return false;
				}
				try {
					LOCK.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return childGameEnded;
				}
			}
			return true;
		}
	}

	/**
	 * What the hub should show after a child ends. Null = nothing noteworthy
	 * (plain disconnect → hub title screen). A kick carries the vanilla
	 * DisconnectedScreen's raw translation keys + args so the hub can show
	 * the EXACT vanilla screen; a crash carries the full report for the
	 * hub's custom crash screen.
	 */
	public static final class ChildExitInfo {
		public final boolean crash;
		/** crash: ignored; kick: DisconnectedScreen title key. */
		public final String title;
		/** crash: full crash report; kick: DisconnectedScreen message key. */
		public final String message;
		/** kick: format args (e.g. the human-readable kick reason). */
		public final String[] args;

		public ChildExitInfo(boolean crash, String title, String message, String[] args) {
			this.crash = crash;
			this.title = title;
			this.message = message;
			this.args = args;
		}
	}

	private static volatile ChildExitInfo childExitInfo = null;

	/**
	 * Child side: a crash report for the hub's crash screen. The first
	 * recorded info wins -- a specific kick/crash must not be overwritten by
	 * generic teardown messages.
	 */
	public static void noteChildCrash(String report) {
		synchronized (LOCK) {
			if (childExitInfo == null) {
				childExitInfo = new ChildExitInfo(true, null, report, null);
			}
		}
	}

	/**
	 * Child side: the raw vanilla disconnect screen parameters (translation
	 * keys + args, e.g. "disconnect.disconnected"/"disconnect.genericReason"
	 * + reason), replayed verbatim on the hub for a vanilla-identical screen.
	 */
	public static void noteChildDisconnect(String title, String message, String[] args) {
		synchronized (LOCK) {
			if (childExitInfo == null) {
				childExitInfo = new ChildExitInfo(false, title, message, args);
			}
		}
	}

	/**
	 * Hub side, after waking: what (if anything) to show. Null = plain
	 * return to the title screen. Consuming clears it.
	 */
	public static ChildExitInfo consumeChildExitInfo() {
		synchronized (LOCK) {
			ChildExitInfo info = childExitInfo;
			childExitInfo = null;
			return info;
		}
	}
}
