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
import dev.skylyrics.gui.SettingsModel.Choice;
import dev.skylyrics.gui.SettingsModel.Entry;
import dev.skylyrics.gui.SettingsModel.Slider;
import dev.skylyrics.gui.SettingsModel.Tab;
import dev.skylyrics.gui.SettingsModel.Toggle;
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
	private final List<Tab> tabs = SettingsModel.tabs();
	private final Map<Entry, Float> knobAnim = new HashMap<>();

	private int tab;
	private float scroll;
	private Slider dragging;
	private boolean saved;
	private long openedNanos;

	// Layout, recomputed every frame.
	private int px, py, pw, ph;

	public SkyLyricsScreen(SkyLyricsConfig config, SkyLyricsRenderer renderer, String version) {
		super(Component.literal("Sky Lyrics settings"));
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
		g.text(font, t.name(), left, py + 10, TEXT, true);
		g.text(font, font.plainSubstrByWidth(hovered != null ? hovered.help() : t.description(), right - left),
				left, py + 22, hovered != null ? 0xFFB9B3E6 : TEXT_DIM, false);
		g.fill(left, listTop() - 3, right, listTop() - 2, PANEL_EDGE);

		int top = listTop(), bottom = listBottom();
		clampScroll();
		g.enableScissor(left - 2, top, right + 2, bottom);

		for (int i = 0; i < t.entries().size(); i++) {
			Entry e = t.entries().get(i);
			int ry = rowY(i);
			if (ry + ROW_H < top || ry > bottom) continue;
			boolean hover = inside(mx, my, left, ry, right, ry + ROW_H) && my >= top && my < bottom;
			g.fill(left, ry, right, ry + ROW_H, hover || e == dragging ? ROW_HOVER : ROW);
			g.text(font, e.label(), left + 9, ry + 8, TEXT, false);

			switch (e) {
				case Toggle tg -> drawToggle(g, tg, right, ry);
				case Slider sl -> drawSlider(g, sl, right, ry, hover);
				case Choice ch -> drawChoice(g, ch, right, ry, mx, my, hover);
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

	private int rowY(int i) {
		return listTop() + 4 + i * (ROW_H + ROW_GAP) - Math.round(scroll);
	}

	private int contentHeight() {
		return 8 + tabs.get(tab).entries().size() * (ROW_H + ROW_GAP);
	}

	private void clampScroll() {
		int max = Math.max(0, contentHeight() - (listBottom() - listTop()));
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private Entry entryAt(double mx, double my) {
		if (my < listTop() || my >= listBottom()) return null;
		List<Entry> entries = tabs.get(tab).entries();

		for (int i = 0; i < entries.size(); i++) {
			int ry = rowY(i);
			if (inside(mx, my, contentLeft(), ry, contentRight(), ry + ROW_H)) return entries.get(i);
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
			saved = true;
			config.save();
			renderer.showNotice("Settings saved");
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

		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
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
			saved = true;
			config.save();
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
