package net.modificationstation.stationapi.api.client.resource;

import net.modificationstation.stationapi.api.resource.ResourceReload;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Compile stub. See the README in the root of this source set.
 *
 * <p>{@code openEarly} deliberately declares no {@code throws}. The real one throws
 * {@code org.lwjgl.LWJGLException}, which lives in THIS project's main source set -- referencing it
 * here would make the stub source set depend on main while main depends on the stub. A throws clause
 * is not part of the JVM descriptor, so Mixin matches the {@code @Overwrite} either way.
 */
public class ReloadScreenManager {
	/** @Shadow target. */
	private static Optional<Thread> thread;
	/** @Shadow target. */
	private static Optional<ResourceReload> currentReload;
	/** @Shadow target. */
	private static Executor applicationExecutor;
	/** @Accessor("LOCATIONS") target. Package-private upstream; public here costs nothing. */
	public static final List<String> LOCATIONS = null;

	/** @Overwrite target. */
	public static void openEarly() {
		throw new AssertionError("StationAPI compile stub");
	}

	/** @Invoker("onFinish") target. Package-private upstream. */
	public static void onFinish() {
		throw new AssertionError("StationAPI compile stub");
	}

	public static boolean isReloadComplete() {
		throw new AssertionError("StationAPI compile stub");
	}

	public static Optional<ResourceReload> getCurrentReload() {
		throw new AssertionError("StationAPI compile stub");
	}
}
