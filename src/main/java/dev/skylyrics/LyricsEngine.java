package dev.skylyrics;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import dev.skylyrics.lyrics.LyricLine;
import dev.skylyrics.lyrics.Lyrics;
import dev.skylyrics.lyrics.LyricsResult;
import dev.skylyrics.media.NowPlaying;
import dev.skylyrics.media.PlaybackClock;

/** Follows the current track, fetches its lyrics and keeps a smooth playback clock. */
public final class LyricsEngine {
	public enum Status { IDLE, LOADING, SYNCED, ESTIMATED, UNSYNCED, INSTRUMENTAL, NOT_FOUND, ERROR }

	/** Everything the sky needs to draw one frame. */
	public record Snapshot(Status status, String title, String artist, Lyrics lyrics,
			double positionMs, boolean playing, double msSinceTrackStart) {
	}

	private final Function<NowPlaying, CompletableFuture<LyricsResult>> fetcher;
	private final PlaybackClock clock = new PlaybackClock();

	private String trackKey;
	private NowPlaying track;
	private long trackStartNanos;
	private long lastSampleNanos = Long.MIN_VALUE;
	private volatile int fetchGeneration;
	private volatile Status status = Status.IDLE;
	/** Timing correction for the current song only, from the sync key. */
	private volatile long trackOffsetMs;
	private volatile Lyrics lyrics;

	/** Timing recorder: lines still waiting for a tap, and the lines timed so far. */
	private volatile java.util.List<String> recordQueue;
	private final java.util.List<LyricLine> recorded = new java.util.ArrayList<>();
	private volatile Lyrics recordedLyrics;
	/** People tap a little after they hear a line start; recorded times are moved back by this. */
	static final long REACTION_MS = 150;

	public LyricsEngine(Function<NowPlaying, CompletableFuture<LyricsResult>> fetcher) {
		this.fetcher = fetcher;
	}

	/** Call every client tick with the newest reading (or null when nothing is playing). */
	public void update(NowPlaying np, long nowNanos) {
		if (np == null || !np.hasTitle()) {
			if (trackKey != null) clearTrack();
			return;
		}

		if (!np.trackKey().equals(trackKey)) {
			startTrack(np, nowNanos);
		}

		track = np;

		if (np.sampleNanos() == lastSampleNanos) return;
		lastSampleNanos = np.sampleNanos();

		if (np.positionMs() >= 0) {
			clock.sync(np.positionMs(), np.playing(), np.sampleNanos());
		} else {
			clock.runFree(np.playing(), nowNanos);
		}
	}

	/** Re-download lyrics for the current track. */
	public void refetch() {
		if (track != null) startFetch(track);
	}

	/**
	 * The player pressed the sync key because a line is starting right now. Shifts this song's
	 * timing so the nearest line start (just passed or coming up) lines up with this moment.
	 *
	 * @return the shift applied in ms, or null if there are no lyrics to sync
	 */
	public Long syncLineStartsNow(long nowNanos, long offsetMs) {
		Lyrics l = lyrics;
		if (track == null || l == null || l.isEmpty()) return null;

		double pos = clock.positionAt(nowNanos) + offsetMs + trackOffsetMs;
		long best = Long.MAX_VALUE;

		int idx = l.indexAt(pos);

		for (int i = Math.max(0, idx - 1); i < l.size() && i <= idx + 2; i++) {
			LyricLine line = l.get(i);
			if (line == null || line.isBlank()) continue;
			long delta = Math.round(line.timeMs() - pos);
			if (Math.abs(delta) < Math.abs(best)) best = delta;
		}

		if (best == Long.MAX_VALUE) return null;
		trackOffsetMs += best;
		return best;
	}

	public NowPlaying currentTrack() {
		return track;
	}

	// --- timing recorder -------------------------------------------------------------------

	public boolean canRecord() {
		Lyrics l = lyrics;
		return track != null && l != null && l.lines().stream().anyMatch(x -> !x.isBlank());
	}

	public boolean isRecording() {
		return recordQueue != null;
	}

