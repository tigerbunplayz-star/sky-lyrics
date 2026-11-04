package dev.skylyrics.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import dev.skylyrics.SkyLyricsConfig;
import dev.skylyrics.gui.SettingsModel.Action;
import dev.skylyrics.gui.SettingsModel.Choice;
import dev.skylyrics.gui.SettingsModel.Info;
import dev.skylyrics.gui.SettingsModel.Note;
import dev.skylyrics.gui.SettingsModel.Picker;
import dev.skylyrics.gui.SettingsModel.Preview;
import dev.skylyrics.gui.SettingsModel.Swatch;
import dev.skylyrics.gui.SettingsModel.Entry;
import dev.skylyrics.gui.SettingsModel.Slider;
import dev.skylyrics.gui.SettingsModel.Tab;
import dev.skylyrics.gui.SettingsModel.Toggle;
import dev.skylyrics.render.ColorScheme;
import dev.skylyrics.render.Colors;
import dev.skylyrics.render.SkyLyricsRenderer;

/**
 * Sky Lyrics settings: a dusk-coloured panel with tabs on the left, toggles / sliders /
 * choices on the right, and Reset tab / Cancel / Save at the bottom. Changes apply live
 * (sample words show in the world if nothing is playing); Cancel or Esc undoes them.
 */
public final class SkyLyricsScreen extends Screen {
	// Theme: night-sky indigo with the mod's pink → violet → cyan accents.
	private static final int DIM = 0x60060414;
	private static final int PANEL = 0xF2141226;
	private static final int PANEL_EDGE = 0xFF2E2954;
	private static final int SIDEBAR = 0xF20E0C1D;
	private static final int ROW = 0xFF1C1936;
	private static final int ROW_HOVER = 0xFF25214A;
	private static final int TEXT = 0xFFEFEBFF;
	private static final int TEXT_DIM = 0xFF8F89BA;
	private static final int TEXT_FAINT = 0xFF5E5888;
	private static final int TRACK = 0xFF35305C;
	private static final int PINK = 0xFF5C8A;
	private static final int VIOLET = 0x9B7BFF;
	private static final int CYAN = 0x4DD8FF;

	private static final int SIDEBAR_W = 112;
	private static final int ROW_H = 24;
	private static final int ROW_GAP = 4;
	private static final int HEADER_H = 34;
	private static final int FOOTER_H = 30;

	private final SkyLyricsConfig config;
	private final SkyLyricsConfig original;
	private final SkyLyricsRenderer renderer;
	private final String version;
	private final List<Tab> tabs;
	private final Map<Entry, Float> knobAnim = new HashMap<>();

	private int tab;
	private float scroll;
	private Slider dragging;
	/** Colour picker state: which swatch is edited, what is being dragged, last drawn geometry. */
	private int pickerTarget;
	private int pickerDrag; // 0 none, 1 square, 2 hue bar
	private Picker pickerEntry;
	private float[] pickerHsv = {0.55f, 0.7f, 1f};
	private String pickerHex = "";
	private int sqX, sqY, sqW, sqH, hueX, hueW;
	private boolean saved;
	private long openedNanos;

	// Layout, recomputed every frame.
	private int px, py, pw, ph;

	private final Runnable onApply;
	private final Runnable onRestart;

	/**
	 * @param onApply   run after Save (applies settings outside the renderer, e.g. which app to read)
	 * @param onRestart run by the Restart button (restarts the Spotify reader and re-downloads lyrics)
	 */
	public SkyLyricsScreen(SkyLyricsConfig config, SkyLyricsRenderer renderer, String version,
			Runnable onApply, Runnable onRestart, SettingsModel.MenuActions actions) {
		super(Component.literal("Sky Lyrics settings"));
		this.tabs = SettingsModel.tabs(actions);
		this.onApply = onApply;
		this.onRestart = onRestart;
		this.config = config;
		this.original = config.copy();
		this.renderer = renderer;
		this.version = version;
	}

	@Override
	protected void init() {
		openedNanos = System.nanoTime();
	}

