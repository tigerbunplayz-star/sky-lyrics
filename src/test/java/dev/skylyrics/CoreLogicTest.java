package dev.skylyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import dev.skylyrics.LyricsEngine.Snapshot;
import dev.skylyrics.LyricsEngine.Status;
import dev.skylyrics.lyrics.LrcParser;
import dev.skylyrics.lyrics.Lyrics;
import dev.skylyrics.lyrics.LyricsResult;
import dev.skylyrics.lyrics.TitleCleaner;
import dev.skylyrics.media.NowPlaying;
import dev.skylyrics.media.PlaybackClock;
import dev.skylyrics.render.SkyLayout;
import dev.skylyrics.render.TextWrap;

class CoreLogicTest {
	private static final long MS = 1_000_000L;

	private static final String LRC = """
			[ar:Someone]
			[00:01.00]
			[00:12.50]First line here
			[00:15.2]Second <00:15.80>line
			[00:18.123][00:40.00]Chorus line
			not a lyric line
			[00:21.00]
			""";

	@Test
	void parsesLrc() {
		Lyrics l = LrcParser.parse(LRC);
		assertEquals(6, l.size());
		assertEquals(1000, l.get(0).timeMs());
		assertTrue(l.get(0).isBlank());
		assertEquals(12500, l.get(1).timeMs());
		assertEquals("First line here", l.get(1).text());
		assertEquals(15200, l.get(2).timeMs());
		assertEquals("Second line", l.get(2).text());
		assertEquals(18123, l.get(3).timeMs());
		assertEquals(21000, l.get(4).timeMs());
		assertEquals(40000, l.get(5).timeMs());
		assertEquals("Chorus line", l.get(5).text());
		assertEquals(12500, l.firstLineTime());
	}

	@Test
	void appliesOffsetTag() {
		Lyrics l = LrcParser.parse("[offset:+500]\n[00:10.00]Hi");
		assertEquals(9500, l.get(0).timeMs());
	}

	@Test
	void findsCurrentLine() {
		Lyrics l = LrcParser.parse(LRC);
		assertEquals(-1, l.indexAt(500));
		assertEquals(0, l.indexAt(1000));
		assertEquals(1, l.indexAt(12500));
		assertEquals(1, l.indexAt(15199));
		assertEquals(2, l.indexAt(15200));
		assertEquals(5, l.indexAt(999_999));
	}

	@Test
	void cleansTitles() {
		assertEquals("Here Comes the Sun", TitleCleaner.cleanTitle("Here Comes the Sun - Remastered 2009"));
		assertEquals("Song", TitleCleaner.cleanTitle("Song (feat. Someone) [Live]"));
		assertEquals("(Untitled)", TitleCleaner.cleanTitle("(Untitled)"));
		assertEquals("Daft Punk", TitleCleaner.primaryArtist("Daft Punk, Pharrell Williams"));
		assertEquals("A", TitleCleaner.primaryArtist("A feat. B"));
		assertEquals("Simon", TitleCleaner.primaryArtist("Simon & Garfunkel"));
	}

	@Test
	void clockInterpolatesAndSmoothsDrift() {
		PlaybackClock c = new PlaybackClock();
		c.sync(10_000, true, 0);
		assertEquals(10_500, c.positionAt(500 * MS), 0.001);

		// small drift: corrected gradually, no jump
		c.sync(10_600, true, 500 * MS);
		double p = c.positionAt(500 * MS);
		assertTrue(p > 10_500 && p < 10_600, "drift should be partially corrected, was " + p);

		// seek: applied immediately
		c.sync(60_000, true, 600 * MS);
		assertEquals(60_000, c.positionAt(600 * MS), 0.001);

		// pause freezes
		c.sync(61_000, false, 1_600 * MS);
		assertEquals(61_000, c.positionAt(5_000 * MS), 0.001);
	}

	@Test
	void clockRunsFreeWithoutTimeline() {
		PlaybackClock c = new PlaybackClock();
		c.runFree(true, 0);
		assertEquals(2_000, c.positionAt(2_000 * MS), 0.001);
		c.runFree(false, 2_000 * MS);
		assertEquals(2_000, c.positionAt(9_000 * MS), 0.001);
		c.runFree(true, 9_000 * MS);
		assertEquals(3_000, c.positionAt(10_000 * MS), 0.001);
	}

