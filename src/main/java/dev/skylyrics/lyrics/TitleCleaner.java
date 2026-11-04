package dev.skylyrics.lyrics;

import java.util.Locale;
import java.util.regex.Pattern;

/** Simplifies track titles/artists so lyric lookups still match ("Song - 2011 Remaster" → "Song"). */
public final class TitleCleaner {
	private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
	private static final Pattern DASH_SUFFIX = Pattern.compile("\\s+[-\u2013\u2014]\\s+.*$");
	private static final Pattern ARTIST_SPLIT = Pattern.compile(
			"\\s*(?:,|;|&|\\s+x\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+with\\s+)\\s*",
			Pattern.CASE_INSENSITIVE);

	private TitleCleaner() {
	}

	public static String cleanTitle(String title) {
		if (title == null) return "";
		String t = DASH_SUFFIX.matcher(title).replaceAll("");
		t = BRACKETS.matcher(t).replaceAll("");
		t = t.strip();
		return t.isEmpty() ? title.strip() : t;
	}

	public static String primaryArtist(String artist) {
		if (artist == null) return "";
		String[] parts = ARTIST_SPLIT.split(artist.strip(), 2);
		return parts.length == 0 ? artist.strip() : parts[0].strip();
	}

	/** "Árvíztűrő" → "Arvizturo". */
	public static String stripDiacritics(String s) {
		if (s == null) return "";
		return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

	/** Lowercase, no accents, letters and digits only — for comparing names loosely. */
	public static String normalize(String s) {
		return s == null ? "" : stripDiacritics(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}

	/** Whether two artist strings plausibly name the same artist. */
	public static boolean artistMatches(String a, String b) {
		String na = normalize(primaryArtist(a));
		String nb = normalize(primaryArtist(b));
		if (na.isEmpty() || nb.isEmpty()) return false;
		return na.equals(nb) || normalize(b).contains(na) || normalize(a).contains(nb);
	}
}
