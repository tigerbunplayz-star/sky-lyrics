package dev.skylyrics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.render.LyricScene;

/** Settings, stored in .minecraft/config/skylyrics.json. Edit the file and restart to change them. */
public final class SkyLyricsConfig {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Show lyrics at all (toggle in game with K). */
	public boolean enabled = true;
	/** "block" (chunky 3D letters), "lobster" (fancy script) or "minecraft" (the game's font). */
	public String font = "block";
	/** Write the lyrics in CAPITALS. */
	public boolean uppercase = true;
	/** Text size multiplier. */
	public double size = 1.0;
	/** 3D thickness of the letters (0 = flat). */
	public double depth = 1.0;
	/** Colours the word groups cycle through. */
	public List<String> colors = new ArrayList<>(List.of("#FF5C8A", "#FFB443", "#FFE45C", "#5CFF9D", "#4DD8FF", "#9B7BFF"));
	/** Gently shift the colours over time. */
	public boolean shimmer = true;
	/** "original" (the colourful palette above) or "custom" (your own colour below). */
	public String colorMode = "original";
	/** Custom mode: main colour. */
	public String customColor = "#4DD8FF";
	/** Custom mode: blend each word group's letters into a second colour. */
	public boolean customFade = true;
	/** Custom mode: the colour the letters fade into. */
	public String customFadeColor = "#9B7BFF";
	/** Let the words bob and sway a little. */
	public boolean bob = true;
	/** How far left/right of where you look the words can appear (degrees). */
	public double spread = 36;
	/** How far away the words appear (blocks). */
	public double distanceMin = 24;
	public double distanceMax = 36;
	/** Height above your eyes (blocks). Raise both to put the lyrics up in the sky. */
	public double heightMin = -1.0;
	public double heightMax = 8.0;
	/** How long a line stays after the next one starts (ms) before it fades. */
	public long holdMs = 2200;
	/** Speed of the pop-in / fade-out / bobbing animations (1 = normal). */
	public double animationSpeed = 1.0;
	/** Lines are cut into groups of at most this many words / letters. */
	public int maxWordsPerGroup = 3;
	public int maxCharsPerGroup = 12;
	/** Shift lyric timing (ms). Positive shows words earlier. Adjust in game with [ and ]. */
	public long offsetMs = 0;
	/** Which app to read: "spotify", or "*" for whatever is playing (browsers, other players). */
	public String player = "spotify";
	/**
	 * When a song only has plain (untimed) lyrics, show them anyway with estimated timing.
	 * Press ; in game the moment you hear a line start to line them up.
	 */
	public boolean estimateUnsyncedLyrics = true;
	/** AI lyric maker: song language ("auto", "hu", "en"). */
	public String aiLanguage = "auto";
	/** AI lyric maker: speech model, "small" (fast) or "medium" (better, slower). */
	public String aiModel = "small";
	/**
	 * Also look on YouTube Music when LRCLIB has no synced lyrics. Uses YouTube Music's
	 * unofficial app interface, so it may stop working if YouTube changes it.
	 */
	public boolean youtubeMusicLyrics = true;
	/** Country for YouTube Music lyrics (they're licensed per country). Empty = your system's country. */
	public String youtubeRegion = "";
	/** Settings file format; used to upgrade old files. */
	public int configVersion = 0;

	private static final int CURRENT_VERSION = 2;

	private transient Path path;

