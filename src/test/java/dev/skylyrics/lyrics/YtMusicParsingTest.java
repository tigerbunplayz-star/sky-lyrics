package dev.skylyrics.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Parses a made-up, trimmed YouTube Music search response. */
class YtMusicParsingTest {
	private static String row(String videoId, String title, String subtitle, String duration) {
		return """
				{"musicResponsiveListItemRenderer": {
				  "playlistItemData": {"videoId": "%s"},
				  "flexColumns": [
				    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "%s"}]}}},
				    {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
				      {"text": "%s"}, {"text": " \\u2022 "}, {"text": "%s"}]}}}
				  ]}}""".formatted(videoId, title, subtitle, duration);
	}

	@Test
	void findsSongRowsWithDuration() {
		String json = "{\"contents\": {\"tabbedSearchResultsRenderer\": {\"tabs\": [{\"tabRenderer\": {\"content\": "
				+ "{\"sectionListRenderer\": {\"contents\": [{\"musicShelfRenderer\": {\"contents\": ["
				+ row("abc123", "Test Song", "Some Artist", "3:39") + ","
				+ row("def456", "Other Song (Live)", "Someone Else", "1:02:05")
				+ "]}}]}}}}]}}}";

		List<YtMusicLyricsClient.Hit> hits = new ArrayList<>();
		YtMusicLyricsClient.collectHits(JsonParser.parseString(json), hits);

		assertEquals(2, hits.size());
		assertEquals("abc123", hits.get(0).videoId());
		assertEquals("Test Song", hits.get(0).title());
		assertEquals(219.0, hits.get(0).durationS(), 0.001);
		assertEquals(3725.0, hits.get(1).durationS(), 0.001);
	}

	@Test
	void readsVideoIdsFromLinks() {
		assertEquals("UPA6XHXopzY", YtMusicLyricsClient.videoIdFromLink("https://www.youtube.com/watch?v=UPA6XHXopzY"));
		assertEquals("UPA6XHXopzY", YtMusicLyricsClient.videoIdFromLink("https://youtu.be/UPA6XHXopzY?si=abc"));
		assertEquals("UPA6XHXopzY", YtMusicLyricsClient.videoIdFromLink("https://music.youtube.com/watch?v=UPA6XHXopzY&list=RD123"));
		assertEquals("UPA6XHXopzY", YtMusicLyricsClient.videoIdFromLink("https://www.youtube.com/shorts/UPA6XHXopzY"));
		assertEquals("UPA6XHXopzY", YtMusicLyricsClient.videoIdFromLink("  UPA6XHXopzY "));
		assertEquals(null, YtMusicLyricsClient.videoIdFromLink("https://example.com/watch?v=UPA6XHXopzY"));
		assertEquals(null, YtMusicLyricsClient.videoIdFromLink("hello"));
	}

	@Test
	void videoTitlesBecomeSongTitles() {
		assertEquals("TOP 1", YtMusicLyricsClient.songTitleFromVideo("SAKI & BALESZKO TOP 1 (Official Music)", "Saki & Baleszko"));
		assertEquals("Song Name", YtMusicLyricsClient.songTitleFromVideo("Some Band - Song Name (Official Video)", "Some Band"));
		assertEquals("Song Name", YtMusicLyricsClient.songTitleFromVideo("Song Name | Some Band", "Some Band"));
		assertEquals("Just A Title", YtMusicLyricsClient.songTitleFromVideo("Just A Title [Lyric Video]", "Other"));
	}

	@Test
	void titlesMustReallyMatch() {
		org.junit.jupiter.api.Assertions.assertTrue(YtMusicLyricsClient.titleMatches("top1", "top1"));
		org.junit.jupiter.api.Assertions.assertTrue(YtMusicLyricsClient.titleMatches("blindinglights", "blindinglightsremix"));
		org.junit.jupiter.api.Assertions.assertFalse(YtMusicLyricsClient.titleMatches("sakibaleszkotop1", "saki"));
	}
}
