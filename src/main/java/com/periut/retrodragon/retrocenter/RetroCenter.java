package com.periut.retrodragon.retrocenter;

import com.periut.retrodragon.retrocenter.bridge.HubBridge;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Core state for the RetroCenter multiplayer hub feature. All sync
 * networking runs over RetroDragon's own vanilla-carrier probe packet
 * (RetroSyncPacket) -- no external networking library involved.
 */
public final class RetroCenter {

	public static final String SYNC_PROTOCOL_NAME = "retrocenter";
	public static final int SYNC_PROTOCOL_VERSION = 1;

	/** Launch arguments captured at init, reused to boot child instances. */
	private static volatile String[] launchArguments = new String[0];

	/**
	 * The classpath as the hub's fabric launcher sees it. Launchers like
	 * Prism provide loader libraries (e.g. Ornithe's flap) that are not on
	 * the raw java.class.path property -- child instances need them too.
	 */
	private static volatile String[] launchClasspath = new String[0];

	/** Host/port of the most recent outgoing server connection (set by ConnectScreenMixin). */
	public static volatile String lastConnectHost = null;
	public static volatile int lastConnectPort = 25565;

	private RetroCenter() {
	}

	/** Cached classloader-based self-classification (per tree). */
	private static volatile int selfInstance = Integer.MIN_VALUE;

	/**
	 * True when this Minecraft instance is a child spun up by the hub to play
	 * on one specific server -- either in-process (this class' copy lives in a
	 * child classloader tree) or as a separate process
	 * (-Dretrocenter.child=true). Classification is classloader-based, NOT
	 * thread-based: applet-era launches bounce both trees' code through the
	 * shared AWT EventQueue.
	 */
	public static boolean isChildInstance() {
		int self = selfInstance;
		if (self == Integer.MIN_VALUE) {
			selfInstance = self = HubBridge.instanceOf(RetroCenter.class);
		}
		return self != HubBridge.HUB || Boolean.getBoolean("retrocenter.child");
	}

	public static void captureLaunchArguments() {
		try {
			launchArguments = FabricLoader.getInstance().getLaunchArguments(false);
		} catch (Throwable t) {
			log("could not capture launch arguments: " + t);
		}
		try {
			// loader-internal API, but RetroDragon already relies on it
			// (Lwjgl3MixinPostProcessor's paulscode classpath fix)
			java.util.List<java.nio.file.Path> classPath =
					net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getClassPath();
			String[] entries = new String[classPath.size()];
			for (int i = 0; i < classPath.size(); i++) {
				entries[i] = classPath.get(i).toAbsolutePath().toString();
			}
			launchClasspath = entries;
			log("captured launcher classpath (" + entries.length + " entries)");
		} catch (Throwable t) {
			log("could not capture launcher classpath (java.class.path fallback): " + t);
		}
	}

	public static String[] launchArguments() {
		return launchArguments.clone();
	}

	public static String[] launchClasspath() {
		return launchClasspath.clone();
	}

	public static void log(String message) {
		System.out.println("[RetroCenter] " + message);
	}
}
