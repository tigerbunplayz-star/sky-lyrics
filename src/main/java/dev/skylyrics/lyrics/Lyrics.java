package dev.skylyrics.lyrics;

import java.util.List;

/** Time-sorted synced lyric lines. */
public final class Lyrics {
	private final List<LyricLine> lines;

	public Lyrics(List<LyricLine> lines) {
		this.lines = List.copyOf(lines);
	}

	public List<LyricLine> lines() {
		return lines;
	}

	public int size() {
		return lines.size();
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	public LyricLine get(int index) {
		return index >= 0 && index < lines.size() ? lines.get(index) : null;
	}

	/** Index of the line being sung at {@code posMs}, or -1 before the first line. */
	public int indexAt(double posMs) {
		int lo = 0;
		int hi = lines.size() - 1;
		int found = -1;

		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;

			if (lines.get(mid).timeMs() <= posMs) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}

		return found;
	}

	/** First non-blank line's start time, or -1 if there is none. */
	public long firstLineTime() {
		for (LyricLine line : lines) {
			if (!line.isBlank()) return line.timeMs();
		}

		return -1;
	}
}
