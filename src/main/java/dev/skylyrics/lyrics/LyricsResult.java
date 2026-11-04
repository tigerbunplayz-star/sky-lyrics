package dev.skylyrics.lyrics;

public record LyricsResult(Kind kind, Lyrics lyrics) {
	public enum Kind { SYNCED, UNSYNCED, INSTRUMENTAL, NOT_FOUND }

	public static LyricsResult synced(Lyrics lyrics) {
		return new LyricsResult(Kind.SYNCED, lyrics);
	}

	public static LyricsResult of(Kind kind) {
		return new LyricsResult(kind, null);
	}
}
