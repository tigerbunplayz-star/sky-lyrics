package dev.skylyrics;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import dev.skylyrics.gui.SkyLyricsScreen;
import dev.skylyrics.gui.SettingsModel;
import dev.skylyrics.lyrics.LinkStore;
import dev.skylyrics.lyrics.LocalLyrics;
import dev.skylyrics.lyrics.LrcLibClient;
import dev.skylyrics.lyrics.Lyrics;
import dev.skylyrics.media.NowPlaying;
import dev.skylyrics.lyrics.LyricsService;
import dev.skylyrics.lyrics.YtMusicLyricsClient;
import dev.skylyrics.media.WindowsMediaSource;
import dev.skylyrics.render.SkyLyricsRenderer;

public final class SkyLyricsClient implements ClientModInitializer {
	public static final String MOD_ID = "skylyrics";
	private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
	private static final long OFFSET_STEP_MS = 100;

	private SkyLyricsConfig config;
	private WindowsMediaSource media;
	private LyricsEngine engine;
	private LyricsService lyrics;
	private LocalLyrics localLyrics;
	private LinkStore links;
	private SkyLyricsRenderer renderer;

	private KeyMapping toggleKey;
	private KeyMapping earlierKey;
	private KeyMapping laterKey;
	private KeyMapping syncKey;
	private KeyMapping menuKey;
	private String version = "dev";

	@Override
	public void onInitializeClient() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		config = SkyLyricsConfig.load(configDir.resolve("skylyrics.json"));

		version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
		LrcLibClient lrclib = new LrcLibClient(version);
		YtMusicLyricsClient youtube = new YtMusicLyricsClient(config.youtubeRegion());
		localLyrics = new LocalLyrics(configDir.resolve("skylyrics").resolve("lyrics"));
		links = new LinkStore(localLyrics.dir().resolve("links.json"));
		lyrics = new LyricsService(localLyrics, links, lrclib, youtube, () -> config.youtubeMusicLyrics);
		engine = new LyricsEngine(lyrics::fetch);
		renderer = new SkyLyricsRenderer(() -> config, engine);

