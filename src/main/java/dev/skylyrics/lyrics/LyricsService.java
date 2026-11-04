package dev.skylyrics.lyrics;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/**
 * Finds lyrics for a track: LRCLIB first, then YouTube Music if LRCLIB has no synced version.
 * Synced lyrics from either source win; otherwise the best estimated/plain result is used.
 */
public final class LyricsService {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");

	private final LocalLyrics local;
	private final LrcLibClient lrclib;
	private final YtMusicLyricsClient youtube;
	private final BooleanSupplier useYoutube;
	private final Map<String, LyricsResult> cache = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "SkyLyrics lyrics lookup");
		t.setDaemon(true);
		return t;
	});

	public LyricsService(LocalLyrics local, LrcLibClient lrclib, YtMusicLyricsClient youtube, BooleanSupplier useYoutube) {
		this.local = local;
		this.lrclib = lrclib;
		this.youtube = youtube;
		this.useYoutube = useYoutube;
	}

	/** Forget every downloaded result so the next lookups ask the sources again. */
	public void clearCache() {
		cache.clear();
	}

	public CompletableFuture<LyricsResult> fetch(NowPlaying np) {
		String key = np.trackKey();

		return CompletableFuture.supplyAsync(() -> {
			// Your own lyrics folder always wins (checked every time, so new files are picked up).
			LyricsResult mine = local.find(np);
			if (mine != null) return mine;

			LyricsResult cached = cache.get(key);
			if (cached != null) return cached;

			LyricsResult result = lookup(np);
			// Only cache when a source actually answered, so a network hiccup can be retried.
			if (result != null) cache.put(key, result);
			return result;
		}, executor);
	}

	private LyricsResult lookup(NowPlaying np) {
		LyricsResult fromLrclib = null;
		LyricsResult fromYoutube = null;
		RuntimeException failure = null;

		try {
			fromLrclib = lrclib.lookup(np);
			if (fromLrclib.kind() == LyricsResult.Kind.SYNCED) return fromLrclib;
		} catch (RuntimeException e) {
			failure = e;
		}

		if (useYoutube.getAsBoolean()) {
			try {
				fromYoutube = youtube.lookup(np);

				if (fromYoutube.kind() == LyricsResult.Kind.SYNCED) {
					LOG.info("[Sky Lyrics] Synced lyrics for '{}' from YouTube Music", np.title());
					return fromYoutube;
				}
			} catch (RuntimeException e) {
				if (failure == null) failure = e;
			}
		}

		LyricsResult best = pick(fromLrclib, fromYoutube);
		if (best != null) return best;
		throw failure != null ? failure : new IllegalStateException("no lyrics source answered");
	}

	private static LyricsResult pick(LyricsResult a, LyricsResult b) {
		if (a == null) return b;
		if (b == null) return a;
		return rank(b) > rank(a) ? b : a;
	}

	private static int rank(LyricsResult r) {
		return switch (r.kind()) {
			case SYNCED -> 4;
			case ESTIMATED -> 3;
			case UNSYNCED -> 2;
			case INSTRUMENTAL -> 1;
			case NOT_FOUND -> 0;
		};
	}
}
