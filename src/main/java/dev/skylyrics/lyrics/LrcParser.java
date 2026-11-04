package dev.skylyrics.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses LRC synced lyrics ("[01:23.45] some words"). */
public final class LrcParser {
	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
	/** Enhanced-LRC word timestamps like {@code <00:12.34>}. */
	private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

	private LrcParser() {
	}

	public static Lyrics parse(String lrc) {
		List<LyricLine> out = new ArrayList<>();
		if (lrc == null || lrc.isBlank()) return new Lyrics(out);

		long offset = 0;

		for (String raw : lrc.split("\\r?\\n")) {
			String line = raw.strip();
			if (line.isEmpty()) continue;

			Matcher offsetMatch = OFFSET.matcher(line);

			if (offsetMatch.find()) {
				offset = Long.parseLong(offsetMatch.group(1));
				continue;
			}

			Matcher m = TIME.matcher(line);
			List<Long> times = new ArrayList<>();
			int end = 0;

			while (m.find() && m.start() == end) {
				times.add(toMillis(m));
				end = m.end();
			}

			if (times.isEmpty()) continue;

			String text = WORD_TIME.matcher(line.substring(end)).replaceAll("").strip();

			for (long t : times) {
				out.add(new LyricLine(Math.max(0, t - offset), text));
			}
		}

		out.sort(Comparator.comparingLong(LyricLine::timeMs));
		return new Lyrics(out);
	}

	private static long toMillis(Matcher m) {
		long minutes = Long.parseLong(m.group(1));
		long seconds = Long.parseLong(m.group(2));
		String frac = m.group(3);
		long millis = 0;

		if (frac != null) {
			millis = switch (frac.length()) {
				case 1 -> Long.parseLong(frac) * 100;
				case 2 -> Long.parseLong(frac) * 10;
				default -> Long.parseLong(frac);
			};
		}

		return minutes * 60_000 + seconds * 1_000 + millis;
	}
}
