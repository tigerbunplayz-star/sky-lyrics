package dev.skylyrics.media;

/**
 * One reading of what the media player is doing.
 *
 * @param positionMs playback position at {@code sampleNanos}, or -1 when the player doesn't report one
 * @param durationMs track length, or 0 when unknown
 * @param sampleNanos {@link System#nanoTime()} when this reading was taken
 */
public record NowPlaying(String title, String artist, String album,
		long positionMs, long durationMs, boolean playing, long sampleNanos) {

	public NowPlaying {
		title = title == null ? "" : title.strip();
		artist = artist == null ? "" : artist.strip();
		album = album == null ? "" : album.strip();
	}

	/** Identifies the track, ignoring position and play state. */
	public String trackKey() {
		return title + '\u0001' + artist;
	}

	public boolean hasTitle() {
		return !title.isEmpty();
	}
}
