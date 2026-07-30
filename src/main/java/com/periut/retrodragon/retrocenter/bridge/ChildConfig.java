package com.periut.retrodragon.retrocenter.bridge;

/**
 * Everything a child instance needs to boot: which server to join, the
 * authenticated session to reuse, and its isolated game directory.
 *
 * The session is shared by value: retroauth derives all of its auth state
 * (accessToken, profile UUID) by parsing the raw sessionId string
 * ("token:&lt;accessToken&gt;:&lt;uuid&gt;"), so a child that constructs
 * new Session(username, sessionId) re-authenticates identically with no
 * retroauth changes and no extra plumbing.
 *
 * MUST stay JDK-only in its field types: this class is loaded
 * by the hub's classloader and shared with child classloader trees.
 */
public final class ChildConfig {

	public final String host;
	public final int port;
	public final String username;
	public final String sessionId;
	/** Absolute path of the child's isolated game directory (servers/&lt;key&gt;/). */
	public final String gameDir;
	/**
	 * Absolute path of the hub's game directory. The child reads (and
	 * writes) settings/keybinds (options.txt) and texture packs from here --
	 * one source of truth shared with the hub -- while saves/stats/
	 * screenshots stay per-server in {@link #gameDir}.
	 */
	public final String hubGameDir;

	public ChildConfig(String host, int port, String username, String sessionId, String gameDir, String hubGameDir) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.sessionId = sessionId;
		this.gameDir = gameDir;
		this.hubGameDir = hubGameDir;
	}

	@Override
	public String toString() {
		return "ChildConfig[" + host + ":" + port + ", user=" + username + ", gameDir=" + gameDir + "]";
	}
}
