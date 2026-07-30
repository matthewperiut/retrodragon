package com.periut.retrodragon.retrocenter.gui;

import java.nio.file.Path;

import com.periut.retrodragon.retrocenter.RetroCenter;
import com.periut.retrodragon.retrocenter.bridge.ChildConfig;
import com.periut.retrodragon.retrocenter.bridge.HubBridge;
import com.periut.retrodragon.retrocenter.child.InProcessChildLauncher;
import com.periut.retrodragon.retrocenter.net.ProbeFlow;
import com.periut.retrodragon.retrocenter.profile.ServerProfiles;
import com.periut.retrodragon.retrocenter.sync.ModManifest;
import com.periut.retrodragon.retrocenter.sync.ModSyncClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientNetworkHandler;

/**
 * Consent + download + handoff over a HELD PRE-LOGIN connection.
 *
 * Opened by ProbeFlow when the server's manifest doesn't match this
 * instance's mod set. The login was never sent: no world, no player entity
 * -- the server's login timeout is frozen by ServerLoginHandlerSyncMixin
 * while we stream files over the same socket. States:
 *
 *   CONSENT      mod list + size, [Download & Join] / [Cancel]
 *   DOWNLOADING  tick-driven chunk pull (this screen pumps the connection)
 *   LAUNCHING    profile ready → drop the probe connection, boot child,
 *                hand the window over
 *   HOSTING      hub parked; ticks again only once the window is back --
 *                then shows why the child ended
 *   FAILED       sync error, [Back]
 *
 * Reached purely via the vanilla-carrier probe.
 */
public final class ModSyncScreen extends Screen {

	private static final int BUTTON_ACCEPT = 0;
	private static final int BUTTON_CANCEL = 1;

	private enum State {CONSENT, DOWNLOADING, LAUNCHING, HOSTING, FAILED}

	private final Minecraft mc;
	private final ModManifest manifest;
	private final String host;
	private final int port;
	private final ClientNetworkHandler connection;

	private volatile State state = State.CONSENT;
	private ModSyncClient.DownloadSession session;
	private Path profileDir;
	private volatile String error;
	private int modListScroll;

	public ModSyncScreen(Minecraft mc, ModManifest manifest, String host, int port, ClientNetworkHandler connection) {
		this.mc = mc;
		this.manifest = manifest;
		this.host = host;
		this.port = port;
		this.connection = connection;
	}

