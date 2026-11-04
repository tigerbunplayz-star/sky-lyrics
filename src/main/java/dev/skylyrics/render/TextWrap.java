package dev.skylyrics.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Word-wraps text to a pixel width using a supplied width measurer. */
public final class TextWrap {
	private TextWrap() {
	}

	public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isBlank()) return out;

		StringBuilder line = new StringBuilder();

		for (String word : text.strip().split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;

			if (width.applyAsInt(candidate) <= maxWidth || line.isEmpty()) {
				line.setLength(0);
				line.append(candidate);
			} else {
				out.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}

		if (!line.isEmpty()) out.add(line.toString());
		return balance(out, maxWidth, width);
	}

	/** Avoid a lonely last word: if there are two lines, split them more evenly. */
	private static List<String> balance(List<String> lines, int maxWidth, ToIntFunction<String> width) {
		if (lines.size() != 2) return lines;

		String[] words = (lines.get(0) + " " + lines.get(1)).split(" ");
		List<String> best = lines;
		int bestDiff = Math.abs(width.applyAsInt(lines.get(0)) - width.applyAsInt(lines.get(1)));

		for (int cut = 1; cut < words.length; cut++) {
			String a = String.join(" ", java.util.Arrays.copyOfRange(words, 0, cut));
			String b = String.join(" ", java.util.Arrays.copyOfRange(words, cut, words.length));
			int wa = width.applyAsInt(a);
			int wb = width.applyAsInt(b);
			if (wa > maxWidth || wb > maxWidth) continue;
			int diff = Math.abs(wa - wb);

			if (diff < bestDiff) {
				bestDiff = diff;
				best = List.of(a, b);
			}
		}

		return best;
	}
}
