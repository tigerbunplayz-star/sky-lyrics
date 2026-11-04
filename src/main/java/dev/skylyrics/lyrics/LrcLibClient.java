package dev.skylyrics.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/** Looks up synced lyrics on LRCLIB (https://lrclib.net), a free, key-less lyrics database. */
public final class LrcLibClient {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final String BASE = "https://lrclib.net/api";
	/** Search results whose length differs more than this are probably a different version. */
	private static final double MAX_DURATION_DIFF_S = 6;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SkyLyrics lyrics lookup");
		t.setDaemon(true);
		return t;
	});
	private final Map<String, LyricsResult> cache = new ConcurrentHashMap<>();
	private final String userAgent;

	public LrcLibClient(String modVersion) {
		this.userAgent = "SkyLyrics/" + modVersion + " (https://github.com/tigerbunplayz-star/sky-lyrics)";
	}

	public CompletableFuture<LyricsResult> fetch(NowPlaying np) {
		String key = np.trackKey();
		LyricsResult cached = cache.get(key);
		if (cached != null) return CompletableFuture.completedFuture(cached);

		return CompletableFuture.supplyAsync(() -> {
			LyricsResult result = lookup(np);
			// Only cache real answers, so a network hiccup can be retried.
			if (result != null) cache.put(key, result);
			return result;
		}, executor);
	}

	private LyricsResult lookup(NowPlaying np) {
		String title = np.title();
		String artist = np.artist();
		double durationS = np.durationMs() / 1000.0;
		String cleanTitle = TitleCleaner.cleanTitle(title);
		String mainArtist = TitleCleaner.primaryArtist(artist);

		try {
			Candidate best = null;

			if (durationS > 0) {
				best = better(best, getExact(title, artist, np.album(), durationS), durationS);
				if (best != null && best.kind == LyricsResult.Kind.SYNCED) return best.toResult();
			}

			best = better(best, search(params("track_name", title, "artist_name", artist), durationS), durationS);
			if (isGoodSynced(best, durationS)) return best.toResult();

			if (!cleanTitle.equalsIgnoreCase(title) || !mainArtist.equalsIgnoreCase(artist)) {
				best = better(best, search(params("track_name", cleanTitle, "artist_name", mainArtist), durationS), durationS);
				if (isGoodSynced(best, durationS)) return best.toResult();
			}

			best = better(best, search(params("q", cleanTitle + " " + mainArtist), durationS), durationS);

			if (best == null) return LyricsResult.of(LyricsResult.Kind.NOT_FOUND);
			return best.toResult();
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Lyrics lookup failed for '{}' by '{}': {}", title, artist, e.toString());
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private Candidate getExact(String title, String artist, String album, double durationS) throws IOException, InterruptedException {
		Map<String, String> p = params("track_name", title, "artist_name", artist);
		if (!album.isEmpty()) p.put("album_name", album);
		p.put("duration", Long.toString(Math.round(durationS)));

		JsonElement body = get("/get", p);
		if (body == null || !body.isJsonObject()) return null;
		return Candidate.from(body.getAsJsonObject());
	}

	private Candidate search(Map<String, String> p, double durationS) throws IOException, InterruptedException {
		JsonElement body = get("/search", p);
		if (body == null || !body.isJsonArray()) return null;

		JsonArray arr = body.getAsJsonArray();
		Candidate best = null;

		for (JsonElement el : arr) {
			if (!el.isJsonObject()) continue;
			best = better(best, Candidate.from(el.getAsJsonObject()), durationS);
		}

		return best;
	}

	private JsonElement get(String path, Map<String, String> params) throws IOException, InterruptedException {
		StringJoiner q = new StringJoiner("&");
		params.forEach((k, v) -> q.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));

		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path + "?" + q))
				.header("User-Agent", userAgent)
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(12))
				.GET()
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (res.statusCode() == 404) return null;
		if (res.statusCode() / 100 != 2) throw new IOException("LRCLIB answered HTTP " + res.statusCode());
		return JsonParser.parseString(res.body());
	}

	private static boolean isGoodSynced(Candidate c, double durationS) {
		return c != null && c.kind == LyricsResult.Kind.SYNCED
				&& (durationS <= 0 || c.durationS <= 0 || Math.abs(c.durationS - durationS) <= MAX_DURATION_DIFF_S);
	}

	/** Prefers synced lyrics, then a closer track length. */
	private static Candidate better(Candidate a, Candidate b, double durationS) {
		if (b == null) return a;
		if (a == null) return b;
		if (rank(b) != rank(a)) return rank(b) > rank(a) ? b : a;
		return diff(b, durationS) < diff(a, durationS) ? b : a;
	}

	private static int rank(Candidate c) {
		return switch (c.kind) {
			case SYNCED -> 3;
			case INSTRUMENTAL -> 2;
			case UNSYNCED -> 1;
			case NOT_FOUND -> 0;
		};
	}

	private static double diff(Candidate c, double durationS) {
		if (durationS <= 0 || c.durationS <= 0) return 0;
		return Math.abs(c.durationS - durationS);
	}

	private static Map<String, String> params(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}

	private static final class Candidate {
		final LyricsResult.Kind kind;
		final Lyrics lyrics;
		final double durationS;

		Candidate(LyricsResult.Kind kind, Lyrics lyrics, double durationS) {
			this.kind = kind;
			this.lyrics = lyrics;
			this.durationS = durationS;
		}

		static Candidate from(JsonObject o) {
			double duration = num(o, "duration");
			String synced = str(o, "syncedLyrics");

			if (synced != null && !synced.isBlank()) {
				Lyrics parsed = LrcParser.parse(synced);
				if (!parsed.isEmpty() && parsed.firstLineTime() >= 0) {
					return new Candidate(LyricsResult.Kind.SYNCED, parsed, duration);
				}
			}

			if (o.has("instrumental") && o.get("instrumental").isJsonPrimitive() && o.get("instrumental").getAsBoolean()) {
				return new Candidate(LyricsResult.Kind.INSTRUMENTAL, null, duration);
			}

			String plain = str(o, "plainLyrics");
			if (plain != null && !plain.isBlank()) return new Candidate(LyricsResult.Kind.UNSYNCED, null, duration);
			return null;
		}

		LyricsResult toResult() {
			return kind == LyricsResult.Kind.SYNCED ? LyricsResult.synced(lyrics) : LyricsResult.of(kind);
		}

		private static String str(JsonObject o, String k) {
			JsonElement e = o.get(k);
			return e == null || e.isJsonNull() ? null : e.getAsString();
		}

		private static double num(JsonObject o, String k) {
			JsonElement e = o.get(k);
			if (e == null || e.isJsonNull()) return 0;
			try {
				return e.getAsDouble();
			} catch (RuntimeException ex) {
				return 0;
			}
		}
	}
}
