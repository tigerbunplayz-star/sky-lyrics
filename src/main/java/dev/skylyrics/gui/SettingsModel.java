package dev.skylyrics.gui;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import dev.skylyrics.SkyLyricsConfig;

/** What the settings menu shows: tabs of toggles, sliders and choices bound to config fields. */
public final class SettingsModel {
	public sealed interface Entry permits Toggle, Slider, Choice {
		String label();

		String help();

		/** Put this setting back to its value in {@code defaults}. */
		void reset(SkyLyricsConfig cfg, SkyLyricsConfig defaults);
	}

	public record Toggle(String label, String help, Predicate<SkyLyricsConfig> get,
			BiConsumer<SkyLyricsConfig, Boolean> set) implements Entry {
		public void reset(SkyLyricsConfig cfg, SkyLyricsConfig defaults) {
			set.accept(cfg, get.test(defaults));
		}
	}

	public record Slider(String label, String help, double min, double max, double step,
			ToDoubleFunction<SkyLyricsConfig> get, ObjDoubleConsumer<SkyLyricsConfig> set,
			java.util.function.DoubleFunction<String> format) implements Entry {
		public void reset(SkyLyricsConfig cfg, SkyLyricsConfig defaults) {
			set.accept(cfg, get.applyAsDouble(defaults));
		}

		/** Clamp and snap to the step. */
		public double snap(double v) {
			double c = Math.max(min, Math.min(max, v));
			double s = Math.round((c - min) / step) * step + min;
			return Math.max(min, Math.min(max, Math.round(s * 1000) / 1000.0));
		}

		public double fraction(SkyLyricsConfig cfg) {
			return max <= min ? 0 : (Math.max(min, Math.min(max, get.applyAsDouble(cfg))) - min) / (max - min);
		}
	}

	public record Choice(String label, String help, List<String> options,
			ToIntFunction<SkyLyricsConfig> get, BiConsumer<SkyLyricsConfig, Integer> set) implements Entry {
		public void reset(SkyLyricsConfig cfg, SkyLyricsConfig defaults) {
			int i = get.applyAsInt(defaults);
			if (i >= 0) set.accept(cfg, i);
		}

		/** Current option's label ("Custom" if the value matches none). */
		public String current(SkyLyricsConfig cfg) {
			int i = get.applyAsInt(cfg);
			return i >= 0 && i < options.size() ? options.get(i) : "Custom";
		}

		public void step(SkyLyricsConfig cfg, int dir) {
			int i = get.applyAsInt(cfg);
			int n = options.size();
			int next = i < 0 ? 0 : Math.floorMod(i + dir, n);
			set.accept(cfg, next);
		}
	}

	public record Tab(String name, String icon, String description, List<Entry> entries) {
	}

	/** Colour presets the "Colours" choice cycles through. */
	public static final List<String> PALETTE_NAMES = List.of("Candy", "Sunset", "Ocean", "Neon", "Pastel", "Snow");
	public static final List<List<String>> PALETTES = List.of(
			List.of("#FF5C8A", "#FFB443", "#FFE45C", "#5CFF9D", "#4DD8FF", "#9B7BFF"),
			List.of("#FF6B35", "#FF9F1C", "#FFD166", "#EF476F", "#C9184A", "#FF8FAB"),
			List.of("#00B4D8", "#48CAE4", "#90E0EF", "#0077B6", "#4CC9F0", "#80FFDB"),
			List.of("#39FF14", "#FF073A", "#00F0FF", "#FFEE00", "#FF00E5", "#7DF9FF"),
			List.of("#FFADAD", "#FFD6A5", "#FDFFB6", "#CAFFBF", "#9BF6FF", "#BDB2FF"),
			List.of("#FFFFFF", "#E8EEF7", "#D6E4F0", "#F5F5F5"));

	private static final List<String> FONTS = List.of("block", "lobster", "minecraft");
	private static final List<String> PLAYERS = List.of("spotify", "*");

	private SettingsModel() {
	}

