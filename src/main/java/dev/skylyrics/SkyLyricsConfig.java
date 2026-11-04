package dev.skylyrics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings, stored in .minecraft/config/skylyrics.json. Edit the file and restart to change them. */
public final class SkyLyricsConfig {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Show lyrics at all (toggle in game with K). */
	public boolean enabled = true;
	/** How far away the text floats, in blocks. Lower it if you play with a tiny render distance. */
	public double distance = 48;
	/** Text size multiplier. */
	public double size = 1.0;
	/** Degrees above the horizon. */
	public double elevation = 28;
	/** true: the lyrics swing around to stay in front of you. false: they stay put at fixedYaw. */
	public boolean followView = true;
	/** How quickly the lyrics catch up when you turn (higher = snappier). */
	public double followSpeed = 1.6;
	/** Compass direction for followView=false: 0 south, 90 west, 180 north, 270 east. */
	public double fixedYaw = 180;
	/** Show the upcoming line, faded, under the current one. */
	public boolean showNextLine = true;
	/** Light up each line from left to right as it's sung. */
	public boolean karaoke = true;
	public String lyricColor = "#FFFFFF";
	public String unsungColor = "#9FB0C8";
	public String titleColor = "#1ED760";
	public String dimColor = "#DDE6F0";
	public double opacity = 1.0;
	public boolean shadow = true;
	/** Dark box behind the text for readability on bright skies. */
	public boolean background = false;
	/** Shift lyric timing (ms). Positive shows lines earlier. Adjust in game with [ and ]. */
	public long offsetMs = 0;
	/** Which app to read: "spotify", or "*" for whatever is playing (browsers, other players). */
	public String player = "spotify";
	/** Longest line width (in Minecraft font pixels) before wrapping. */
	public int maxLineWidth = 150;

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
		cfg.path = path;
		cfg.save(); // writes any new settings with their defaults
		return cfg;
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