	public static SkyLyricsConfig load(Path path) {
		SkyLyricsConfig cfg = null;

		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				cfg = GSON.fromJson(r, SkyLyricsConfig.class);
			} catch (IOException | RuntimeException e) {
				LOG.warn("[Sky Lyrics] Couldn't read {}, using defaults: {}", path, e.toString());
			}
		}

		if (cfg == null) cfg = new SkyLyricsConfig();
		if (cfg.colors == null || cfg.colors.isEmpty()) cfg.colors = new SkyLyricsConfig().colors;
		cfg.upgrade();
		cfg.path = path;
		cfg.save(); // writes any new settings with their defaults
		return cfg;
	}

	/** Files from 1.1.0 have no version: move untouched old defaults to the new, farther ones. */
	private void upgrade() {
		if (configVersion >= CURRENT_VERSION) return;
		SkyLyricsConfig d = new SkyLyricsConfig();
		if (distanceMin == 13 && distanceMax == 22) { distanceMin = d.distanceMin; distanceMax = d.distanceMax; }
		if (heightMin == -0.5 && heightMax == 4.5) { heightMin = d.heightMin; heightMax = d.heightMax; }
		if (spread == 38) spread = d.spread;
		configVersion = CURRENT_VERSION;
	}

	public void save() {
		if (path == null) return;

		try {
			Files.createDirectories(path.getParent());

			try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't save {}: {}", path, e.toString());
		}
	}

	public String youtubeRegion() {
		if (youtubeRegion != null && youtubeRegion.strip().length() == 2) return youtubeRegion.strip();
		String c = java.util.Locale.getDefault().getCountry();
		return c == null || c.length() != 2 ? "US" : c;
	}

	public LyricScene.Settings sceneSettings() {
		float dMin = (float) Math.max(4, Math.min(distanceMin, distanceMax));
		float dMax = (float) Math.max(dMin, Math.max(distanceMin, distanceMax));
		float hMin = (float) Math.min(heightMin, heightMax);
		float hMax = (float) Math.max(heightMin, heightMax);
		return new LyricScene.Settings(Math.max(4, maxCharsPerGroup), Math.max(1, maxWordsPerGroup),
				(float) Math.max(0, Math.min(80, spread)), dMin, dMax, hMin, hMax, Math.max(0, holdMs), uppercase,
				estimateUnsyncedLyrics, (float) Math.max(0.1, animationSpeed));
	}

	public boolean isCustomColors() {
		return "custom".equalsIgnoreCase(colorMode == null ? "" : colorMode.strip());
	}

	public dev.skylyrics.render.ColorScheme colorScheme() {
		return new dev.skylyrics.render.ColorScheme(isCustomColors(),
				dev.skylyrics.render.ColorScheme.parsePalette(colors),
				dev.skylyrics.render.ColorScheme.parse(customColor, 0x4DD8FF),
				dev.skylyrics.render.ColorScheme.parse(customFadeColor, 0x9B7BFF),
				customFade, shimmer);
	}

	/** A full copy (used by the settings menu to undo on Cancel). */
	public SkyLyricsConfig copy() {
		SkyLyricsConfig c = GSON.fromJson(GSON.toJson(this), SkyLyricsConfig.class);
		c.path = path;
		return c;
	}

	/** Overwrite every setting with the other config's values. */
	public void copyFrom(SkyLyricsConfig other) {
		SkyLyricsConfig c = GSON.fromJson(GSON.toJson(other), SkyLyricsConfig.class);
		for (java.lang.reflect.Field f : SkyLyricsConfig.class.getDeclaredFields()) {
			int mod = f.getModifiers();
			if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) continue;
			try {
				f.set(this, f.get(c));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/** Middle of the distance range; the menu moves both ends together. */
	public double distanceCenter() {
		return (distanceMin + distanceMax) / 2.0;
	}

	public void setDistanceCenter(double center) {
		distanceMin = Math.round(center * 0.8 * 10) / 10.0;
		distanceMax = Math.round(center * 1.2 * 10) / 10.0;
	}

	/** Middle of the height range; the menu moves both ends together. */
	public double heightCenter() {
		return (heightMin + heightMax) / 2.0;
	}

	public void setHeightCenter(double center) {
		double half = Math.max(0.5, (heightMax - heightMin) / 2.0);
		heightMin = Math.round((center - half) * 10) / 10.0;
		heightMax = Math.round((center + half) * 10) / 10.0;
	}

	public static int parseColor(String hex, int fallback) {
		if (hex == null) return fallback;
		String h = hex.strip();
		if (h.startsWith("#")) h = h.substring(1);
		if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);

		try {
			return Integer.parseUnsignedInt(h, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
