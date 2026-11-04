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
import dev.skylyrics.lyrics.LrcLibClient;
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
		lyrics = new LyricsService(lrclib, youtube, () -> config.youtubeMusicLyrics);
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
			client.setScreenAndShow(new SkyLyricsScreen(config, renderer, version, this::applySettings, this::restartServices));
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
			Long shift = engine.syncLineStartsNow(System.nanoTime(), config.offsetMs);
			renderer.showNotice(shift == null ? "No lyrics to sync"
					: shift == 0 ? "Lyrics synced" : "Lyrics synced (" + (shift > 0 ? "+" : "") + shift + " ms)");
		}

		if (offsetChanged) {
			config.save();
			long o = config.offsetMs;
			renderer.showNotice(o == 0 ? "Lyrics timing: normal"
					: "Lyrics timing: " + Math.abs(o) + " ms " + (o > 0 ? "earlier" : "later"));
		}
	}
}