	@Override
	public boolean isPauseScreen() {
		return false; // keep the world (and the lyrics) moving behind the menu
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, DIM); // light dim, no blur, so the live preview stays visible
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		renderer.keepPreview();
		layout();

		float open = Math.min(1f, (System.nanoTime() - openedNanos) / 180_000_000f);
		int slide = Math.round((1f - easeOut(open)) * 8);

		g.pose().pushMatrix();
		g.pose().translate(0, slide);

		// Panel with a soft glow and a gradient accent strip along the top.
		g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0x30FF5C8A);
		g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PANEL_EDGE);
		g.fill(px, py, px + pw, py + ph, PANEL);
		hGradient(g, px, py, px + pw, py + 2, PINK, CYAN);

		drawSidebar(g, mouseX, mouseY - slide);
		drawContent(g, mouseX, mouseY - slide);
		drawFooter(g, mouseX, mouseY - slide);

		g.pose().popMatrix();
	}

	private void layout() {
		pw = Math.min(width - 24, 460);
		ph = Math.min(height - 24, 270);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
	}

	// --- sidebar --------------------------------------------------------------------------

	private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
		int x = px, y = py + 2;
		g.fill(x, y, x + SIDEBAR_W, py + ph, SIDEBAR);
		g.fill(x + SIDEBAR_W, y, x + SIDEBAR_W + 1, py + ph, PANEL_EDGE);

		// Title in the mod's block font, one colour per letter.
		g.text(font, rainbowTitle(), x + 12, y + 12, 0xFFFFFFFF, true);
		g.text(font, "v" + version, x + 12, y + 25, TEXT_FAINT, false);

		int ty = y + 46;

		for (int i = 0; i < tabs.size(); i++) {
			Tab t = tabs.get(i);
			boolean active = i == tab;
			boolean hover = inside(mx, my, x + 6, ty, x + SIDEBAR_W - 6, ty + 20);

			if (active) {
				g.fill(x + 6, ty, x + SIDEBAR_W - 6, ty + 20, 0xFF231F42);
				vGradient(g, x + 6, ty, x + 8, ty + 20, PINK, VIOLET);
			} else if (hover) {
				g.fill(x + 6, ty, x + SIDEBAR_W - 6, ty + 20, 0x22FFFFFF);
			}

			int c = active ? TEXT : (hover ? 0xFFCFCAF2 : TEXT_DIM);
			g.text(font, t.icon(), x + 14, ty + 6, active ? (0xFF000000 | PINK) : c, false);
			g.text(font, t.name(), x + 28, ty + 6, c, false);
			ty += 24;
		}

		g.text(font, "♫ live preview", x + 12, py + ph - 16, TEXT_FAINT, false);
	}

	private Component rainbowTitle() {
		String title = "SKY LYRICS";
		int[] colors = {0xFF5C8A, 0xFFB443, 0xFFE45C, 0x5CFF9D, 0x4DD8FF, 0x9B7BFF};
		MutableComponent c = Component.empty();
		int k = 0;

		for (int i = 0; i < title.length(); i++) {
			char ch = title.charAt(i);
			MutableComponent part = Component.literal(String.valueOf(ch)).withColor(colors[k % colors.length]);
			part = part.withStyle(s -> s.withFont(SkyLyricsRenderer.BLOCK_FONT));
			c.append(part);
			if (ch != ' ') k++;
		}

		return c;
	}

	// --- content --------------------------------------------------------------------------

	private int contentLeft() {
		return px + SIDEBAR_W + 12;
	}

	private int contentRight() {
		return px + pw - 12;
	}

	private int listTop() {
		return py + 2 + HEADER_H;
	}

	private int listBottom() {
		return py + ph - FOOTER_H;
	}

	private void drawContent(GuiGraphicsExtractor g, int mx, int my) {
		Tab t = tabs.get(tab);
		int left = contentLeft(), right = contentRight();

		Entry hovered = entryAt(mx, my);
		String hint = hovered != null ? hovered.help() : t.description();
		boolean hinting = hovered != null;

		if (inside(mx, my, restartBtn())) {
			hint = "Save, restart the Spotify reader and download the lyrics again";
			hinting = true;
		}

		g.text(font, t.name(), left, py + 10, TEXT, true);
		g.text(font, font.plainSubstrByWidth(hint, right - left), left, py + 22, hinting ? 0xFFB9B3E6 : TEXT_DIM, false);
		g.fill(left, listTop() - 3, right, listTop() - 2, PANEL_EDGE);

		int top = listTop(), bottom = listBottom();
		clampScroll();
		g.enableScissor(left - 2, top, right + 2, bottom);

		List<Entry> rows = t.visible(config);

		for (int i = 0; i < rows.size(); i++) {
			Entry e = rows.get(i);
			int ry = rowY(rows, i);
			int rh = e.height();
			if (ry + rh < top || ry > bottom) continue;
			boolean hover = inside(mx, my, left, ry, right, ry + rh) && my >= top && my < bottom;
			boolean busy = e == dragging || (e == pickerEntry && pickerDrag != 0);
			g.fill(left, ry, right, ry + rh, (hover && !(e instanceof Picker)) || busy ? ROW_HOVER : ROW);
			if (!(e instanceof Note)) g.text(font, e.label(), left + 9, ry + 8, TEXT, false);

			switch (e) {
				case Toggle tg -> drawToggle(g, tg, right, ry);
				case Slider sl -> drawSlider(g, sl, right, ry, hover);
				case Choice ch -> drawChoice(g, ch, right, ry, mx, my, hover);
				case Picker pk -> drawPicker(g, pk, left, right, ry, mx, my);
				case Preview pv -> drawPreview(g, left, right, ry);
				case Info in -> {
					String v = font.plainSubstrByWidth(in.value().get(), Math.max(20, right - left - font.width(in.label()) - 30));
					g.text(font, v, right - 9 - font.width(v), ry + 8, 0xFFDAD5FF, false);
				}
				case Action ac -> drawAction(g, ac, right, ry, mx, my);
				case Note nt -> {
					int tw = font.width(nt.tag()) + 8;
					g.fill(left + 9, ry + 6, left + 9 + tw, ry + 17, 0xFFFFC94D);
					g.text(font, nt.tag(), left + 13, ry + 8, 0xFF2A1F00, false);
					g.textWithWordWrap(font, Component.literal(nt.label()), left + 15 + tw, ry + 7, right - left - tw - 26, 0xFFCFCAF2);
				}
			}
		}

		g.disableScissor();

		// Scrollbar when the list is taller than the area.
		int content = contentHeight();
		int area = bottom - top;

		if (content > area) {
			int barH = Math.max(16, area * area / content);
			int barY = top + Math.round((area - barH) * (scroll / (content - area)));
			g.fill(right + 4, top, right + 6, bottom, 0x30FFFFFF);
			vGradient(g, right + 4, barY, right + 6, barY + barH, PINK, CYAN);
		}
	}

	private void drawToggle(GuiGraphicsExtractor g, Toggle t, int right, int ry) {
		boolean on = t.get().test(config);
		float a = knobAnim.getOrDefault(t, on ? 1f : 0f);
		a += ((on ? 1f : 0f) - a) * 0.35f;
		knobAnim.put(t, a);

		int w = 24, h = 12;
		int x2 = right - 9, x1 = x2 - w, y1 = ry + 6;
		g.fill(x1, y1, x2, y1 + h, TRACK);
		if (a > 0.02f) {
			int fillTo = x1 + Math.round(w * a);
			hGradient(g, x1, y1, fillTo, y1 + h, PINK, VIOLET);
		}
		int kx = x1 + 2 + Math.round((w - 12) * a);
		g.fill(kx, y1 + 2, kx + 8, y1 + h - 2, on ? 0xFFFFFFFF : 0xFFBDB8E0);
	}

	private int trackRight(int right) {
		return right - 52;
	}

	private int trackLeft(int right) {
		return trackRight(right) - Math.min(120, Math.max(60, (right - contentLeft()) - 170));
	}

	private void drawSlider(GuiGraphicsExtractor g, Slider s, int right, int ry, boolean hover) {
		int x1 = trackLeft(right), x2 = trackRight(right);
		int y = ry + 11;
		double f = s.fraction(config);
		int fx = x1 + (int) Math.round((x2 - x1) * f);

		g.fill(x1, y, x2, y + 3, TRACK);
		if (fx > x1) hGradient(g, x1, y, fx, y + 3, PINK, CYAN);
		boolean active = hover || s == dragging;
		g.fill(fx - 3, ry + 6, fx + 3, ry + 18, active ? 0xFFFFFFFF : 0xFFE2DEFA);
		g.fill(fx - 2, ry + 7, fx + 2, ry + 17, 0xFF000000 | Colors.mix(PINK, CYAN, (float) f));

		String value = s.format().apply(s.get().applyAsDouble(config));
		g.text(font, value, right - 9 - font.width(value), ry + 8, 0xFFDAD5FF, false);
	}

	private void drawChoice(GuiGraphicsExtractor g, Choice c, int right, int ry, int mx, int my, boolean hover) {
		int w = 96, x2 = right - 9, x1 = x2 - w;
		g.fill(x1, ry + 4, x2, ry + ROW_H - 4, 0xFF2A2650);
		if (hover) g.fill(x1, ry + 4, x2, ry + 5, 0xFF000000 | VIOLET);
		String v = c.current(config);
		g.text(font, v, x1 + (w - font.width(v)) / 2, ry + 8, TEXT, false);
		boolean leftHalf = mx < x1 + w / 2;
		g.text(font, "‹", x1 + 5, ry + 8, hover && leftHalf ? (0xFF000000 | PINK) : TEXT_DIM, false);
		g.text(font, "›", x2 - 9, ry + 8, hover && !leftHalf ? (0xFF000000 | CYAN) : TEXT_DIM, false);
	}

	private Swatch target(Picker p) {
		if (pickerTarget >= p.swatches().size() || !p.swatches().get(pickerTarget).enabled().test(config)) pickerTarget = 0;
		return p.swatches().get(pickerTarget);
	}

	/** Keep an HSV copy of the edited colour so hue survives greys and blacks. */
	private void syncPickerHsv(Swatch sw) {
		String hex = sw.get().apply(config);
		if (hex != null && hex.equalsIgnoreCase(pickerHex)) return;
		float[] hsv = Colors.toHsv(ColorScheme.parse(hex, 0x4DD8FF));
		if (hsv[1] < 0.01f || hsv[2] < 0.01f) hsv[0] = pickerHsv[0];
		pickerHsv = hsv;
		pickerHex = hex == null ? "" : hex;
	}

	private void setPickerColor(Swatch sw, int rgb) {
		String hex = ColorScheme.hex(rgb);
		sw.set().accept(config, hex);
		pickerHex = "";
		syncPickerHsv(sw);
	}

	private void setPickerHsv(Swatch sw) {
		String hex = ColorScheme.hex(Colors.fromHsv(pickerHsv[0], pickerHsv[1], pickerHsv[2]));
		sw.set().accept(config, hex);
		pickerHex = hex;
	}

	private void drawPicker(GuiGraphicsExtractor g, Picker p, int left, int right, int ry, int mx, int my) {
		pickerEntry = p;
		Swatch sw = target(p);
		syncPickerHsv(sw);

		sqX = left + 9;
		sqY = ry + 21;
		sqW = Math.max(60, Math.min(112, (right - left) / 2 - 24));
		sqH = 70;
		hueX = sqX + sqW + 6;
		hueW = 10;

		// Saturation (left→right) × brightness (top→bottom) for the current hue.
		for (int x = 0; x < sqW; x += 2) {
			float sat = x / (float) Math.max(1, sqW - 1);
			int top = Colors.fromHsv(pickerHsv[0], sat, 1f);
			g.fillGradient(sqX + x, sqY, Math.min(sqX + sqW, sqX + x + 2), sqY + sqH, 0xFF000000 | top, 0xFF000000);
		}

		int kx = sqX + Math.round(pickerHsv[1] * (sqW - 1));
		int ky = sqY + Math.round((1f - pickerHsv[2]) * (sqH - 1));
		g.fill(kx - 3, ky - 3, kx + 4, ky + 4, 0xFFFFFFFF);
		g.fill(kx - 2, ky - 2, kx + 3, ky + 3, 0xFF000000 | Colors.fromHsv(pickerHsv[0], pickerHsv[1], pickerHsv[2]));

		// Hue bar.
		for (int y = 0; y < sqH; y += 2) {
			g.fill(hueX, sqY + y, hueX + hueW, Math.min(sqY + sqH, sqY + y + 2), 0xFF000000 | Colors.fromHsv(y / (float) sqH, 1f, 1f));
		}

		int hy = sqY + Math.round(pickerHsv[0] * (sqH - 1));
		g.fill(hueX - 2, hy - 1, hueX + hueW + 2, hy + 2, 0xFFFFFFFF);

		// Swatches: click one to edit it.
		int infoX = hueX + hueW + 14;

		for (int k = 0; k < p.swatches().size(); k++) {
			Swatch s = p.swatches().get(k);
			boolean on = s.enabled().test(config);
			int y = sqY + k * 22;
			int rgb = ColorScheme.parse(s.get().apply(config), 0xFFFFFF);
			boolean selected = k == pickerTarget && on;

			g.fill(infoX - 1, y - 1, infoX + 19, y + 17, selected ? 0xFFFFFFFF : 0xFF3A3566);
			g.fill(infoX, y, infoX + 18, y + 16, on ? (0xFF000000 | rgb) : 0xFF2A2650);
			g.text(font, s.name(), infoX + 24, y + 4, on ? (selected ? TEXT : 0xFFCFCAF2) : TEXT_FAINT, false);
			String hex = on ? ColorScheme.hex(rgb) : "off";
			g.text(font, hex, right - 9 - font.width(hex), y + 4, on ? 0xFFB9B3E6 : TEXT_FAINT, false);
		}

		// Quick colours.
		int dx = infoX, dy = sqY + sqH - 10;
		for (int c : SettingsModel.QUICK_COLORS) {
			if (dx + 9 > right - 9) break;
			boolean hover = inside(mx, my, dx, dy, dx + 9, dy + 9);
			g.fill(dx - 1, dy - 1, dx + 10, dy + 10, hover ? 0xFFFFFFFF : 0xFF3A3566);
			g.fill(dx, dy, dx + 9, dy + 9, 0xFF000000 | c);
			dx += 13;
		}
	}

	private boolean pickerClick(Picker p, double mx, double my) {
		Swatch sw = target(p);

		if (inside(mx, my, sqX, sqY, sqX + sqW, sqY + sqH)) {
			pickerDrag = 1;
			pickerDragTo(mx, my);
			return true;
		}

		if (inside(mx, my, hueX - 2, sqY, hueX + hueW + 2, sqY + sqH)) {
			pickerDrag = 2;
			pickerDragTo(mx, my);
			return true;
		}

		int infoX = hueX + hueW + 14;

		for (int k = 0; k < p.swatches().size(); k++) {
			int y = sqY + k * 22;
			if (inside(mx, my, infoX - 1, y - 1, contentRight() - 9, y + 17) && p.swatches().get(k).enabled().test(config)) {
				pickerTarget = k;
				pickerHex = "";
				return true;
			}
		}

		int dx = infoX, dy = sqY + sqH - 10;
		for (int c : SettingsModel.QUICK_COLORS) {
			if (dx + 9 > contentRight() - 9) break;
			if (inside(mx, my, dx - 1, dy - 1, dx + 10, dy + 10)) {
				setPickerColor(sw, c);
				return true;
			}
			dx += 13;
		}

		return false;
	}

	private void pickerDragTo(double mx, double my) {
		if (pickerEntry == null) return;
		Swatch sw = target(pickerEntry);

		if (pickerDrag == 1) {
			pickerHsv[1] = (float) Math.max(0, Math.min(1, (mx - sqX) / Math.max(1, sqW - 1)));
			pickerHsv[2] = (float) Math.max(0, Math.min(1, 1 - (my - sqY) / Math.max(1, sqH - 1)));
		} else if (pickerDrag == 2) {
			pickerHsv[0] = (float) Math.max(0, Math.min(0.999, (my - sqY) / Math.max(1, sqH - 1)));
		}

		setPickerHsv(sw);
	}

	/** A sample of three word groups in the current font and colours, with a little 3D edge. */
	private void drawPreview(GuiGraphicsExtractor g, int left, int right, int ry) {
		ColorScheme cs = config.colorScheme();
		double timeS = (System.nanoTime() % 3_600_000_000_000L) / 1e9;
		String[] words = config.uppercase ? new String[] {"SKY", "LYRICS", "LIVE"} : new String[] {"Sky", "Lyrics", "live"};
		var fontDesc = SkyLyricsRenderer.fontFor(config);

		MutableComponent front = Component.empty();
		MutableComponent back = Component.empty();

		for (int w = 0; w < words.length; w++) {
			String word = words[w];
			int n = word.length();

			for (int i = 0; i < n; i++) {
				String ch = String.valueOf(word.charAt(i));
				MutableComponent f = Component.literal(ch).withColor(cs.letter(w, i, n, timeS, w * 1.3f));
				MutableComponent b = Component.literal(ch).withColor(Colors.scale(cs.base(w), 0.4f));
				if (fontDesc != null) {
					f = f.withStyle(st -> st.withFont(fontDesc));
					b = b.withStyle(st -> st.withFont(fontDesc));
				}
				front.append(f);
				back.append(b);
			}

			if (w < words.length - 1) {
				front.append(" ");
				back.append(" ");
			}
		}

		float scale = 1.4f;
		int textW = Math.round(font.width(front) * scale);
		int x = Math.max(left + 70, right - 12 - textW);
		int y = ry + 9;

		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale);
		g.text(font, back, 1, 1, 0xFFFFFFFF, false);
		g.text(font, front, 0, 0, 0xFFFFFFFF, false);
		g.pose().popMatrix();
	}

	private int[] actionBtn(Action a, int right, int ry) {
		int w = Math.max(64, font.width(a.button().get()) + 18);
		return new int[] {right - 9 - w, ry + 4, right - 9, ry + ROW_H - 4};
	}

	private void drawAction(GuiGraphicsExtractor g, Action a, int right, int ry, int mx, int my) {
		int[] b = actionBtn(a, right, ry);
		boolean on = a.enabled().getAsBoolean();
		boolean hover = on && inside(mx, my, b);
		if (on) hGradient(g, b[0], b[1], b[2], b[3], hover ? Colors.mix(PINK, 0xFFFFFF, 0.15f) : PINK, hover ? Colors.mix(VIOLET, 0xFFFFFF, 0.15f) : VIOLET);
		else g.fill(b[0], b[1], b[2], b[3], 0xFF26224A);
		String t = a.button().get();
		g.text(font, t, b[0] + (b[2] - b[0] - font.width(t)) / 2, b[1] + 4, on ? 0xFFFFFFFF : TEXT_FAINT, on);
	}

	private int rowY(List<Entry> rows, int i) {
		int y = listTop() + 4 - Math.round(scroll);
		for (int k = 0; k < i; k++) y += rows.get(k).height() + ROW_GAP;
		return y;
	}

	private int contentHeight() {
		int h = 8;
		for (Entry e : tabs.get(tab).visible(config)) h += e.height() + ROW_GAP;
		return h;
	}

	private void clampScroll() {
		int max = Math.max(0, contentHeight() - (listBottom() - listTop()));
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private Entry entryAt(double mx, double my) {
		if (my < listTop() || my >= listBottom()) return null;
		List<Entry> entries = tabs.get(tab).visible(config);

		for (int i = 0; i < entries.size(); i++) {
			int ry = rowY(entries, i);
			if (inside(mx, my, contentLeft(), ry, contentRight(), ry + entries.get(i).height())) return entries.get(i);
		}

		return null;
	}

	// --- footer ---------------------------------------------------------------------------

	private int[] resetBtn() {
		int y = py + ph - FOOTER_H + 6;
		return new int[] {contentLeft(), y, contentLeft() + font.width("Reset tab") + 12, y + 18};
	}

	private int[] cancelBtn() {
		int y = py + ph - FOOTER_H + 6;
		int x2 = contentRight() - 72;
		return new int[] {x2 - 58, y, x2, y + 18};
	}

	private int[] saveBtn() {
		int y = py + ph - FOOTER_H + 6;
		return new int[] {contentRight() - 66, y, contentRight(), y + 18};
	}

	private int[] restartBtn() {
		int[] c = cancelBtn();
		int w = font.width("\u27F3 Restart") + 14;
		return new int[] {c[0] - 6 - w, c[1], c[0] - 6, c[3]};
	}

	/** Settings whose effect needs the reader restarted (applied by Save or Restart). */
	private boolean restartPending() {
		String now = config.player == null ? "" : config.player.strip();
		String was = original.player == null ? "" : original.player.strip();
		return !now.equalsIgnoreCase(was);
	}

	private void save() {
		saved = true;
		config.save();
		onApply.run();
	}

	private void drawFooter(GuiGraphicsExtractor g, int mx, int my) {
		int left = contentLeft(), right = contentRight();
		g.fill(px + SIDEBAR_W + 1, py + ph - FOOTER_H, px + pw, py + ph - FOOTER_H + 1, PANEL_EDGE);

		int[] r = resetBtn();
		boolean rh = inside(mx, my, r);
		g.text(font, "Reset tab", r[0] + 6, r[1] + 5, rh ? TEXT : TEXT_FAINT, false);

		int[] c = cancelBtn();
		boolean ch = inside(mx, my, c);
		g.fill(c[0], c[1], c[2], c[3], ch ? 0xFF332E5E : 0xFF26224A);
		g.text(font, "Cancel", c[0] + (c[2] - c[0] - font.width("Cancel")) / 2, c[1] + 5, ch ? TEXT : 0xFFCFCAF2, false);

		int[] rs = restartBtn();
		boolean rsh = inside(mx, my, rs);
		boolean pending = restartPending();
		g.fill(rs[0], rs[1], rs[2], rs[3], rsh ? 0xFF332E5E : 0xFF26224A);
		if (pending) {
			float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 2.5e8);
			hGradient(g, rs[0], rs[3] - 2, rs[2], rs[3], PINK, CYAN);
			g.fill(rs[0], rs[1], rs[2], rs[1] + 1, 0xFF000000 | Colors.mix(VIOLET, 0xFFFFFF, pulse * 0.5f));
		}
		String rl = "\u27F3 Restart";
		g.text(font, rl, rs[0] + (rs[2] - rs[0] - font.width(rl)) / 2, rs[1] + 5, rsh || pending ? TEXT : 0xFFCFCAF2, false);

		int[] s = saveBtn();
		boolean sh = inside(mx, my, s);
		hGradient(g, s[0], s[1], s[2], s[3], sh ? Colors.mix(PINK, 0xFFFFFF, 0.15f) : PINK, sh ? Colors.mix(VIOLET, 0xFFFFFF, 0.15f) : VIOLET);
		g.text(font, "Save", s[0] + (s[2] - s[0] - font.width("Save")) / 2, s[1] + 5, 0xFFFFFFFF, true);
		if (changed()) g.fill(s[2] - 5, s[1] + 2, s[2] - 2, s[1] + 5, 0xFFFFFFFF);

		if (right - left < 0) return; // tiny window; nothing else fits
	}

	private boolean changed() {
		return !new com.google.gson.Gson().toJson(config).equals(new com.google.gson.Gson().toJson(original));
	}

	// --- input ----------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int button = event.button();

		// Tabs
		int ty = py + 2 + 46;
		for (int i = 0; i < tabs.size(); i++) {
			if (inside(mx, my, px + 6, ty, px + SIDEBAR_W - 6, ty + 20)) {
				tab = i;
				scroll = 0;
				pickerEntry = null;
				return true;
			}
			ty += 24;
		}

		if (inside(mx, my, resetBtn())) {
			SkyLyricsConfig defaults = new SkyLyricsConfig();
			for (Entry e : tabs.get(tab).entries()) e.reset(config, defaults);
			return true;
		}

		if (inside(mx, my, cancelBtn())) {
			onClose();
			return true;
		}

		if (inside(mx, my, saveBtn())) {
			save();
			renderer.showNotice(restartPending() ? "Settings saved \u2013 reader restarted" : "Settings saved");
			onClose();
			return true;
		}

		if (inside(mx, my, restartBtn())) {
			save();
			onRestart.run();
			renderer.showNotice("Restarting Sky Lyrics\u2026");
			onClose();
			return true;
		}

		Entry e = entryAt(mx, my);

		if (e != null) {
			int right = contentRight();

			switch (e) {
				case Toggle t -> t.set().accept(config, !t.get().test(config));
				case Choice c -> {
					int x1 = right - 9 - 96;
					int dir = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || (mx >= x1 && mx < x1 + 48) ? -1 : 1;
					c.step(config, dir);
				}
				case Slider s -> {
					dragging = s;
					setSlider(s, mx);
				}
				case Picker pk -> pickerClick(pk, mx, my);
				case Preview pv -> {
				}
				case Info in -> {
				}
				case Note nt -> {
				}
				case Action ac -> {
					int ry = rowY(tabs.get(tab).visible(config), tabs.get(tab).visible(config).indexOf(ac));
					if (ac.enabled().getAsBoolean() && inside(mx, my, actionBtn(ac, right, ry))) {
						if (ac.closesMenu()) save();
						ac.run().run();
						if (ac.closesMenu()) onClose();
					}
				}
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			setSlider(dragging, event.x());
			return true;
		}

		if (pickerDrag != 0) {
			pickerDragTo(event.x(), event.y());
			return true;
		}

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null || pickerDrag != 0) {
			dragging = null;
			pickerDrag = 0;
			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		Entry e = entryAt(mx, my);

		// Scrolling over a slider nudges it one step; elsewhere it scrolls the list.
		if (e instanceof Slider s && !hasScrollbar()) {
			s.set().accept(config, s.snap(s.get().applyAsDouble(config) + Math.signum(vertical) * s.step()));
			return true;
		}

		scroll -= (float) (vertical * 16);
		clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			save();
			renderer.showNotice("Settings saved");
			onClose();
			return true;
		}

		return super.keyPressed(event); // Esc closes (and cancels)
	}

	@Override
	public void onClose() {
		if (!saved) config.copyFrom(original); // Cancel / Esc: undo live changes
		saved = true;
		super.onClose();
	}

	private boolean hasScrollbar() {
		return contentHeight() > listBottom() - listTop();
	}

	private void setSlider(Slider s, double mx) {
		int right = contentRight();
		int x1 = trackLeft(right), x2 = trackRight(right);
		double f = (mx - x1) / Math.max(1, x2 - x1);
		s.set().accept(config, s.snap(s.min() + f * (s.max() - s.min())));
	}

	// --- drawing helpers --------------------------------------------------------------------

	private static void hGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int from, int to) {
		int w = x2 - x1;
		if (w <= 0) return;
		int step = Math.max(1, w / 32);

		for (int x = x1; x < x2; x += step) {
			float t = (x - x1) / (float) Math.max(1, w - 1);
			g.fill(x, y1, Math.min(x2, x + step), y2, 0xFF000000 | Colors.mix(from, to, t));
		}
	}

	private static void vGradient(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int from, int to) {
		g.fillGradient(x1, y1, x2, y2, 0xFF000000 | from, 0xFF000000 | to);
	}

	private static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
		return mx >= x1 && mx < x2 && my >= y1 && my < y2;
	}

	private static boolean inside(double mx, double my, int[] r) {
		return inside(mx, my, r[0], r[1], r[2], r[3]);
	}

	private static float easeOut(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}
}
