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

	public Snapshot snapshot(long nowNanos, long offsetMs) {
		NowPlaying t = track;

		if (t == null) {
			return new Snapshot(Status.IDLE, "", "", null, 0, false, 0);
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
		trackKey = null;
		track = null;
		lyrics = null;
		status = Status.IDLE;
		clock.reset();
	}
}