	/** Start timing the current lyrics: each {@link #recordTap} gives the next line its start time. */
	public boolean startRecording() {
		if (!canRecord()) return false;
		java.util.List<String> q = new java.util.ArrayList<>();
		for (LyricLine line : lyrics.lines()) if (!line.isBlank()) q.add(line.text());
		recorded.clear();
		recordedLyrics = new Lyrics(java.util.List.of());
		trackOffsetMs = 0;
		recordQueue = q;
		return true;
	}

	/** The line the next tap will time, or null. */
	public String nextRecordLine() {
		java.util.List<String> q = recordQueue;
		return q == null || q.isEmpty() ? null : q.get(0);
	}

	public int recordedCount() {
		return recorded.size();
	}

	public int recordTotal() {
		java.util.List<String> q = recordQueue;
		return recorded.size() + (q == null ? 0 : q.size());
	}

	/** Time the next line now. Returns true when every line has been timed. */
	public boolean recordTap(long nowNanos) {
		java.util.List<String> q = recordQueue;
		if (q == null || q.isEmpty()) return true;
		long t = Math.max(0, Math.round(clock.positionAt(nowNanos)) - REACTION_MS);
		if (!recorded.isEmpty()) t = Math.max(t, recorded.get(recorded.size() - 1).timeMs() + 10);
		recorded.add(new LyricLine(t, q.remove(0)));
		recordedLyrics = new Lyrics(recorded);
		return q.isEmpty();
	}

	/** Stop recording; the timed lines (possibly fewer than all) become this song's lyrics. */
	public Lyrics stopRecording() {
		Lyrics r = recordedLyrics;
		recordQueue = null;
		recordedLyrics = null;

		if (r != null && !r.isEmpty()) {
			lyrics = r;
			status = Status.SYNCED;
		}

		return r;
	}

	public Snapshot snapshot(long nowNanos, long offsetMs) {
		NowPlaying t = track;

		if (t == null) {
			return new Snapshot(Status.IDLE, "", "", null, 0, false, 0);
		}

		Lyrics rec = recordedLyrics;

		if (recordQueue != null && rec != null) {
			// While recording, show exactly what has been tapped, at the raw song position.
			return new Snapshot(Status.SYNCED, t.title(), t.artist(), rec, clock.positionAt(nowNanos), clock.isPlaying(), 60_000);
		}

		return new Snapshot(status, t.title(), t.artist(), lyrics,
				clock.positionAt(nowNanos) + offsetMs + trackOffsetMs, clock.isPlaying(),
				(nowNanos - trackStartNanos) / 1_000_000.0);
	}

	private void startTrack(NowPlaying np, long nowNanos) {
		trackKey = np.trackKey();
		track = np;
		trackStartNanos = nowNanos;
		trackOffsetMs = 0;
		recordQueue = null;
		recordedLyrics = null;
		lastSampleNanos = Long.MIN_VALUE;
		clock.reset();
		startFetch(np);
	}

	private void startFetch(NowPlaying np) {
		int generation = ++fetchGeneration;
		lyrics = null;
		status = Status.LOADING;

		CompletableFuture<LyricsResult> future;

		try {
			future = fetcher.apply(np);
		} catch (RuntimeException e) {
			status = Status.ERROR;
			return;
		}

		future.whenComplete((result, error) -> {
			if (generation != fetchGeneration) return; // track changed meanwhile

			if (error != null || result == null) {
				status = Status.ERROR;
				return;
			}

			switch (result.kind()) {
				case SYNCED -> {
					lyrics = result.lyrics();
					status = Status.SYNCED;
				}
				case ESTIMATED -> {
					lyrics = result.lyrics();
					status = Status.ESTIMATED;
				}
				case UNSYNCED -> status = Status.UNSYNCED;
				case INSTRUMENTAL -> status = Status.INSTRUMENTAL;
				case NOT_FOUND -> status = Status.NOT_FOUND;
			}
		});
	}

	private void clearTrack() {
		fetchGeneration++;
		recordQueue = null;
		recordedLyrics = null;
		trackKey = null;
		track = null;
		lyrics = null;
		status = Status.IDLE;
		clock.reset();
	}
}
