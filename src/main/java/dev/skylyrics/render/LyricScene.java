package dev.skylyrics.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import dev.skylyrics.LyricsEngine.Snapshot;
import dev.skylyrics.LyricsEngine.Status;
import dev.skylyrics.lyrics.LyricLine;
import dev.skylyrics.lyrics.Lyrics;

/**
 * Decides which word groups are floating around the player at a moment, when each one
 * popped up, and where it sits. Lines are cut into short groups ("IT GREED") that appear
 * one after another as they are sung, scattered left/right, near/far, high/low.
 * Pure math (no Minecraft classes) so it can be unit tested.
 */
public final class LyricScene {
	public enum Kind { LYRIC, TITLE, ARTIST, NOTE }

	/**
	 * Where a group sits relative to where the player was looking when it appeared.
	 *
	 * @param yawOffset  degrees left (-) / right (+) of the view direction
	 * @param distance   blocks away
	 * @param height     blocks above eye level
	 * @param size       size multiplier
	 * @param faceTurn   extra degrees the group is turned around its own vertical axis
	 * @param roll       tilt in degrees
	 * @param phase      0..2π, de-synchronises the idle bobbing
	 */
	public record Placement(float yawOffset, float distance, float height, float size,
			float faceTurn, float roll, float phase) {
	}

	/**
	 * @param key       stable id; the renderer anchors each group in the world once, by key
	 * @param appearMs  ms since the group popped up
	 * @param exit      0 while shown, rising to 1 as it disappears
	 * @param color     palette slot
	 */
	public record Chunk(Kind kind, String text, long key, double appearMs, float exit,
			Placement place, int color) {
	}

	public record Settings(int maxChunkChars, int maxChunkWords, float spreadDeg,
			float distanceMin, float distanceMax, float heightMin, float heightMax,
			double holdMs, boolean uppercase, boolean showEstimated, float animSpeed) {
		public static Settings defaults() {
			return new Settings(12, 3, 36f, 24f, 36f, -1.0f, 8.0f, 2200, true, true, 1f);
		}

		double exitMs() {
			return EXIT_MS / Math.max(0.1f, animSpeed);
		}
	}

	static final double EXIT_MS = 550;
	static final double TITLE_CARD_MS = 6000;
	static final double LINGER_MS = 6000;
	static final long TITLE_KEY = -1;
	static final long ARTIST_KEY = -2;
	static final long NOTE_KEY = -3;

	private LyricScene() {
	}

	public static List<Chunk> compute(Snapshot s, Settings st) {
		List<Chunk> out = new ArrayList<>();
		if (s.status() == Status.IDLE) return out;

		long trackSeed = (s.title() + "\u0001" + s.artist()).hashCode();

		boolean estimated = s.status() == Status.ESTIMATED;
		boolean timed = s.status() == Status.SYNCED || (estimated && st.showEstimated());

		if (timed && s.lyrics() != null && !s.lyrics().isEmpty()) {
			Lyrics lyrics = s.lyrics();
			double pos = s.positionMs();
			long first = lyrics.firstLineTime();

			// Title card during the intro, gone just before the first line.
			if (first >= 0) {
				double titleExitAt = Math.min(first - st.exitMs(), s.positionMs() - s.msSinceTrackStart() + 12_000);
				float exit = (float) clamp01((pos - titleExitAt) / st.exitMs());
				String note = estimated ? "timing is estimated \u2013 press the sync key (; or \u00C9) when a line starts" : null;
				if (exit < 1f) addTitleCard(out, s, note, s.msSinceTrackStart(), exit, st);
			}

			int idx = lyrics.indexAt(pos);

			for (int i = Math.max(0, idx - 3); i <= idx; i++) {
				addLine(out, lyrics, i, pos, trackSeed, st);
			}

			return out;
		}

		// No synced lyrics: show the title card for a few seconds with a short note.
		double t = s.msSinceTrackStart();
		float exit = (float) clamp01((t - TITLE_CARD_MS) / st.exitMs());
		if (exit >= 1f) return out;

		String note = switch (s.status()) {
			case LOADING -> "finding lyrics…";
			case UNSYNCED, ESTIMATED -> "only unsynced lyrics exist for this song";
			case INSTRUMENTAL -> "♪ instrumental ♪";
			case NOT_FOUND -> "no synced lyrics found";
			case ERROR -> "couldn't reach the lyrics server";
			default -> null;
		};
		addTitleCard(out, s, note, t, exit, st);
		return out;
	}

