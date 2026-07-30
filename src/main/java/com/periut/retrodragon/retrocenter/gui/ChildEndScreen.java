package com.periut.retrodragon.retrocenter.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Shown by the HUB after a child instance ends with something to say:
 * a red title ("Minecraft Crashed" / "Disconnected") above a scrollable
 * text box with the full details (crash report, kick reason), and a
 * "Back to Main Menu" button. Scroll with the mouse wheel.
 */
public final class ChildEndScreen extends Screen {

	private static final int LINE_HEIGHT = 10;

	private final String title;
	private final String details;
	private List<String> lines;
	private int scroll;

	public ChildEndScreen(String title, String details) {
		this.title = title;
		this.details = details != null ? details : "";
	}

	@Override
	public void init() {
		buttons.clear();
		buttons.add(new ButtonWidget(0, width / 2 - 100, height - 32, "Back to Main Menu"));
		lines = wrap(details, width - 60);
		scroll = 0;
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			minecraft.setScreen(new TitleScreen());
		}
	}

	@Override
	public void onMouseEvent() {
		super.onMouseEvent();
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if (wheel != 0) {
			scroll(-Integer.signum(wheel) * 3);
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == org.lwjgl.input.Keyboard.KEY_DOWN) {
			scroll(3);
		} else if (key == org.lwjgl.input.Keyboard.KEY_UP) {
			scroll(-3);
		} else {
			super.keyPressed(chr, key);
		}
	}

	private void scroll(int deltaLines) {
		int visible = visibleLines();
		int max = Math.max(0, lines.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll + deltaLines));
	}

	private int boxTop() {
		return 38;
	}

	private int boxBottom() {
		return height - 44;
	}

	private int visibleLines() {
		return Math.max(1, (boxBottom() - boxTop() - 8) / LINE_HEIGHT);
	}

	@Override
	public void render(int mouseX, int mouseY, float delta) {
		renderBackground();
		drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFF5555);

		int top = boxTop();
		int bottom = boxBottom();
		fill(28, top, width - 28, bottom, 0xA0000000);
		fill(29, top + 1, width - 29, bottom - 1, 0x60000000);

		int visible = visibleLines();
		int y = top + 5;
		for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
			drawTextWithShadow(textRenderer, lines.get(i), 34, y, 0xCCCCCC);
			y += LINE_HEIGHT;
		}

		// minimal scrollbar
		if (lines.size() > visible) {
			int trackTop = top + 2;
			int trackHeight = bottom - top - 4;
			int thumbHeight = Math.max(8, trackHeight * visible / lines.size());
			int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / Math.max(1, lines.size() - visible);
			fill(width - 33, trackTop, width - 30, trackTop + trackHeight, 0x40FFFFFF);
			fill(width - 33, thumbY, width - 30, thumbY + thumbHeight, 0xC0FFFFFF);
		}

		super.render(mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> result = new ArrayList<>();
		for (String raw : text.replace("\r", "").split("\n", -1)) {
			// preserve indentation from stack traces
			if (raw.isEmpty()) {
				result.add("");
				continue;
			}
			String line = raw.replace("\t", "    ");
			while (textRenderer.getWidth(line) > maxWidth) {
				int cut = line.length();
				while (cut > 1 && textRenderer.getWidth(line.substring(0, cut)) > maxWidth) {
					cut--;
				}
				// prefer breaking at a space/dot near the cut
				int nice = Math.max(line.lastIndexOf(' ', cut), line.lastIndexOf('.', cut));
				if (nice > cut / 2) {
					cut = nice + 1;
				}
				result.add(line.substring(0, cut));
				line = "    " + line.substring(cut);
			}
			result.add(line);
		}
		return result;
	}
}