	public static List<Tab> tabs() {
		return List.of(
				new Tab("Lyrics", "♪", "What shows up and where the lyrics come from", List.of(
						new Toggle("Show lyrics", "Turn the whole mod on or off (also the K key)",
								c -> c.enabled, (c, v) -> c.enabled = v),
						new Toggle("YouTube Music lyrics", "Look on YouTube Music when LRCLIB has no synced lyrics",
								c -> c.youtubeMusicLyrics, (c, v) -> c.youtubeMusicLyrics = v),
						new Toggle("Guess timing of plain lyrics", "Show untimed lyrics with estimated timing (fix with the sync key)",
								c -> c.estimateUnsyncedLyrics, (c, v) -> c.estimateUnsyncedLyrics = v),
						new Choice("Read music from", "Spotify only, or any app playing music (applies after a restart)",
								List.of("Spotify", "Any player"),
								c -> indexOf(PLAYERS, c.player == null ? "spotify" : c.player.strip().toLowerCase(Locale.ROOT)),
								(c, i) -> c.player = PLAYERS.get(i)))),
				new Tab("Look", "✦", "Font, colours and style of the words", List.of(
						new Choice("Font", "Chunky block letters, a fancy script, or the Minecraft font",
								List.of("Block", "Script", "Minecraft"),
								c -> fontIndex(c.font), (c, i) -> c.font = FONTS.get(i)),
						new Choice("Colours", "Colour set the word groups cycle through",
								PALETTE_NAMES, c -> paletteIndex(c.colors), (c, i) -> c.colors = new java.util.ArrayList<>(PALETTES.get(i))),
						new Slider("Text size", "How big the words are", 0.3, 2.5, 0.05,
								c -> c.size, (c, v) -> c.size = v, v -> String.format(Locale.ROOT, "%.2fx", v)),
						new Slider("3D depth", "Thickness of the letters (0 = flat)", 0, 3, 0.1,
								c -> c.depth, (c, v) -> c.depth = v, v -> v == 0 ? "flat" : String.format(Locale.ROOT, "%.1fx", v)),
						new Toggle("Capital letters", "Write the lyrics in CAPITALS",
								c -> c.uppercase, (c, v) -> c.uppercase = v),
						new Toggle("Colour shimmer", "Colours gently shift over time",
								c -> c.shimmer, (c, v) -> c.shimmer = v),
						new Toggle("Float and sway", "Words bob and sway a little",
								c -> c.bob, (c, v) -> c.bob = v))),
				new Tab("Placement", "◎", "Where the words appear around you", List.of(
						new Slider("Distance", "How far away the words appear", 8, 80, 1,
								SkyLyricsConfig::distanceCenter, SkyLyricsConfig::setDistanceCenter,
								v -> Math.round(v) + " blocks"),
						new Slider("Height", "Height above your eyes (raise it to put the lyrics in the sky)", -4, 40, 0.5,
								SkyLyricsConfig::heightCenter, SkyLyricsConfig::setHeightCenter,
								v -> String.format(Locale.ROOT, "%.1f", v)),
						new Slider("Side spread", "How far left and right the words scatter", 0, 70, 1,
								c -> c.spread, (c, v) -> c.spread = v, v -> Math.round(v) + "°"),
						new Slider("Words per group", "How many words pop up together", 1, 6, 1,
								c -> c.maxWordsPerGroup, (c, v) -> c.maxWordsPerGroup = (int) Math.round(v),
								v -> Integer.toString((int) Math.round(v))))),
				new Tab("Timing", "⌛", "Animation speed, fading and sync", List.of(
						new Slider("Animation speed", "Speed of the pop-in, fade-out and floating", 0.25, 3, 0.05,
								c -> c.animationSpeed, (c, v) -> c.animationSpeed = v, v -> String.format(Locale.ROOT, "%.2fx", v)),
						new Slider("Fade out after", "How long a line stays once the next one starts (lower = fades earlier)", 0, 8000, 100,
								c -> c.holdMs, (c, v) -> c.holdMs = Math.round(v), v -> String.format(Locale.ROOT, "%.1f s", v / 1000)),
						new Slider("Timing offset", "Shift all lyrics earlier (+) or later (-); also the [ and ] keys", -3000, 3000, 50,
								c -> c.offsetMs, (c, v) -> c.offsetMs = Math.round(v),
								v -> (v > 0 ? "+" : "") + Math.round(v) + " ms"))));
	}

	private static int indexOf(List<String> list, String v) {
		return Math.max(0, list.indexOf(v));
	}

	private static int fontIndex(String font) {
		String f = font == null ? "block" : font.strip().toLowerCase(Locale.ROOT);
		return switch (f) {
			case "lobster", "fancy", "script" -> 1;
			case "minecraft", "default", "vanilla" -> 2;
			default -> 0;
		};
	}

	static int paletteIndex(List<String> colors) {
		if (colors == null) return -1;
		for (int i = 0; i < PALETTES.size(); i++) {
			List<String> p = PALETTES.get(i);
			if (p.size() != colors.size()) continue;
			boolean same = true;
			for (int j = 0; j < p.size() && same; j++) same = p.get(j).equalsIgnoreCase(colors.get(j).strip());
			if (same) return i;
		}
		return -1;
	}
}