		if (WindowsMediaSource.isSupported()) {
			media = new WindowsMediaSource(config.player, configDir);
			media.start();
		} else {
			LOG.warn("[Sky Lyrics] Reading Spotify is only supported on Windows; lyrics won't show on this system.");
		}

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skylyrics.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		earlierKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skylyrics.earlier", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));
		laterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skylyrics.later", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
		syncKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skylyrics.sync", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SEMICOLON, category));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skylyrics.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(renderer::collect);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (media != null) media.close();
		});
	}

	private SettingsModel.MenuActions menuActions() {
		return new SettingsModel.MenuActions() {
			public String currentFileName() {
				NowPlaying np = engine.currentTrack();
				return np == null ? null : LocalLyrics.fileName(np.artist(), np.title());
			}

			public void openLyricsFolder() {
				try {
					if (WindowsMediaSource.isSupported()) {
						new ProcessBuilder("explorer.exe", localLyrics.dir().toAbsolutePath().toString()).start();
					}
				} catch (java.io.IOException e) {
					LOG.warn("[Sky Lyrics] Couldn't open the lyrics folder: {}", e.toString());
				}
			}

			public boolean canRecord() {
				return engine.canRecord();
			}

			public boolean isRecording() {
				return engine.isRecording();
			}

			public String linkedVideo() {
				NowPlaying np = engine.currentTrack();
				String id = np == null ? null : links.videoId(np);
				return id == null ? null : "youtu.be/" + id;
			}

			public boolean hasSong() {
				return engine.currentTrack() != null;
			}

			public void pasteLink() {
				NowPlaying np = engine.currentTrack();
				if (np == null) {
					renderer.showNotice("Play the song first");
					return;
				}

				String id = YtMusicLyricsClient.videoIdFromLink(clipboard());
				if (id == null) {
					renderer.showNotice("Copy a YouTube link first (youtube.com/watch?v=... or youtu.be/...)", 3_000_000_000L);
					return;
				}

				links.put(np, id);
				lyrics.clearCache();
				engine.refetch();
				renderer.showNotice("Linked! Getting that video's lyrics\u2026", 2_500_000_000L);
			}

			public void removeLink() {
				NowPlaying np = engine.currentTrack();
				if (np != null && links.remove(np)) {
					lyrics.clearCache();
					engine.refetch();
					renderer.showNotice("Link removed");
				}
			}

			public void toggleRecording() {
				if (engine.isRecording()) {
					finishRecording();
				} else if (engine.startRecording()) {
					renderer.showNotice("Recording: tap ; at the start of each line", 3_000_000_000L);
				} else {
					renderer.showNotice("Play a song with lyrics first");
				}
			}
		};
	}

	/** Text on the clipboard (GLFW, on the render thread where the game window is current). */
	private static String clipboard() {
		try {
			long window = GLFW.glfwGetCurrentContext();
			return window == 0L ? null : GLFW.glfwGetClipboardString(window);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Stop the recorder and save the timed lines to the lyrics folder. */
	private void finishRecording() {
		NowPlaying np = engine.currentTrack();
		int total = engine.recordTotal();
		Lyrics timed = engine.stopRecording();

		if (np == null || timed == null || timed.isEmpty()) {
			renderer.showNotice("Recording stopped (nothing tapped)");
			return;
		}

		try {
			java.nio.file.Path file = localLyrics.save(np, timed);
			renderer.showNotice("Saved " + timed.size() + "/" + total + " timed lines: " + file.getFileName(), 4_000_000_000L);
		} catch (java.io.IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't save recorded lyrics: {}", e.toString());
			renderer.showNotice("Couldn't save the lyrics file");
		}
	}

	/** After Save: apply settings that live outside the renderer (which app to read). */
	private void applySettings() {
		if (media != null && !media.filter().equalsIgnoreCase(config.player == null ? "spotify" : config.player.strip())) {
			media.restart(config.player);
		}
	}

	/** The menu's Restart button: restart the Spotify reader and download the lyrics again. */
	private void restartServices() {
		lyrics.clearCache();
		if (media != null) media.restart(config.player);
		else engine.refetch();
	}

	private void tick(Minecraft client) {
		engine.update(media != null ? media.latest() : null, System.nanoTime());

		while (menuKey.consumeClick()) {
			client.setScreenAndShow(new SkyLyricsScreen(config, renderer, version, this::applySettings, this::restartServices, menuActions()));
		}

		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			config.save();
			renderer.showNotice(config.enabled ? "Sky Lyrics on" : "Sky Lyrics off (press K to show)");
		}

		boolean offsetChanged = false;

		while (earlierKey.consumeClick()) {
			config.offsetMs += OFFSET_STEP_MS;
			offsetChanged = true;
		}

		while (laterKey.consumeClick()) {
			config.offsetMs -= OFFSET_STEP_MS;
			offsetChanged = true;
		}

		while (syncKey.consumeClick()) {
			if (engine.isRecording()) {
				if (engine.recordTap(System.nanoTime())) finishRecording();
				continue;
			}

			Long shift = engine.syncLineStartsNow(System.nanoTime(), config.offsetMs);
			renderer.showNotice(shift == null ? "No lyrics to sync"
					: shift == 0 ? "Lyrics synced" : "Lyrics synced (" + (shift > 0 ? "+" : "") + shift + " ms)");
		}

		if (engine.isRecording()) {
			String next = engine.nextRecordLine();
			if (next != null && next.length() > 40) next = next.substring(0, 39) + "\u2026";
			renderer.showNotice("\u25CF REC " + engine.recordedCount() + "/" + engine.recordTotal()
					+ "  tap ; for: " + (next == null ? "" : next), 300_000_000L);
		}

		if (offsetChanged) {
			config.save();
			long o = config.offsetMs;
			renderer.showNotice(o == 0 ? "Lyrics timing: normal"
					: "Lyrics timing: " + Math.abs(o) + " ms " + (o > 0 ? "earlier" : "later"));
		}
	}
}
