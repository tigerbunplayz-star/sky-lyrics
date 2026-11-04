package dev.skylyrics.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.skylyrics.media.NowPlaying;

class LocalLyricsTest {
	private static NowPlaying song(String title, String artist) {
		return new NowPlaying(title, artist, "", 0, 180_000, true, 0);
	}

	@Test
	void fileNamesAreSafe() {
		assertEquals("Saki & Baleszko - TOP 1.lrc", LocalLyrics.fileName("Saki & Baleszko", "TOP 1"));
		assertEquals("ACDC - What? Now.lrc".replace("?", ""), LocalLyrics.fileName("AC/DC", "What? Now"));
		assertEquals("[01:02.34]", LocalLyrics.timestamp(62_345));
	}

	@Test
	void findsTimedAndPlainFilesLoosely(@TempDir Path dir) throws Exception {
		LocalLyrics local = new LocalLyrics(dir);
		assertTrue(Files.exists(dir.resolve("README.txt")));
		assertNull(local.find(song("Nothing Here", "Nobody")));

		Files.writeString(dir.resolve("saki and baleszko - top 1.lrc"), "[00:10.00]made up line one\n[00:14.50]made up line two\n");
		LyricsResult r = local.find(song("TOP 1", "Saki & Baleszko"));
		assertNotNull(r);
		assertEquals(LyricsResult.Kind.SYNCED, r.kind());
		assertEquals(10_000, r.lyrics().firstLineTime());

		Files.writeString(dir.resolve("Árvíztűrő.txt"), "egy sor\nmásik sor\n", StandardCharsets.UTF_8);
		LyricsResult plain = local.find(song("Arvizturo", "Someone"));
		assertNotNull(plain);
		assertEquals(LyricsResult.Kind.ESTIMATED, plain.kind());
		assertEquals("egy sor", plain.lyrics().get(0).text());
	}

	@Test
	void readsOldWindowsEncodingAndTags(@TempDir Path dir) throws Exception {
		LocalLyrics local = new LocalLyrics(dir);
		Files.write(dir.resolve("whatever.lrc"), "[ar:Valaki]\n[ti:Dal Címe]\n[00:05.00]őszi szél fúj\n".getBytes(Charset.forName("windows-1250")));
		LyricsResult r = local.find(song("Dal Címe", "Valaki"));
		assertNotNull(r);
		assertEquals("őszi szél fúj", r.lyrics().get(0).text());
	}

	@Test
	void savesRecordedLyrics(@TempDir Path dir) throws Exception {
		LocalLyrics local = new LocalLyrics(dir);
		Lyrics timed = new Lyrics(List.of(new LyricLine(1_230, "first"), new LyricLine(65_400, "second")));
		Path f = local.save(song("My Song", "Me"), timed);
		assertEquals("Me - My Song.lrc", f.getFileName().toString());
		String text = Files.readString(f);
		assertTrue(text.contains("[00:01.23]first"));
		assertTrue(text.contains("[01:05.40]second"));
		assertEquals(LyricsResult.Kind.SYNCED, local.find(song("My Song", "Me")).kind());
	}
}
