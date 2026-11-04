package dev.skylyrics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.skylyrics.lyrics.LrcLibClient;
import dev.skylyrics.lyrics.Lyrics;
import dev.skylyrics.lyrics.LyricsResult;
import dev.skylyrics.lyrics.YtMusicLyricsClient;
import dev.skylyrics.media.NowPlaying;

/**
 * Live check of the lyric sources, run only in CI (SKYLYRICS_NET_TEST=1). Reports what each
 * source returns (kind, line count, first timestamp) without printing any lyric text, and never
 * fails the build: the outside services may be down or block the runner.
 */
class NetworkLyricsTest {
	private record Song(String title, String artist, long durationMs) {
	}

	private static final List<Song> SONGS = List.of(
			new Song("A sör, a bor, a pálinka", "Delta", 219_000),
			new Song("Blinding Lights", "The Weeknd", 200_040),
			new Song("Bohemian Rhapsody - Remastered 2011", "Queen", 354_320),
			new Song("Introvertált dal", "Azahriah", 0),
			new Song("Valami Amerika", "Halott Pénz", 0),
			new Song("TOP 1", "Saki & Baleszko", 0),
			new Song("TOP 1", "Saki", 0));

	@Test
	void reportLiveLookups() throws Exception {
		Assumptions.assumeTrue("1".equals(System.getenv("SKYLYRICS_NET_TEST")), "network test only runs in CI");

		LrcLibClient lrclib = new LrcLibClient("ci");
		YtMusicLyricsClient youtube = new YtMusicLyricsClient("HU");
		List<String> report = new ArrayList<>();

		for (Song s : SONGS) {
			NowPlaying np = new NowPlaying(s.title(), s.artist(), "", 0, s.durationMs(), true, 0);
			report.add(s.artist() + " - " + s.title());
			report.add("  lrclib:  " + describe(() -> lrclib.lookup(np)));
			report.add("  youtube: " + describe(() -> youtube.lookup(np)));
		}

		Path out = Path.of("build", "net-test.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, String.join("\n", report) + "\n", StandardCharsets.UTF_8);
		System.out.println(String.join("\n", report));
	}

	private interface Lookup {
		LyricsResult run();
	}

	private static String describe(Lookup lookup) {
		long t0 = System.nanoTime();

		try {
			LyricsResult r = lookup.run();
			long ms = (System.nanoTime() - t0) / 1_000_000;
			Lyrics l = r.lyrics();
			if (l == null || l.isEmpty()) return r.kind() + " (" + ms + " ms)";
			long words = l.lines().stream().filter(x -> !x.isBlank()).count();
			return r.kind() + ", " + words + " lines, first at " + l.firstLineTime() + " ms, last at "
					+ l.get(l.size() - 1).timeMs() + " ms (" + ms + " ms)";
		} catch (RuntimeException e) {
			Throwable c = e.getCause() != null ? e.getCause() : e;
			return "ERROR " + c;
		}
	}
}