	@Test
	void wrapsAndBalances() {
		List<String> lines = TextWrap.wrap("one two three four five six seven", 60, s -> s.length() * 4);
		assertTrue(lines.size() >= 2);
		for (String line : lines) assertTrue(line.length() * 4 <= 60 || !line.contains(" "));
		assertEquals(List.of("short"), TextWrap.wrap("short", 60, s -> s.length() * 4));
		assertTrue(TextWrap.wrap("   ", 60, String::length).isEmpty());
	}

	@Test
	void engineFetchesOncePerTrackAndReportsLyrics() {
		int[] calls = {0};
		Lyrics lyrics = LrcParser.parse(LRC);
		LyricsEngine e = new LyricsEngine(np -> {
			calls[0]++;
			return CompletableFuture.completedFuture(LyricsResult.synced(lyrics));
		});

		e.update(new NowPlaying("Song", "Artist", "", 13_000, 200_000, true, 0), 0);
		e.update(new NowPlaying("Song", "Artist", "", 13_400, 200_000, true, 400 * MS), 400 * MS);
		assertEquals(1, calls[0]);

		Snapshot s = e.snapshot(400 * MS, 0);
		assertEquals(Status.SYNCED, s.status());
		assertEquals(13_400, s.positionMs(), 50);

		List<SkyLayout.Row> rows = SkyLayout.compute(s, true, true);
		assertTrue(rows.stream().anyMatch(r -> r.text().equals("First line here") && r.style() == SkyLayout.Style.LYRIC));
		assertTrue(rows.stream().anyMatch(r -> r.text().equals("Second line") && r.style() == SkyLayout.Style.DIM));

		e.update(new NowPlaying("Other", "Artist", "", 0, 100_000, true, 900 * MS), 900 * MS);
		assertEquals(2, calls[0]);

		e.update(null, MS * 1000);
		assertEquals(Status.IDLE, e.snapshot(MS * 1000, 0).status());
		assertTrue(SkyLayout.compute(e.snapshot(MS * 1000, 0), true, true).isEmpty());
	}

	@Test
	void layoutShowsTitleCardThenHidesWhenNoLyrics() {
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(LyricsResult.of(LyricsResult.Kind.NOT_FOUND)));
		e.update(new NowPlaying("Song", "Artist", "", 0, 100_000, true, 0), 0);

		List<SkyLayout.Row> early = SkyLayout.compute(e.snapshot(2_000 * MS, 0), true, true);
		assertTrue(early.stream().anyMatch(r -> r.style() == SkyLayout.Style.TITLE && r.text().equals("Song")));
		assertTrue(early.stream().anyMatch(r -> r.text().contains("no synced lyrics")));

		assertTrue(SkyLayout.compute(e.snapshot(20_000 * MS, 0), true, true).isEmpty());
	}

	@Test
	void layoutIntroBeforeFirstLineAndKaraokeProgress() {
		Lyrics lyrics = LrcParser.parse(LRC);
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(LyricsResult.synced(lyrics)));

		e.update(new NowPlaying("Song", "Artist", "", 5_000, 200_000, true, 0), 0);
		List<SkyLayout.Row> intro = SkyLayout.compute(e.snapshot(2_000 * MS, 0), true, true);
		assertTrue(intro.stream().anyMatch(r -> r.style() == SkyLayout.Style.TITLE));

		e.update(new NowPlaying("Song", "Artist", "", 12_600, 200_000, true, 3_000 * MS), 3_000 * MS);
		SkyLayout.Row start = lyricRow(SkyLayout.compute(e.snapshot(3_000 * MS, 0), true, true));
		SkyLayout.Row later = lyricRow(SkyLayout.compute(e.snapshot(4_500 * MS, 0), true, true));
		assertTrue(start.sung() < later.sung(), "karaoke should advance");
		assertTrue(later.alpha() > 0.9f);

		assertFalse(SkyLayout.compute(e.snapshot(4_500 * MS, 0), true, false).stream()
				.anyMatch(r -> r.style() == SkyLayout.Style.LYRIC && r.sung() >= 0));
	}

	private static SkyLayout.Row lyricRow(List<SkyLayout.Row> rows) {
		return rows.stream().filter(r -> r.style() == SkyLayout.Style.LYRIC).findFirst().orElseThrow();
	}
}
