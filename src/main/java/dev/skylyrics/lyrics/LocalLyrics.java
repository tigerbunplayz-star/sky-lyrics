package dev.skylyrics.lyrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/**
 * The player's own lyrics: .lrc (timed) or .txt (plain) files in the "My lyrics" folder, named
 * "Artist - Title". They always take priority over downloaded lyrics. Names are matched loosely
 * (case, accents and punctuation don't matter).
 */
public final class LocalLyrics {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	private static final Pattern TAG = Pattern.compile("^\\[(ar|ti):(.*)]\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static final Pattern BAD_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

	private final Path dir;

	public LocalLyrics(Path dir) {
		this.dir = dir;

		try {
			Files.createDirectories(dir);
			Path readme = dir.resolve("README.txt");
			if (!Files.exists(readme)) Files.writeString(readme, README, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't create the lyrics folder {}: {}", dir, e.toString());
		}
	}

	public Path dir() {
		return dir;
	}

	/** The file name this song's lyrics should have, e.g. "Saki & Baleszko - TOP 1.lrc". */
	public static String fileName(String artist, String title) {
		String base = (artist == null || artist.isBlank() ? "" : artist.strip() + " - ") + (title == null ? "" : title.strip());
		String safe = BAD_FILENAME_CHARS.matcher(base).replaceAll("").replaceAll("\\s+", " ").strip();
		if (safe.length() > 150) safe = safe.substring(0, 150).strip();
		return (safe.isEmpty() ? "lyrics" : safe) + ".lrc";
	}

	/** Lyrics from the folder for this song, or null if there's no matching file. */
	public LyricsResult find(NowPlaying np) {
		Path file = match(np);
		if (file == null) return null;

		try {
			String text = read(file);
			Lyrics timed = LrcParser.parse(text);

			if (!timed.isEmpty() && timed.firstLineTime() >= 0) {
				LOG.info("[Sky Lyrics] Using your lyrics file {}", file.getFileName());
				return LyricsResult.synced(timed);
			}

			// No timestamps: treat it as plain lyrics with estimated timing.
			String plain = text.replaceAll("(?m)^\\[[a-zA-Z]+:.*]\\s*$", "");
			Lyrics estimated = PlainLyricsEstimator.estimate(plain, np.durationMs());
			if (estimated.isEmpty()) return null;
			LOG.info("[Sky Lyrics] Using your lyrics file {} (no timings, estimated)", file.getFileName());
			return new LyricsResult(LyricsResult.Kind.ESTIMATED, estimated);
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't read {}: {}", file, e.toString());
			return null;
		}
	}

	/** Write timed lyrics for a song into the folder (replacing an existing file of the same name). */
	public Path save(NowPlaying np, Lyrics lyrics) throws IOException {
		Files.createDirectories(dir);
		Path file = dir.resolve(fileName(np.artist(), np.title()));
		StringBuilder sb = new StringBuilder();
		if (!np.artist().isEmpty()) sb.append("[ar:").append(np.artist()).append("]\n");
		sb.append("[ti:").append(np.title()).append("]\n");
		sb.append("[by:Sky Lyrics timing recorder]\n");

		for (LyricLine line : lyrics.lines()) {
			sb.append(timestamp(line.timeMs())).append(line.text()).append('\n');
		}

		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
		return file;
	}

	/** "[01:02.34]" */
	public static String timestamp(long ms) {
		long t = Math.max(0, ms);
		return String.format(Locale.ROOT, "[%02d:%02d.%02d]", t / 60_000, (t / 1000) % 60, (t % 1000) / 10);
	}

	private Path match(NowPlaying np) {
		if (!Files.isDirectory(dir)) return null;

		String full = TitleCleaner.normalize(np.artist() + " - " + np.title());
		String main = TitleCleaner.normalize(TitleCleaner.primaryArtist(np.artist()) + " - " + TitleCleaner.cleanTitle(np.title()));
		String title = TitleCleaner.normalize(np.title());
		String cleanTitle = TitleCleaner.normalize(TitleCleaner.cleanTitle(np.title()));

		Path byArtistAndTitle = null;
		Path byTags = null;
		Path byTitleOnly = null;

		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path f : files) {
				String name = f.getFileName().toString();
				String lower = name.toLowerCase(Locale.ROOT);
				if (!(lower.endsWith(".lrc") || lower.endsWith(".txt")) || lower.equals("readme.txt")) continue;
				if (!Files.isRegularFile(f)) continue;

				String stem = TitleCleaner.normalize(name.substring(0, name.lastIndexOf('.')));
				if (stem.isEmpty()) continue;

				if (stem.equals(full) || stem.equals(main)) {
					// Prefer timed files when both a .lrc and a .txt exist.
					if (byArtistAndTitle == null || lower.endsWith(".lrc")) byArtistAndTitle = f;
				} else if (byTags == null && lower.endsWith(".lrc") && tagsMatch(f, np)) {
					byTags = f;
				} else if (byTitleOnly == null && (stem.equals(title) || stem.equals(cleanTitle))) {
					byTitleOnly = f;
				}
			}
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't read the lyrics folder: {}", e.toString());
			return null;
		}

		return byArtistAndTitle != null ? byArtistAndTitle : (byTags != null ? byTags : byTitleOnly);
	}

