package dev.skylyrics.lyrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/**
 * Looks up lyrics through YouTube Music's (unofficial) app interface, the way the open-source
 * ytmusicapi library does: search the song, open its "Lyrics" tab, and ask for the lyrics as
 * the Android app would, which returns them with timestamps when YouTube Music has them synced.
 * This interface isn't public, so it may change or stop working at any time.
 */
public final class YtMusicLyricsClient {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final String API = "https://music.youtube.com/youtubei/v1/";
	/** YouTube Music web client's public key (as used by ytmusicapi). */
	private static final String PARAMS = "?alt=json&prettyPrint=false&key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
	/** Search filter: songs only. */
	private static final String SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF";
	private static final String ANDROID_MUSIC_VERSION = "7.21.50";
	private static final Pattern DURATION = Pattern.compile("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$");
	private static final double MAX_DURATION_DIFF_S = 8;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final String region;

	/** @param region two-letter country (affects which lyrics are licensed), e.g. "HU" */
	public YtMusicLyricsClient(String region) {
		this.region = region == null || region.isBlank() ? "US" : region.strip().toUpperCase(Locale.ROOT);
	}

	/** A song found by search. */
	record Hit(String videoId, String title, String subtitle, double durationS) {
	}

	/**
	 * Blocking lookup. Returns SYNCED when YouTube Music has timed lyrics, ESTIMATED when it
	 * only has plain lyrics, NOT_FOUND otherwise. Throws a RuntimeException if it can't be reached.
	 */
	public LyricsResult lookup(NowPlaying np) {
		try {
			Hit hit = findSong(np);
			if (hit == null) return LyricsResult.of(LyricsResult.Kind.NOT_FOUND);
			long dur = np.durationMs() > 0 ? np.durationMs() : Math.round(hit.durationS() * 1000);
			return lyricsForVideo(hit.videoId(), dur);
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] YouTube Music lookup failed for '{}' by '{}': {}", np.title(), np.artist(), e.toString());
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	/** Lyrics of one specific YouTube / YouTube Music video (from a link the player pasted). */
	public LyricsResult lookupVideo(String videoId, long durationMs) {
		try {
			return lyricsForVideo(videoId, durationMs);
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] YouTube Music lookup failed for video {}: {}", videoId, e.toString());
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private LyricsResult lyricsForVideo(String videoId, long durationMs) throws IOException, InterruptedException {
		JsonObject next = next(videoId);
		String browseId = lyricsBrowseId(next);

		if (browseId == null) {
			// Music videos often have no lyrics tab, but their audio-only "song" version does.
			for (String other : counterparts(next, videoId)) {
				browseId = lyricsBrowseId(other);
				if (browseId != null) break;
			}
		}

		if (browseId == null) {
			// Last try: search with the video's own title and artist.
			String[] info = videoInfo(next, videoId);

			if (info != null) {
				NowPlaying np = new NowPlaying(songTitleFromVideo(info[0], info[1]), info[1], "", 0, durationMs, true, 0);
				Hit hit = findSong(np);
				if (hit != null && !hit.videoId().equals(videoId)) browseId = lyricsBrowseId(hit.videoId());
			}
		}

		if (browseId == null) return LyricsResult.of(LyricsResult.Kind.NOT_FOUND);

		Lyrics timed = timedLyrics(browseId);
		if (timed != null && !timed.isEmpty()) return LyricsResult.synced(timed);

		String plain = plainLyrics(browseId);

		if (plain != null && !plain.isBlank()) {
			Lyrics estimated = PlainLyricsEstimator.estimate(plain, durationMs);
			if (!estimated.isEmpty()) return new LyricsResult(LyricsResult.Kind.ESTIMATED, estimated);
		}

		return LyricsResult.of(LyricsResult.Kind.NOT_FOUND);
	}

	private static final Pattern VIDEO_ID = Pattern.compile(
			"(?:v=|/shorts/|youtu\\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");

	/** The 11-character video id from a YouTube / YouTube Music / youtu.be link, or null. */
	public static String videoIdFromLink(String link) {
		if (link == null) return null;
		String t = link.strip();
		if (t.matches("[A-Za-z0-9_-]{11}")) return t;
		if (!t.contains("youtu")) return null;
		Matcher m = VIDEO_ID.matcher(t);
		return m.find() ? m.group(1) : null;
	}

	/** Search for the song and pick the result whose title, artist and length match best. */
	Hit findSong(NowPlaying np) throws IOException, InterruptedException {
		String query = TitleCleaner.cleanTitle(np.title()) + " " + TitleCleaner.primaryArtist(np.artist());
		JsonObject body = new JsonObject();
		body.addProperty("query", query.strip());
		body.addProperty("params", SONGS_FILTER);

		JsonObject res = post("search", body, webClient());
		List<Hit> hits = new ArrayList<>();
		collectHits(res, hits);

		double wantS = np.durationMs() / 1000.0;
		String wantTitle = TitleCleaner.normalize(TitleCleaner.cleanTitle(np.title()));
		Hit best = null;
		int bestScore = Integer.MIN_VALUE;

		for (int i = 0; i < hits.size() && i < 10; i++) {
			Hit h = hits.get(i);
			String title = TitleCleaner.normalize(TitleCleaner.cleanTitle(h.title()));
			boolean titleMatch = titleMatches(wantTitle, title);
			boolean artistMatch = TitleCleaner.normalize(h.subtitle()).contains(TitleCleaner.normalize(TitleCleaner.primaryArtist(np.artist())));
			boolean durationKnown = wantS > 0 && h.durationS() > 0;
			double diff = durationKnown ? Math.abs(h.durationS() - wantS) : 0;

			if (!titleMatch && !artistMatch) continue;
			if (durationKnown && diff > MAX_DURATION_DIFF_S * 3) continue;

			int score = (titleMatch ? 100 : 0) + (artistMatch ? 80 : 0) - (int) Math.min(60, diff * 4) - i;
			if (score > bestScore) {
				bestScore = score;
				best = h;
			}
		}

		return best;
	}

	/** Same title, or one contains the other and they're of similar length (not "Saki" vs "Saki & Baleszko Top 1"). */
	static boolean titleMatches(String want, String got) {
		if (want.isEmpty() || got.isEmpty()) return false;
		if (want.equals(got)) return true;
		String longer = want.length() >= got.length() ? want : got;
		String shorter = longer == want ? got : want;
		return longer.contains(shorter) && shorter.length() >= longer.length() * 0.6;
	}

	/**
	 * Song title from a video title: "SAKI & BALESZKO TOP 1 (Official Music)" with artist
	 * "Saki & Baleszko" → "TOP 1"; "Artist - Song (Official Video)" → "Song".
	 */
	static String songTitleFromVideo(String videoTitle, String artist) {
		String t = videoTitle == null ? "" : videoTitle.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").strip();
		String a = artist == null ? "" : artist.strip();
		String[] parts = t.split("\\s+[-\u2013\u2014|:]\\s+", 2);

		if (parts.length == 2) {
			if (TitleCleaner.artistMatches(parts[0], a)) return parts[1].strip();
			if (TitleCleaner.artistMatches(parts[1], a)) return parts[0].strip();
		}

		if (!a.isEmpty()) {
			String plainT = TitleCleaner.stripDiacritics(t).toLowerCase(java.util.Locale.ROOT);
			String plainA = TitleCleaner.stripDiacritics(a).toLowerCase(java.util.Locale.ROOT);
			if (plainT.startsWith(plainA) && t.length() > a.length()) return t.substring(a.length()).replaceFirst("^[\\s\\-\u2013\u2014|:]+", "").strip();
		}

		return t;
	}

	/** The song's "Lyrics" tab id, or null if YouTube Music has no lyrics for it. */
	String lyricsBrowseId(String videoId) throws IOException, InterruptedException {
		return lyricsBrowseId(next(videoId));
	}

	private JsonObject next(String videoId) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("videoId", videoId);
		body.addProperty("isAudioOnly", true);
		body.addProperty("enablePersistentPlaylistPanel", true);
		return post("next", body, webClient());
	}

	static String lyricsBrowseId(JsonObject res) {
		JsonArray tabs = arr(nav(res, "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
				"watchNextTabbedResultsRenderer", "tabs"));
		if (tabs == null) return null;

		for (JsonElement tab : tabs) {
			JsonObject tr = obj(nav(tab, "tabRenderer"));
			if (tr == null || tr.has("unselectable")) continue;
			JsonObject be = obj(nav(tr, "endpoint", "browseEndpoint"));
			if (be == null) continue;
			String pageType = str(nav(be, "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"));
			if ("MUSIC_PAGE_TYPE_TRACK_LYRICS".equals(pageType)) return str(be.get("browseId"));
		}

		return null;
	}

	/** Structure-only summary of how a video resolves (for the CI check; no lyric text). */
	public String debugVideo(String videoId) {
		try {
			JsonObject next = next(videoId);
			StringBuilder sb = new StringBuilder();
			sb.append("lyricsTab=").append(lyricsBrowseId(next) != null);
			List<String> cps = counterparts(next, videoId);
			sb.append(" counterparts=").append(cps);
			for (String c : cps) sb.append(" [").append(c).append(" lyricsTab=").append(lyricsBrowseId(c) != null).append(']');
			String[] info = videoInfo(next, videoId);
			sb.append(" info=").append(info == null ? "null" : info[0] + " | " + info[1]);
			JsonArray tabs = arr(nav(next, "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
					"watchNextTabbedResultsRenderer", "tabs"));
			sb.append(" tabs=").append(tabs == null ? "none" : tabs.size());
			if (tabs != null) for (JsonElement t : tabs) {
				JsonObject tr = obj(nav(t, "tabRenderer"));
				sb.append(" {").append(tr == null ? "?" : str(nav(tr, "title"))).append(tr != null && tr.has("unselectable") ? " unselectable" : "").append('}');
			}
			if (info != null) {
				sb.append(" songTitle=").append(songTitleFromVideo(info[0], info[1]));
				Hit hit = findSong(new NowPlaying(songTitleFromVideo(info[0], info[1]), info[1], "", 0, 0, true, 0));
				sb.append(" search=").append(hit == null ? "none" : hit.videoId() + " '" + hit.title() + "'");
				if (hit != null) sb.append(" hitLyricsTab=").append(lyricsBrowseId(hit.videoId()) != null);
			}
			return sb.toString();
		} catch (Exception e) {
			return "ERROR " + e;
		}
	}

	/** Video ids of the other version (song ⇄ music video) mentioned in a "next" response. */
	static List<String> counterparts(JsonElement el, String self) {
		List<String> out = new ArrayList<>();
		collectCounterparts(el, self, out);
		return out;
	}

	private static void collectCounterparts(JsonElement el, String self, List<String> out) {
		if (el == null || el.isJsonNull()) return;

		if (el.isJsonArray()) {
			for (JsonElement e : el.getAsJsonArray()) collectCounterparts(e, self, out);
			return;
		}

		if (!el.isJsonObject()) return;
		JsonObject o = el.getAsJsonObject();

		if (o.has("counterpartRenderer")) {
			String id = str(nav(o, "counterpartRenderer", "playlistPanelVideoRenderer", "videoId"));
			if (id != null && !id.equals(self) && !out.contains(id)) out.add(id);
		}

		for (String k : o.keySet()) collectCounterparts(o.get(k), self, out);
	}

	/** {title, artist} of the video itself from a "next" response, or null. */
	static String[] videoInfo(JsonElement el, String videoId) {
		if (el == null || el.isJsonNull()) return null;

		if (el.isJsonArray()) {
			for (JsonElement e : el.getAsJsonArray()) {
				String[] r = videoInfo(e, videoId);
				if (r != null) return r;
			}
			return null;
		}

		if (!el.isJsonObject()) return null;
		JsonObject o = el.getAsJsonObject();
		JsonObject v = obj(o.get("playlistPanelVideoRenderer"));

		if (v != null && videoId.equals(str(v.get("videoId")))) {
			String title = runsText(nav(v, "title", "runs"));
			JsonArray by = arr(nav(v, "longBylineText", "runs"));
			String artist = by == null || by.isEmpty() ? "" : str(nav(by.get(0), "text"));
			if (title != null && !title.isBlank()) return new String[] {title, artist == null ? "" : artist};
		}

		for (String k : o.keySet()) {
			String[] r = videoInfo(o.get(k), videoId);
			if (r != null) return r;
		}

		return null;
	}

	/** Lyrics with timestamps (asked as the Android app), or null if they aren't synced. */
	Lyrics timedLyrics(String browseId) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("browseId", browseId);
		JsonObject res = post("browse", body, mobileClient());

		JsonArray data = arr(nav(res, "contents", "elementRenderer", "newElement", "type", "componentType", "model",
				"timedLyricsModel", "lyricsData", "timedLyricsData"));
		if (data == null || data.isEmpty()) return null;

		List<LyricLine> lines = new ArrayList<>();

		for (JsonElement el : data) {
			String text = str(nav(el, "lyricLine"));
			String start = str(nav(el, "cueRange", "startTimeMilliseconds"));
			if (text == null || start == null) return null; // not timed after all

			try {
				lines.add(new LyricLine(Long.parseLong(start.strip()), text));
			} catch (NumberFormatException e) {
				return null;
			}
		}

		lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
		return new Lyrics(lines);
	}

