package dev.skylyrics.lyrics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/**
 * Remembers "this song uses the lyrics of that YouTube video", set with the menu's Paste link
 * button. Only the link is stored (in links.json in the lyrics folder); the lyrics themselves are
 * fetched from YouTube Music each time the song plays.
 */
public final class LinkStore {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Type TYPE = new TypeToken<LinkedHashMap<String, Link>>() { }.getType();

	public record Link(String artist, String title, String videoId) {
	}

	private final Path file;
	private final Map<String, Link> links = new LinkedHashMap<>();

	public LinkStore(Path file) {
		this.file = file;
		load();
	}

	public synchronized String videoId(NowPlaying np) {
		Link l = links.get(key(np));
		return l == null ? null : l.videoId();
	}

	public synchronized void put(NowPlaying np, String videoId) {
		links.put(key(np), new Link(np.artist(), np.title(), videoId));
		save();
	}

	public synchronized boolean remove(NowPlaying np) {
		boolean had = links.remove(key(np)) != null;
		if (had) save();
		return had;
	}

	static String key(NowPlaying np) {
		return LocalLyrics.key(np.artist() + " - " + np.title());
	}

	private void load() {
		if (!Files.exists(file)) return;

		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Link> m = GSON.fromJson(r, TYPE);
			if (m != null) m.forEach((k, v) -> { if (v != null && v.videoId() != null) links.put(k, v); });
		} catch (IOException | RuntimeException e) {
			LOG.warn("[Sky Lyrics] Couldn't read {}: {}", file, e.toString());
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(links, TYPE, w);
			}
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't save {}: {}", file, e.toString());
		}
	}
}