	/** An .lrc whose [ar:] and [ti:] tags name this song, whatever the file is called. */
	private static boolean tagsMatch(Path f, NowPlaying np) {
		try {
			String text = read(f);
			Matcher m = TAG.matcher(text.length() > 4000 ? text.substring(0, 4000) : text);
			String ar = null, ti = null;

			while (m.find()) {
				if (m.group(1).equalsIgnoreCase("ar")) ar = m.group(2);
				else ti = m.group(2);
			}

			if (ti == null) return false;
			boolean titleOk = TitleCleaner.normalize(TitleCleaner.cleanTitle(ti)).equals(TitleCleaner.normalize(TitleCleaner.cleanTitle(np.title())));
			return titleOk && (ar == null || TitleCleaner.artistMatches(ar, np.artist()));
		} catch (IOException e) {
			return false;
		}
	}

	/** UTF-8 (Notepad's default), falling back to the Central European Windows encoding. */
	static String read(Path f) throws IOException {
		byte[] bytes = Files.readAllBytes(f);
		String text;

		try {
			text = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			text = new String(bytes, Charset.forName("windows-1250"));
		}

		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	private static final String README = """
			Sky Lyrics - My lyrics folder
			=============================

			Put your own lyrics here. They always win over downloaded lyrics.

			File name:  Artist - Title.lrc   (or just  Title.lrc)
			            Capitals, accents and punctuation don't matter.
			            The settings menu (O key, Lyrics tab) shows the exact name for
			            the song that's playing.

			Timed lyrics (.lrc): one line per lyric line, each starting with the
			time it is sung, as [minutes:seconds.hundredths]:

			    [00:12.50]This is my first line
			    [00:16.20]And here comes the second
			    [00:20.00]
			    [00:31.75]After a short break, the next part

			    - An empty line with a time makes a pause (the words clear away).
			    - Lines must be in time order.
			    - Save the file as UTF-8 (Notepad does this by default) so accents work.

			Plain lyrics (.txt, or .lrc without times): just the lines. Sky Lyrics
			estimates the timing; press the sync key (; or the key right of L) when you
			hear a line start to line it up.

			Easiest way to get the timings: let the mod record them.
			  1. Play the song in Spotify (from the start is easiest).
			  2. Open the menu (O) > Lyrics > Record timing > Start.
			  3. Press the sync key at the start of every line as you hear it.
			  4. When the last line is tapped (or you press Stop in the menu) the
			     timed .lrc file is saved in this folder automatically.

			After editing a file, press Restart in the settings menu to reload it.
			""";
}
