package dev.skylyrics.lyrics;

public record LyricLine(long timeMs, String text) {
	public LyricLine {
		text = text == null ? "" : text.strip();
	}

	public boolean isBlank() {
		return text.isEmpty();
	}
}
