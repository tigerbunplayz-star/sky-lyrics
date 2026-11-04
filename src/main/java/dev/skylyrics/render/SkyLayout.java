package dev.skylyrics.render;

import java.util.ArrayList;
import java.util.List;

import dev.skylyrics.LyricsEngine.Snapshot;
import dev.skylyrics.LyricsEngine.Status;
import dev.skylyrics.lyrics.LyricLine;
import dev.skylyrics.lyrics.Lyrics;

/**
 * Decides what text is in the sky at a moment and how it is animated.
 * Pure math (no Minecraft classes) so it can be unit tested.
 */
public final class SkyLayout {
	public enum Style { LYRIC, DIM, TITLE, SUB }

	/**
	 * @param sung  karaoke progress through the line (0..1), or -1 for none
	 * @param y     vertical position in line heights, positive is down
	 * @param scale size relative to a lyric line
	 */
	public record Row(String text, Style style, float alpha, float scale, float y, float sung) {
	}

	static final double LINE_IN_MS = 320;
	static final double TITLE_CARD_MS = 5500;
	static final double FADE_MS = 600;
	static final double LINGER_MS = 4500;

	private SkyLayout() {
	}

	public static List<Row> compute(Snapshot s, boolean showNextLine, boolean karaoke) {
		List<Row> rows = new ArrayList<>();
		if (s.status() == Status.IDLE) return rows;

		float pauseDim = s.playing() ? 1f : 0.55f;

		if (s.status() == Status.SYNCED && s.lyrics() != null && !s.lyrics().isEmpty()) {
			Lyrics lyrics = s.lyrics();
			double pos = s.positionMs();
			int idx = lyrics.indexAt(pos);
			long first = lyrics.firstLineTime();

			// Intro: title card until the first line is about to start.
			if (first >= 0 && pos < first) {
				float alpha = (float) (clamp01(s.msSinceTrackStart() / FADE_MS) * clamp01((first - pos) / FADE_MS));
				addTitleCard(rows, s, null, alpha * pauseDim);
				if (showNextLine && first - pos < 2500) {
					LyricLine next = lyrics.get(lyrics.indexAt(first));
					if (next != null) rows.add(new Row(next.text(), Style.DIM, 0.35f * pauseDim, 0.65f, 1.3f, -1));
				}
				return rows;
			}

			addLyricRows(rows, lyrics, idx, pos, showNextLine, karaoke, pauseDim);
			return rows;
		}

		// Not synced: show a title card for a few seconds, then get out of the way.
		double t = s.msSinceTrackStart();
		if (t > TITLE_CARD_MS + FADE_MS) return rows;

		float alpha = (float) (clamp01(t / FADE_MS) * clamp01((TITLE_CARD_MS + FADE_MS - t) / FADE_MS));
		String note = switch (s.status()) {
			case LOADING -> "finding lyrics…";
			case UNSYNCED -> "only unsynced lyrics exist for this song";
			case INSTRUMENTAL -> "♪ instrumental ♪";
			case NOT_FOUND -> "no synced lyrics found";
			case ERROR -> "couldn't reach the lyrics server";
			default -> null;
		};
		addTitleCard(rows, s, note, alpha * pauseDim);
		return rows;
	}

	private static void addLyricRows(List<Row> rows, Lyrics lyrics, int idx, double pos,
			boolean showNextLine, boolean karaoke, float pauseDim) {
		LyricLine cur = lyrics.get(idx);
		if (cur == null) return;

		LyricLine nextLine = lyrics.get(idx + 1);
		double start = cur.timeMs();
		double gap = nextLine != null ? nextLine.timeMs() - start : 6000;
		double since = pos - start;
		float in = (float) clamp01(since / LINE_IN_MS);
		float eased = easeOutCubic(in);

		// Previous line drifts up and fades out while the new one comes in.
		LyricLine prev = lyrics.get(idx - 1);
		if (prev != null && !prev.isBlank() && in < 1f) {
			rows.add(new Row(prev.text(), Style.DIM, (1f - eased) * 0.7f * pauseDim, 0.85f, -1.15f - eased * 0.5f, -1));
		}

		if (cur.isBlank()) {
			// Instrumental break: show a little note if the break is long.
			if (gap > 3500) {
				rows.add(new Row("♪", Style.DIM, eased * 0.6f * pauseDim, 1f, 0f, -1));
			}
		} else {
			double singDuration = Math.min(gap, Math.max(1200, cur.text().length() * 85.0));
			float sung = karaoke ? (float) clamp01(since / singDuration) : -1f;

			// A line followed by a long silence fades away instead of hanging forever.
			double lingerEnd = singDuration + LINGER_MS;
			float linger = (float) clamp01(1 - (since - lingerEnd) / FADE_MS);

			float scale = 0.9f + 0.1f * easeOutBack(in);
			rows.add(new Row(cur.text(), Style.LYRIC, eased * linger * pauseDim, scale, (1f - eased) * 0.6f, sung));
		}

		if (showNextLine && nextLine != null && !nextLine.isBlank() && gap < 12000) {
			rows.add(new Row(nextLine.text(), Style.DIM, 0.35f * eased * pauseDim, 0.65f, 1.3f, -1));
		}
	}

	private static void addTitleCard(List<Row> rows, Snapshot s, String note, float alpha) {
		if (alpha <= 0.01f) return;
		rows.add(new Row(s.title(), Style.TITLE, alpha, 1f, 0f, -1));
		if (!s.artist().isEmpty()) rows.add(new Row(s.artist(), Style.SUB, alpha * 0.9f, 0.6f, 1.15f, -1));
		if (note != null) rows.add(new Row(note, Style.DIM, alpha * 0.7f, 0.45f, 1.95f, -1));
	}

	static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	static float easeOutCubic(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	static float easeOutBack(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}
}
