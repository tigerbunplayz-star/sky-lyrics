package dev.skylyrics.lyrics;

public record LyricLine(long timeMs, String text) {
	public LyricLine {
		text = text == null ? "" : text.strip();
	}

	/** No words to show: empty, or only symbols such as "♪" (instrumental break markers). */
	public boolean isBlank() {
		return text.codePoints().noneMatch(Character::isLetterOrDigit);
	}
}
