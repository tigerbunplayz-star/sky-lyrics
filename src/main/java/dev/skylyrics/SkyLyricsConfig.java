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
	/** How long a line stays after the next one starts (ms). */
	public long holdMs = 2200;
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

	public LyricScene.Settings sceneSettings() {
		float dMin = (float) Math.max(4, Math.min(distanceMin, distanceMax));
		float dMax = (float) Math.max(dMin, Math.max(distanceMin, distanceMax));
		float hMin = (float) Math.min(heightMin, heightMax);
		float hMax = (float) Math.max(heightMin, heightMax);
		return new LyricScene.Settings(Math.max(4, maxCharsPerGroup), Math.max(1, maxWordsPerGroup),
				(float) Math.max(0, Math.min(80, spread)), dMin, dMax, hMin, hMax, Math.max(0, holdMs), uppercase,
				estimateUnsyncedLyrics);
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