	/** Plain lyrics text from the web client, or null. */
	String plainLyrics(String browseId) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("browseId", browseId);
		JsonObject res = post("browse", body, webClient());
		JsonArray sections = arr(nav(res, "contents", "sectionListRenderer", "contents"));
		if (sections == null) return null;

		for (JsonElement sec : sections) {
			JsonArray runs = arr(nav(sec, "musicDescriptionShelfRenderer", "description", "runs"));
			if (runs == null) continue;
			StringBuilder sb = new StringBuilder();
			for (JsonElement run : runs) {
				String t = str(nav(run, "text"));
				if (t != null) sb.append(t);
			}
			if (!sb.isEmpty()) return sb.toString();
		}

		return null;
	}

	/** Every song row in a search response: video id, title, subtitle ("Song • Artist • Album • 3:39"). */
	static void collectHits(JsonElement el, List<Hit> out) {
		if (el == null || el.isJsonNull()) return;

		if (el.isJsonArray()) {
			for (JsonElement e : el.getAsJsonArray()) collectHits(e, out);
			return;
		}

		if (!el.isJsonObject()) return;
		JsonObject o = el.getAsJsonObject();
		JsonObject item = obj(o.get("musicResponsiveListItemRenderer"));

		if (item != null) {
			Hit hit = toHit(item);
			if (hit != null) out.add(hit);
			return;
		}

		for (String k : o.keySet()) collectHits(o.get(k), out);
	}

	private static Hit toHit(JsonObject item) {
		String videoId = str(nav(item, "playlistItemData", "videoId"));
		if (videoId == null) videoId = str(nav(item, "overlay", "musicItemThumbnailOverlayRenderer", "content",
				"musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId"));
		if (videoId == null) return null;

		JsonArray cols = arr(item.get("flexColumns"));
		if (cols == null || cols.isEmpty()) return null;

		String title = runsText(nav(cols.get(0), "musicResponsiveListItemFlexColumnRenderer", "text", "runs"));
		StringBuilder subtitle = new StringBuilder();
		double duration = 0;

		for (int i = 1; i < cols.size(); i++) {
			JsonArray runs = arr(nav(cols.get(i), "musicResponsiveListItemFlexColumnRenderer", "text", "runs"));
			if (runs == null) continue;

			for (JsonElement run : runs) {
				String t = str(nav(run, "text"));
				if (t == null) continue;
				subtitle.append(t);
				Matcher m = DURATION.matcher(t.strip());

				if (m.matches()) {
					int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
					duration = h * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
				}
			}

			subtitle.append(' ');
		}

		JsonArray fixed = arr(item.get("fixedColumns"));

		if (duration == 0 && fixed != null) {
			for (JsonElement col : fixed) {
				String t = runsText(nav(col, "musicResponsiveListItemFixedColumnRenderer", "text", "runs"));
				Matcher m = t == null ? null : DURATION.matcher(t.strip());

				if (m != null && m.matches()) {
					int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
					duration = h * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
				}
			}
		}

		return new Hit(videoId, title == null ? "" : title, subtitle.toString().strip(), duration);
	}

	private JsonObject webClient() {
		JsonObject c = new JsonObject();
		c.addProperty("clientName", "WEB_REMIX");
		c.addProperty("clientVersion", "1." + LocalDate.now(ZoneOffset.UTC).minusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE) + ".01.00");
		c.addProperty("hl", "en");
		c.addProperty("gl", region);
		return c;
	}

	private JsonObject mobileClient() {
		JsonObject c = new JsonObject();
		c.addProperty("clientName", "ANDROID_MUSIC");
		c.addProperty("clientVersion", ANDROID_MUSIC_VERSION);
		c.addProperty("hl", "en");
		c.addProperty("gl", region);
		return c;
	}

	private JsonObject post(String endpoint, JsonObject body, JsonObject client) throws IOException, InterruptedException {
		JsonObject context = new JsonObject();
		context.add("client", client);
		context.add("user", new JsonObject());
		body.add("context", context);

		HttpRequest req = HttpRequest.newBuilder(URI.create(API + endpoint + PARAMS))
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.header("Accept", "*/*")
				.header("Origin", "https://music.youtube.com")
				.header("Referer", "https://music.youtube.com/")
				.timeout(Duration.ofSeconds(12))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (res.statusCode() / 100 != 2) throw new IOException("YouTube Music answered HTTP " + res.statusCode() + " for " + endpoint);
		JsonElement el = JsonParser.parseString(res.body());
		if (!el.isJsonObject()) throw new IOException("YouTube Music sent an unexpected " + endpoint + " response");
		return el.getAsJsonObject();
	}

	// --- small JSON helpers ---

	static JsonElement nav(JsonElement el, String... path) {
		JsonElement cur = el;

		for (String key : path) {
			if (cur == null || !cur.isJsonObject()) return null;
			cur = cur.getAsJsonObject().get(key);
		}

		return cur == null || cur.isJsonNull() ? null : cur;
	}

	private static String runsText(JsonElement runs) {
		JsonArray a = arr(runs);
		if (a == null) return null;
		StringBuilder sb = new StringBuilder();
		for (JsonElement r : a) {
			String t = str(nav(r, "text"));
			if (t != null) sb.append(t);
		}
		return sb.toString();
	}

	private static JsonObject obj(JsonElement e) {
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray arr(JsonElement e) {
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	private static String str(JsonElement e) {
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