	@Override
	public void init() {
		buttons.clear();
		if (state == State.CONSENT) {
			// same size/shape/position as the multiplayer screen's
			// Connect/Cancel buttons
			buttons.add(new ButtonWidget(BUTTON_ACCEPT, width / 2 - 100, height / 4 + 96 + 12, "Download & Join"));
			buttons.add(new ButtonWidget(BUTTON_CANCEL, width / 2 - 100, height / 4 + 120 + 12, "Cancel"));
		} else if (state == State.FAILED) {
			buttons.add(new ButtonWidget(BUTTON_CANCEL, width / 2 - 100, height / 4 + 120 + 12, "Back"));
		}
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == BUTTON_ACCEPT && state == State.CONSENT) {
			startDownload();
		} else if (button.id == BUTTON_CANCEL) {
			abort();
		}
	}

	private void startDownload() {
		try {
			profileDir = ServerProfiles.profileDir(host, port);
			session = ModSyncClient.startSession(manifest, profileDir, connection);
			state = State.DOWNLOADING;
			init(mc, width, height); // rebuild buttons
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.toString());
		}
	}

	@Override
	public void tick() {
		switch (state) {
			case CONSENT:
			case DOWNLOADING:
				// We own the held pre-login connection: pump it so probe
				// chunks (and disconnects) keep flowing.
				try {
					connection.tick();
				} catch (Throwable t) {
					t.printStackTrace();
					fail("connection lost: " + t);
					return;
				}
				if (state == State.DOWNLOADING && session != null) {
					session.tick();
					if (session.error() != null) {
						fail(session.error());
					} else if (session.isFinished()) {
						state = State.LAUNCHING;
					}
				}
				break;
			case LAUNCHING:
				launchChild();
				break;
			case HOSTING:
				// Safety net only: the wake callback normally swaps the hub
				// to its destination screen before this can ever tick again.
				if (!HubBridge.isChildRunning()) {
					mc.setScreen(retrocenterDestinationScreen());
				}
				break;
			default:
		}
	}

	private void launchChild() {
		state = State.HOSTING;
		try {
			String username = mc.session != null ? mc.session.username : "Player";
			String sessionId = mc.session != null ? mc.session.sessionId : "";

			ServerProfiles.seedInfraMods(profileDir);

			// Done with the probe connection -- the child dials its own.
			try {
				connection.disconnect();
			} catch (Throwable ignored) {
			}
			ProbeFlow.syncSessionEnded();

			ChildConfig config = new ChildConfig(host, port, username, sessionId,
					profileDir.toAbsolutePath().toString(),
					net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath().toString());
			HubBridge.setChildConfig(config);

			// At wake (context re-acquired, before anything renders) the hub
			// jumps straight to its destination: the main menu for a plain
			// disconnect, ChildEndScreen for kicks/crashes. No stale frames.
			final Minecraft mcRef = this.mc;
			HubBridge.setHubWakeCallback(() -> mcRef.setScreen(retrocenterDestinationScreen()));

			InProcessChildLauncher.launch(config);
			HubBridge.handOwnershipToChild();
			// The hub's next Display.update() parks until the child returns.
		} catch (Throwable t) {
			t.printStackTrace();
			HubBridge.setHubWakeCallback(null);
			fail(t.toString());
			HubBridge.returnOwnershipToHub(null);
		}
	}

	private void abort() {
		RetroCenter.log("sync aborted by user");
		ModSyncClient.endSession();
		ProbeFlow.syncSessionEnded();
		try {
			connection.disconnect();
		} catch (Throwable ignored) {
		}
		mc.setScreen(new TitleScreen());
	}

	private void fail(String message) {
		error = message;
		state = State.FAILED;
		ModSyncClient.endSession();
		ProbeFlow.syncSessionEnded();
		try {
			connection.disconnect();
		} catch (Throwable ignored) {
		}
		init(mc, width, height);
	}

	@Override
	public void render(int mouseX, int mouseY, float delta) {
		renderBackground();
		// title in the multiplayer screen's title position
		drawCenteredTextWithShadow(textRenderer, "RetroCenter -- " + host + ":" + port,
				width / 2, height / 4 - 60 + 20, 0xFFFFFF);

		switch (state) {
			case CONSENT: {
				drawCenteredTextWithShadow(textRenderer,
						"This server provides " + manifest.entries.size() + " mods ("
								+ formatSize(manifest.totalSize()) + ").",
						width / 2, height / 4 - 24, 0xAAAAAA);
				drawCenteredTextWithShadow(textRenderer,
						"Downloaded mods are executable code. Only continue if you trust this server.",
						width / 2, height / 4 - 12, 0xFF8888);

				// compact scrollable mod list (mouse wheel)
				int boxLeft = width / 2 - 110;
				int boxRight = width / 2 + 110;
				int boxTop = modListBoxTop();
				int boxBottom = modListBoxBottom();
				fill(boxLeft, boxTop, boxRight, boxBottom, 0xA0000000);
				fill(boxLeft + 1, boxTop + 1, boxRight - 1, boxBottom - 1, 0x60000000);

				int visible = modListVisibleLines();
				int y = boxTop + 4;
				for (int i = modListScroll; i < Math.min(manifest.entries.size(), modListScroll + visible); i++) {
					ModManifest.Entry entry = manifest.entries.get(i);
					String line = entry.modId + " " + entry.version + " (" + formatSize(entry.size) + ")";
					while (textRenderer.getWidth(line) > boxRight - boxLeft - 16 && line.length() > 4) {
						line = line.substring(0, line.length() - 4) + "...";
					}
					drawTextWithShadow(textRenderer, line, boxLeft + 5, y, 0xCCCCCC);
					y += 11;
				}
				if (manifest.entries.size() > visible) {
					int trackTop = boxTop + 2;
					int trackHeight = boxBottom - boxTop - 4;
					int thumbHeight = Math.max(6, trackHeight * visible / manifest.entries.size());
					int thumbY = trackTop + (trackHeight - thumbHeight) * modListScroll
							/ Math.max(1, manifest.entries.size() - visible);
					fill(boxRight - 5, trackTop, boxRight - 2, trackTop + trackHeight, 0x40FFFFFF);
					fill(boxRight - 5, thumbY, boxRight - 2, thumbY + thumbHeight, 0xC0FFFFFF);
				}
				break;
			}
			case DOWNLOADING: {
				long total = Math.max(1, session != null ? session.bytesTotal() : 1);
				long done = session != null ? session.bytesDone() : 0;
				drawCenteredTextWithShadow(textRenderer, "Downloading mods...", width / 2, 70, 0xFFFFFF);
				drawCenteredTextWithShadow(textRenderer,
						session != null ? session.currentFile() : "", width / 2, 86, 0xAAAAAA);
				int barWidth = 200;
				int x = width / 2 - barWidth / 2;
				int y = 104;
				int filled = (int) (barWidth * Math.min(1.0, (double) done / total));
				fill(x - 1, y - 1, x + barWidth + 1, y + 7, 0xFF000000);
				fill(x, y, x + filled, y + 6, 0xFF55FF55);
				drawCenteredTextWithShadow(textRenderer,
						formatSize(done) + " / " + formatSize(total), width / 2, 116, 0xAAAAAA);
				break;
			}
			case LAUNCHING:
			case HOSTING:
				drawCenteredTextWithShadow(textRenderer, "Starting server instance...", width / 2, 80, 0xFFFFFF);
				drawCenteredTextWithShadow(textRenderer,
						"The window will switch to the server's modded instance.", width / 2, 96, 0xAAAAAA);
				break;
			case FAILED:
				drawCenteredTextWithShadow(textRenderer, "Mod sync failed", width / 2, 70, 0xFF5555);
				drawCenteredTextWithShadow(textRenderer, String.valueOf(error), width / 2, 90, 0xAAAAAA);
				break;
			default:
		}
		super.render(mouseX, mouseY, delta);
	}

	@Override
	public void onMouseEvent() {
		super.onMouseEvent();
		if (state == State.CONSENT) {
			int wheel = org.lwjgl.input.Mouse.getEventDWheel();
			if (wheel != 0) {
				int max = Math.max(0, manifest.entries.size() - modListVisibleLines());
				modListScroll = Math.max(0, Math.min(max, modListScroll - Integer.signum(wheel) * 2));
			}
		}
	}

	private int modListBoxTop() {
		return height / 4;
	}

	private int modListBoxBottom() {
		// fill the space down to just above the Connect-position button
		return Math.max(modListBoxTop() + 26, height / 4 + 96 + 12 - 6);
	}

	private int modListVisibleLines() {
		return Math.max(2, (modListBoxBottom() - modListBoxTop() - 6) / 11);
	}

	/**
	 * Where the hub lands after a child session: title screen for a plain
	 * disconnect, the EXACT vanilla DisconnectedScreen (raw translation keys
	 * + args replayed) for kicks, and the custom crash screen only for
	 * crashes.
	 */
	private static Screen retrocenterDestinationScreen() {
		HubBridge.ChildExitInfo info = HubBridge.consumeChildExitInfo();
		if (info == null) {
			return new TitleScreen();
		}
		if (info.crash) {
			return new ChildEndScreen("Minecraft Crashed", info.message);
		}
		Object[] args = info.args != null ? info.args : new Object[0];
		return new DisconnectedScreen(info.title, info.message, args);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static String formatSize(long bytes) {
		if (bytes >= 1024 * 1024) {
			return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
		}
		return (bytes / 1024) + " KiB";
	}
}
