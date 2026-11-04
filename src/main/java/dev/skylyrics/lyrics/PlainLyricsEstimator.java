package dev.skylyrics.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gives plain (untimed) lyrics an estimated timing so they can still be shown: lines are
 * spread over the song after a short intro, longer lines get more time, and verse breaks
 * become short pauses. Not exact — the in-game sync key fixes it up while listening.
 */
public final class PlainLyricsEstimator {
	/** Section labels such as "[Refrén]", "(2x)" or "Ref.:" — not sung. */
	private static final Pattern LABEL = Pattern.compile("^(?:[\\[(].{0,30}[\\])]:?|(?i:ref(?:r[eé]n)?\\.?|chorus|verse|bridge|intro|outro)\\s*\\d*\\s*:?)$");
	private static final double BREAK_WEIGHT = 1.6;
	private static final double CHARS_PER_UNIT = 38.0;

	private PlainLyricsEstimator() {
	}

	public static Lyrics estimate(String plain, long durationMs) {
		List<String> texts = new ArrayList<>();
		List<Boolean> breakBefore = new ArrayList<>();
		boolean pendingBreak = false;

		if (plain != null) {
			for (String raw : plain.split("\\r?\\n")) {
				String line = raw.strip();

				if (line.isEmpty() || LABEL.matcher(line).matches()) {
					pendingBreak = !texts.isEmpty();
					continue;
				}

				texts.add(line);
				breakBefore.add(pendingBreak);
				pendingBreak = false;
			}
		}

		List<LyricLine> out = new ArrayList<>();
		if (texts.isEmpty()) return new Lyrics(out);

		double duration = durationMs > 0 ? durationMs : texts.size() * 3200.0 + 15_000;
		double start = clamp(duration * 0.07, 6_000, 18_000);
		double end = duration - clamp(duration * 0.06, 4_000, 15_000);

		if (end - start < texts.size() * 900.0) {
			start = Math.min(2_000, duration * 0.05);
			end = duration * 0.97;
		}

		double total = 0;

		for (int i = 0; i < texts.size(); i++) {
			total += weight(texts.get(i));
			if (breakBefore.get(i)) total += BREAK_WEIGHT;
		}

		double unit = (end - start) / total;
		double t = start;

		for (int i = 0; i < texts.size(); i++) {
			if (breakBefore.get(i)) {
				out.add(new LyricLine(Math.round(t), ""));
				t += BREAK_WEIGHT * unit;
			}

			out.add(new LyricLine(Math.round(t), texts.get(i)));
			t += weight(texts.get(i)) * unit;
		}

		return new Lyrics(out);
	}

	private static double weight(String line) {
		return 1.0 + line.length() / CHARS_PER_UNIT;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
