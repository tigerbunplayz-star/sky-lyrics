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
import dev.skylyrics.render.Colors;
import dev.skylyrics.render.LyricScene;

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
	void musicNoteLinesCountAsBreaks() {
		Lyrics l = LrcParser.parse("[00:00.00]\u266A\n[00:12.00]Words here");
		assertTrue(l.get(0).isBlank());
		assertEquals(12_000, l.firstLineTime());
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

		List<LyricScene.Chunk> chunks = LyricScene.compute(s, LyricScene.Settings.defaults());
		assertTrue(chunks.stream().anyMatch(c -> c.kind() == LyricScene.Kind.LYRIC && c.text().equals("FIRST LINE")),
				"first group of the current line should be up: " + chunks);

		e.update(new NowPlaying("Other", "Artist", "", 0, 100_000, true, 900 * MS), 900 * MS);
		assertEquals(2, calls[0]);

		e.update(null, MS * 1000);
		assertEquals(Status.IDLE, e.snapshot(MS * 1000, 0).status());
		assertTrue(LyricScene.compute(e.snapshot(MS * 1000, 0), LyricScene.Settings.defaults()).isEmpty());
	}

	@Test
	void titleCardThenNothingWhenNoLyrics() {
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(LyricsResult.of(LyricsResult.Kind.NOT_FOUND)));
		e.update(new NowPlaying("Song", "Artist", "", 0, 100_000, true, 0), 0);
		LyricScene.Settings st = LyricScene.Settings.defaults();

		List<LyricScene.Chunk> early = LyricScene.compute(e.snapshot(2_000 * MS, 0), st);
		assertTrue(early.stream().anyMatch(c -> c.kind() == LyricScene.Kind.TITLE && c.text().equals("SONG")));
		assertTrue(early.stream().anyMatch(c -> c.kind() == LyricScene.Kind.NOTE && c.text().contains("no synced lyrics")));
		assertTrue(LyricScene.compute(e.snapshot(20_000 * MS, 0), st).isEmpty());
	}

	@Test
	void groupsAppearOneByOneThenLeave() {
		Lyrics lyrics = LrcParser.parse("[00:10.00]Isn't this it, greed is all around\n[00:14.00]Next one\n[00:18.00]Third\n[00:30.00]Fourth");
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(LyricsResult.synced(lyrics)));
		LyricScene.Settings st = LyricScene.Settings.defaults();

		e.update(new NowPlaying("Song", "Artist", "", 10_050, 200_000, true, 0), 0);
		List<String> start = texts(LyricScene.compute(e.snapshot(0, 0), st));
		assertEquals(List.of("ISN'T THIS"), start);

		List<String> later = texts(LyricScene.compute(e.snapshot(2_500 * MS, 0), st));
		assertTrue(later.containsAll(List.of("ISN'T THIS", "IT,", "GREED IS ALL", "AROUND")), "all groups by now: " + later);

		// Still there shortly after the next line starts (hold), gone once line 3 starts.
		assertTrue(texts(LyricScene.compute(e.snapshot(4_500 * MS, 0), st)).contains("ISN'T THIS"));
		assertFalse(texts(LyricScene.compute(e.snapshot(8_700 * MS, 0), st)).contains("ISN'T THIS"));
	}

	@Test
	void groupsAreScatteredAndStable() {
		LyricScene.Settings st = LyricScene.Settings.defaults();
		LyricScene.Placement a = LyricScene.place(42, 3, 0, 3, st);
		LyricScene.Placement b = LyricScene.place(42, 3, 2, 3, st);
		assertEquals(a, LyricScene.place(42, 3, 0, 3, st));
		assertTrue(Math.signum(a.yawOffset()) != Math.signum(b.yawOffset()), "first and last group on opposite sides");
		assertTrue(a.height() != b.height());
		for (int i = 0; i < 50; i++) {
			LyricScene.Placement p = LyricScene.place(i, i, i % 3, 3, st);
			assertTrue(p.distance() >= st.distanceMin() && p.distance() <= st.distanceMax() + 0.01f);
			assertTrue(p.height() >= st.heightMin() && p.height() <= st.heightMax() + 0.01f);
			assertTrue(Math.abs(p.yawOffset()) <= st.spreadDeg() + 6.01f);
		}
	}

	@Test
	void splitsIntoShortGroups() {
		assertEquals(List.of("IT'S ALL", "ABOUT THE", "MONEY"), LyricScene.splitChunks("IT'S ALL ABOUT THE MONEY", 10, 3));
		assertEquals(List.of("HELLO,", "WORLD"), LyricScene.splitChunks("HELLO, WORLD", 12, 3));
		assertEquals(List.of("TÜKÖRFÚRÓGÉPEK"), LyricScene.splitChunks("TÜKÖRFÚRÓGÉPEK", 12, 3));
	}

	@Test
	void hungarianTextSurvives() {
		assertEquals("ÁRVÍZTŰRŐ TÜKÖRFÚRÓGÉP", "Árvíztűrő tükörfúrógép".toUpperCase(java.util.Locale.ROOT));
		assertEquals("Arvizturo tukorfurogep", TitleCleaner.stripDiacritics("Árvíztűrő tükörfúrógép"));
		assertTrue(TitleCleaner.artistMatches("Halott Pénz", "Halott Penz"));
		assertTrue(TitleCleaner.artistMatches("Azahriah, DESH", "azahriah"));
		assertFalse(TitleCleaner.artistMatches("Azahriah", "Wellhello"));
		Lyrics l = LrcParser.parse("[00:01.00]Szól a dal, ő is énekel");
		assertEquals("Szól a dal, ő is énekel", l.get(0).text());
	}

	@Test
	void colourMath() {
		float[] hsv = Colors.toHsv(0xFF0000);
		assertEquals(0.0, hsv[0], 1e-4);
		assertEquals(0xFF0000, Colors.fromHsv(hsv[0], hsv[1], hsv[2]));
		assertEquals(0x00FF00, Colors.fromHsv(1f / 3f, 1f, 1f));
		assertEquals(0x800000, Colors.scale(0xFF0000, 0.5f));
		assertEquals(0x808080, Colors.mix(0x000000, 0xFFFFFF, 0.5f));
	}

	@Test
	void estimatesTimingForPlainLyrics() {
		String plain = "[Verse 1]\nA sör, a bor, a pálinka\nMind jó barát\n\nRefrén:\nEgy hosszabb sor itt a refrénben ami tovább tart\nVége";
		Lyrics l = dev.skylyrics.lyrics.PlainLyricsEstimator.estimate(plain, 200_000);
		List<String> texts = l.lines().stream().map(dev.skylyrics.lyrics.LyricLine::text).toList();
		assertEquals(List.of("A sör, a bor, a pálinka", "Mind jó barát", "", "Egy hosszabb sor itt a refrénben ami tovább tart", "Vége"), texts);
		assertTrue(l.get(0).timeMs() >= 6_000 && l.get(0).timeMs() <= 18_000, "short intro before the first line");
		for (int i = 1; i < l.size(); i++) assertTrue(l.get(i).timeMs() > l.get(i - 1).timeMs());
		assertTrue(l.get(l.size() - 1).timeMs() < 200_000 - 4_000, "last line before the outro");
		// longer lines get more time
		long longGap = l.get(4).timeMs() - l.get(3).timeMs();
		long shortGap = l.get(2).timeMs() - l.get(1).timeMs();
		assertTrue(longGap > shortGap);
		assertTrue(dev.skylyrics.lyrics.PlainLyricsEstimator.estimate("\n\n", 100_000).isEmpty());
	}

	@Test
	void estimatedLyricsAreShownAndSyncKeyShiftsThem() {
		Lyrics lyrics = dev.skylyrics.lyrics.PlainLyricsEstimator.estimate("Egy\nKettő\nHárom\nNégy", 120_000);
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(new LyricsResult(LyricsResult.Kind.ESTIMATED, lyrics)));
		long t1 = lyrics.get(1).timeMs();

		e.update(new NowPlaying("Song", "Artist", "", t1 + 50, 120_000, true, 0), 0);
		assertEquals(Status.ESTIMATED, e.snapshot(0, 0).status());
		assertTrue(texts(LyricScene.compute(e.snapshot(0, 0), LyricScene.Settings.defaults())).contains("KETTŐ"));

		// Shown nothing when estimates are switched off.
		LyricScene.Settings off = new LyricScene.Settings(12, 3, 36f, 24f, 36f, -1f, 8f, 2200, true, false, 1f);
		assertTrue(texts(LyricScene.compute(e.snapshot(0, 0), off)).isEmpty());

		// Player presses sync 1.2 s before the estimated start of line 2: it should line up now.
		long t2 = lyrics.get(2).timeMs();
		e.update(new NowPlaying("Song", "Artist", "", t2 - 1200, 120_000, true, 1000 * MS), 1000 * MS);
		Long shift = e.syncLineStartsNow(1000 * MS, 0);
		assertEquals(1200L, shift.longValue(), 30);
		assertEquals(t2, e.snapshot(1000 * MS, 0).positionMs(), 30);

		// A new song starts with no extra shift.
		e.update(new NowPlaying("Other", "Artist", "", 5_000, 120_000, true, 2000 * MS), 2000 * MS);
		assertEquals(5_000, e.snapshot(2000 * MS, 0).positionMs(), 30);
	}

	@Test
	void originalColorsMatchPreviousLook() {
		int[] palette = {0xFF5C8A, 0x4DD8FF};
		dev.skylyrics.render.ColorScheme cs = new dev.skylyrics.render.ColorScheme(false, palette, 0, 0, true, false);
		assertEquals(0x4DD8FF, cs.base(1));
		assertEquals(0xFF5C8A, cs.base(2));
		float[] hsv = Colors.toHsv(0xFF5C8A);
		int expected = Colors.fromHsv(hsv[0] + 3 * 0.022f, hsv[1] * 0.92f, Math.min(1f, hsv[2] * 1.04f + 0.02f));
		assertEquals(expected, cs.letter(0, 3, 6, 0, 0));
	}

	@Test
	void customColorsFadeAcrossLetters() {
		dev.skylyrics.render.ColorScheme fade = new dev.skylyrics.render.ColorScheme(true, null, 0xFF0000, 0x0000FF, true, false);
		assertEquals(0xFF0000, fade.letter(5, 0, 5, 0, 0));
		assertEquals(0x0000FF, fade.letter(5, 4, 5, 0, 0));
		assertEquals(Colors.mix(0xFF0000, 0x0000FF, 0.5f), fade.letter(5, 2, 5, 0, 0));
		assertEquals(Colors.mix(0xFF0000, 0x0000FF, 0.5f), fade.base(7));

		dev.skylyrics.render.ColorScheme solid = new dev.skylyrics.render.ColorScheme(true, null, 0x00FF00, 0x0000FF, false, false);
		assertEquals(0x00FF00, solid.letter(0, 3, 5, 0, 0));
		assertEquals(0x00FF00, solid.base(0));
		assertEquals("#00FF00", dev.skylyrics.render.ColorScheme.hex(0x00FF00));
		assertEquals(0x12ABEF, dev.skylyrics.render.ColorScheme.parse("#12abef", 0));
		assertEquals(7, dev.skylyrics.render.ColorScheme.parse("nope", 7));
	}

	@Test
	void recorderTimesEachLineOnTap() {
		Lyrics plain = dev.skylyrics.lyrics.PlainLyricsEstimator.estimate("first made up line\nsecond one\n\nthird after a break", 120_000);
		LyricsEngine e = new LyricsEngine(np -> CompletableFuture.completedFuture(new LyricsResult(LyricsResult.Kind.ESTIMATED, plain)));
		e.update(new NowPlaying("Song", "Artist", "", 10_000, 120_000, true, 0), 0);

		assertTrue(e.canRecord());
		assertTrue(e.startRecording());
		assertEquals("first made up line", e.nextRecordLine());
		assertEquals(3, e.recordTotal());

		assertFalse(e.recordTap(2_000 * MS)); // song at 12.0 s
		assertFalse(e.recordTap(5_000 * MS)); // 15.0 s
		assertEquals("third after a break", e.nextRecordLine());
		assertTrue(e.recordTap(9_000 * MS)); // 19.0 s, last line

		Lyrics timed = e.stopRecording();
		assertEquals(3, timed.size());
		assertEquals(12_000 - LyricsEngine.REACTION_MS, timed.get(0).timeMs());
		assertEquals(15_000 - LyricsEngine.REACTION_MS, timed.get(1).timeMs());
		assertEquals(19_000 - LyricsEngine.REACTION_MS, timed.get(2).timeMs());
		assertFalse(e.isRecording());
		assertEquals(Status.SYNCED, e.snapshot(9_000 * MS, 0).status());
	}

	private static List<String> texts(List<LyricScene.Chunk> chunks) {
		return chunks.stream().filter(c -> c.kind() == LyricScene.Kind.LYRIC).map(LyricScene.Chunk::text).toList();
	}
}