	private static void addLine(List<Chunk> out, Lyrics lyrics, int i, double pos, long trackSeed, Settings st) {
		LyricLine line = lyrics.get(i);
		if (line == null || line.isBlank()) return;

		double start = line.timeMs();
		LyricLine next = lyrics.get(i + 1);
		double nextStart = next != null ? next.timeMs() : start + LINGER_MS;
		double gap = Math.max(300, nextStart - start);
		String text = st.uppercase() ? line.text().toUpperCase(Locale.ROOT) : line.text();
		List<String> groups = splitChunks(text, st.maxChunkChars(), st.maxChunkWords());
		if (groups.isEmpty()) return;

		int totalChars = 0;
		for (String g : groups) totalChars += g.length() + 1;
		double singMs = Math.min(gap * 0.85, Math.max(900, totalChars * 70.0));

		// Stays a little after the next line starts, but never lingers through long breaks,
		// and is always gone once the line after next begins.
		double exitAt = Math.min(nextStart + st.holdMs(), start + singMs + LINGER_MS);
		LyricLine afterNext = lyrics.get(i + 2);
		if (afterNext != null) exitAt = Math.min(exitAt, afterNext.timeMs());

		int offset = 0;

		for (int j = 0; j < groups.size(); j++) {
			String g = groups.get(j);
			double appearAt = start + singMs * offset / totalChars;
			offset += g.length() + 1;

			double appearMs = pos - appearAt;
			if (appearMs < 0) continue;

			float exit = (float) clamp01((pos - exitAt - j * 70.0 / Math.max(0.1f, st.animSpeed())) / st.exitMs());
			if (exit >= 1f) continue;

			Placement place = place(trackSeed, i, j, groups.size(), st);
			out.add(new Chunk(Kind.LYRIC, g, (long) i * 64 + j, appearMs, exit, place, i + j));
		}
	}

	private static void addTitleCard(List<Chunk> out, Snapshot s, String note, double appearMs, float exit, Settings st) {
		String title = st.uppercase() ? s.title().toUpperCase(Locale.ROOT) : s.title();
		if (title.length() > 28) title = title.substring(0, 27).strip() + "…";
		float titleSize = (float) Math.max(0.55, Math.min(1.15, 13.0 / Math.max(1, title.length()) * 1.1));

		out.add(new Chunk(Kind.TITLE, title, TITLE_KEY, appearMs, exit,
				new Placement(0f, 30f, 5.3f, titleSize, 0f, 0f, 0.3f), 0));

		if (!s.artist().isEmpty()) {
			out.add(new Chunk(Kind.ARTIST, s.artist(), ARTIST_KEY, appearMs - 250, exit,
					new Placement(0f, 30f, 2.5f, 0.5f, 0f, 0f, 1.7f), 3));
		}

		if (note != null) {
			out.add(new Chunk(Kind.NOTE, note, NOTE_KEY, appearMs - 500, exit,
					new Placement(0f, 30f, 0.8f, 0.45f, 0f, 0f, 2.9f), 0));
		}
	}

	/** Scatter the groups of one line across the view: alternating sides, heights and depths. */
	public static Placement place(long trackSeed, int lineIndex, int j, int n, Settings st) {
		SplittableRandom r = new SplittableRandom(trackSeed * 1_000_003L + lineIndex * 7919L + j * 104_729L);
		float dir = (lineIndex % 2 == 0) ? 1f : -1f;
		float base = n == 1 ? (float) r.nextDouble(-0.55, 0.55) : -1f + 2f * (j + 0.5f) / n;
		float yaw = dir * base * st.spreadDeg() + (float) r.nextDouble(-6, 6);

		float midHeight = (st.heightMin() + st.heightMax()) / 2f;
		boolean high = ((j + lineIndex) % 2) == 0;
		float height = high ? (float) r.nextDouble(midHeight, st.heightMax() + 1e-3)
				: (float) r.nextDouble(st.heightMin(), midHeight + 1e-3);

		float distance = (float) r.nextDouble(st.distanceMin(), st.distanceMax() + 1e-3);
		float size = (float) r.nextDouble(0.85, 1.22);
		float turn = (float) r.nextDouble(-10, 10);
		float roll = (float) r.nextDouble(-4, 4);
		float phase = (float) r.nextDouble(0, Math.PI * 2);
		return new Placement(yaw, distance, height, size, turn, roll, phase);
	}

	/** "IT'S ALL ABOUT THE MONEY" → ["IT'S ALL", "ABOUT THE", "MONEY"]. */
	public static List<String> splitChunks(String text, int maxChars, int maxWords) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isBlank()) return out;

		StringBuilder cur = new StringBuilder();
		int words = 0;

		for (String w : text.strip().split("\\s+")) {
			boolean fits = cur.isEmpty() || (cur.length() + 1 + w.length() <= maxChars && words < maxWords);

			if (!fits) {
				out.add(cur.toString());
				cur.setLength(0);
				words = 0;
			}

			if (!cur.isEmpty()) cur.append(' ');
			cur.append(w);
			words++;

			// Natural break after punctuation.
			char last = w.charAt(w.length() - 1);
			if (last == ',' || last == '.' || last == '!' || last == '?' || last == ';' || last == ':') {
				out.add(cur.toString());
				cur.setLength(0);
				words = 0;
			}
		}

		if (!cur.isEmpty()) out.add(cur.toString());
		return out;
	}

	static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}
}
