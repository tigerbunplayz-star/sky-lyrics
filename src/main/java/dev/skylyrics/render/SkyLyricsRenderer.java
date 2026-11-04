package dev.skylyrics.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import dev.skylyrics.LyricsEngine;
import dev.skylyrics.SkyLyricsConfig;
import dev.skylyrics.lyrics.LrcParser;
import dev.skylyrics.lyrics.Lyrics;
import dev.skylyrics.render.LyricScene.Chunk;
import dev.skylyrics.render.LyricScene.Kind;
import dev.skylyrics.render.LyricScene.Placement;

/**
 * Draws the lyric word groups as chunky, colourful 3D letters standing in the world around
 * the player. Each group is anchored where the player was looking when it appeared, so you
 * can walk around them and see their sides.
 */
public final class SkyLyricsRenderer {
	/** Blocks per font pixel for a group 18 blocks away (keeps size similar at any distance). */
	private static final float BASE_SCALE = 0.13f;
	private static final float REFERENCE_DISTANCE = 18f;
	/** Slices stacked behind the front face to make the letters look solid. */
	private static final int DEPTH_LAYERS = 12;
	/** Letter thickness in font pixels at depth = 1. */
	private static final float DEPTH_PX = 2.4f;
	private static final double APPEAR_MS = 520;
	private static final long NOTICE_NANOS = 1_800_000_000L;

	public static final FontDescription BLOCK_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("skylyrics", "block"));
	private static final FontDescription LOBSTER_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("skylyrics", "lobster"));

	private record Anchor(double x, double y, double z, float yaw) {
	}

	private final Supplier<SkyLyricsConfig> config;
	private final LyricsEngine engine;
	private final Map<Long, Anchor> anchors = new HashMap<>();
	private String anchorTrack = "";
	private volatile String notice;
	private volatile long noticeUntilNanos;
	private volatile long previewUntilNanos;
	private long previewStartNanos;

	/** Sample phrases (written for this mod) shown while the settings menu is open and nothing plays. */
	private static final Lyrics PREVIEW = LrcParser.parse("""
			[00:00.00]This is Sky Lyrics
			[00:02.60]words pop up around you
			[00:05.20]change a slider, watch them move
			[00:08.00]
			""");
	private static final long PREVIEW_LOOP_MS = 9_500;

	public SkyLyricsRenderer(Supplier<SkyLyricsConfig> config, LyricsEngine engine) {
		this.config = config;
		this.engine = engine;
	}

	/** Briefly show a small message in front of the player (toggle / timing changes). */
	public void showNotice(String text) {
		showNotice(text, NOTICE_NANOS);
	}

	public void showNotice(String text, long durationNanos) {
		notice = text;
		noticeUntilNanos = System.nanoTime() + durationNanos;
	}

	/** Keep the sample lyrics showing (the settings menu calls this every frame). */
	public void keepPreview() {
		long now = System.nanoTime();
		if (now - previewUntilNanos > 1_000_000_000L) previewStartNanos = now;
		previewUntilNanos = now + 300_000_000L;
	}

	public void collect(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		SkyLyricsConfig cfg = config.get();
		long now = System.nanoTime();
		double timeS = (now % 3_600_000_000_000L) / 1_000_000_000.0;
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float viewYaw = mc.player.getYRot();

		LyricsEngine.Snapshot snap = engine.snapshot(now, cfg.offsetMs);

		if (snap.status() == LyricsEngine.Status.IDLE && now < previewUntilNanos) {
			double pos = ((now - previewStartNanos) / 1_000_000.0) % PREVIEW_LOOP_MS;
			snap = new LyricsEngine.Snapshot(LyricsEngine.Status.SYNCED, "Sky Lyrics", "Preview", PREVIEW, pos, true, 60_000);
		}
		String track = snap.title() + "\u0001" + snap.artist();

		if (!track.equals(anchorTrack)) {
			anchors.clear();
			anchorTrack = track;
		}

		List<Chunk> chunks = cfg.enabled ? LyricScene.compute(snap, cfg.sceneSettings()) : List.of();
		Set<Long> live = new HashSet<>();
		for (Chunk c : chunks) live.add(c.key());
		anchors.keySet().retainAll(live);

		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector out = context.submitNodeCollector();
		Font font = mc.font;
		ColorScheme colors = cfg.colorScheme();

		for (Chunk c : chunks) {
			Anchor a = anchors.computeIfAbsent(c.key(), k -> new Anchor(cam.x, cam.y, cam.z, viewYaw));
			// Left far behind (teleport, fast travel): don't draw it across the map.
			double dx = a.x() - cam.x, dy = a.y() - cam.y, dz = a.z() - cam.z;
			if (dx * dx + dy * dy + dz * dz > 80 * 80) continue;
			drawChunk(poseStack, out, font, cfg, colors, c, a, cam, timeS);
		}

		String n = notice;
		long left = noticeUntilNanos - now;
		if (n != null && left > 0) drawNotice(poseStack, out, font, n, cam, viewYaw, left);
	}

	private void drawChunk(PoseStack poseStack, SubmitNodeCollector out, Font font, SkyLyricsConfig cfg,
			ColorScheme colors, Chunk c, Anchor a, Vec3 cam, double timeS) {
		Placement p = c.place();

		// Pop up from below with a little overshoot and swing into place; shrink and sink away.
		float speed = (float) Math.max(0.1, cfg.animationSpeed);
		float in = (float) clamp01(c.appearMs() * speed / APPEAR_MS);
		if (c.appearMs() < 0) return;
		float pop = easeOutBack(in);
		float settle = 1f - easeOutCubic(in);
		float exit = c.exit();
		float shrink = 1f - easeInCubic(exit);
		float scaleAnim = Math.max(0f, pop * shrink);
		if (scaleAnim < 0.02f) return;

		float bob = cfg.bob ? (float) Math.sin(timeS * 1.7 * speed + p.phase()) * 0.12f : 0f;
		float sway = cfg.bob ? (float) Math.sin(timeS * 0.9 * speed + p.phase() * 1.3) * 2.5f : 0f;
		float lift = -settle * 1.6f - exit * 1.4f + bob;

		boolean note = c.kind() == Kind.NOTE;
		FontDescription fontDesc = note ? null : fontFor(cfg);
		int base = note ? 0xFFFFFF : colors.base(c.color());
		float flash = (1f - in) * 0.65f; // freshly appeared words flash brighter

		FormattedCharSequence front = note ? plain(c.text(), null, 0xEEF2F8).getVisualOrderText()
				: gradient(c.text(), fontDesc, colors, c.color(), timeS, p.phase(), flash).getVisualOrderText();
		int widthPx = font.width(front);
		float fullScale = BASE_SCALE * p.size() * (float) Math.max(0.1, cfg.size) * (p.distance() / REFERENCE_DISTANCE);

		// Keep the whole group on screen: wide groups stay closer to the centre of view.
		double halfWidthDeg = Math.toDegrees(Math.atan(widthPx * fullScale / 2.0 / p.distance()));
		double maxYaw = Math.max(0, 46 - halfWidthDeg);
		double yawOffset = Math.max(-maxYaw, Math.min(maxYaw, p.yawOffset()));

		double yawRad = Math.toRadians(a.yaw() + yawOffset);
		double wx = a.x() - Math.sin(yawRad) * p.distance();
		double wy = a.y() + p.height() + lift;
		double wz = a.z() + Math.cos(yawRad) * p.distance();

		float faceYaw = a.yaw() + p.faceTurn() + settle * 40f + sway;
		float s = fullScale * scaleAnim;

		poseStack.pushPose();
		poseStack.translate(wx - cam.x, wy - cam.y, wz - cam.z);
		poseStack.mulPose(new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(faceYaw), 0f, 0f));
		poseStack.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(p.roll())));
		poseStack.scale(s, -s, s);

		float x = -widthPx / 2f;
		float y = -font.lineHeight / 2f;

		float depthPx = (float) Math.max(0, cfg.depth) * DEPTH_PX;

		if (!note && depthPx > 0.01f) {
			FormattedCharSequence side = plain(c.text(), fontDesc, -1).getVisualOrderText(); // colour comes from each layer

			for (int k = DEPTH_LAYERS; k >= 1; k--) {
				float f = (float) k / DEPTH_LAYERS;
				int shade = Colors.scale(base, 0.62f - 0.34f * f);
				poseStack.pushPose();
				poseStack.translate(0f, 0f, -depthPx * f);
				out.submitText(poseStack, x, y, side, false, Font.DisplayMode.NORMAL,
						LightCoordsUtil.FULL_BRIGHT, 0xFF000000 | shade, 0, 0);
				poseStack.popPose();
			}
		}

		out.submitText(poseStack, x, y, front, note, Font.DisplayMode.NORMAL,
				LightCoordsUtil.FULL_BRIGHT, 0xFFFFFFFF, 0, 0);
		poseStack.popPose();
	}

	private void drawNotice(PoseStack poseStack, SubmitNodeCollector out, Font font, String text,
			Vec3 cam, float viewYaw, long leftNanos) {
		float grow = (float) Math.min(1.0, leftNanos / 250_000_000.0);
		double yawRad = Math.toRadians(viewYaw);
		float dist = 8f;

		poseStack.pushPose();
		poseStack.translate(-Math.sin(yawRad) * dist, 2.2, Math.cos(yawRad) * dist);
		poseStack.mulPose(new Quaternionf().rotationYXZ((float) Math.PI - (float) yawRad, 0f, 0f));
		float s = 0.035f * grow;
		poseStack.scale(s, -s, s);
		FormattedCharSequence seq = Component.literal(text).getVisualOrderText();
		out.submitText(poseStack, -font.width(seq) / 2f, -font.lineHeight / 2f, seq, true,
				Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, 0xFFFFFFFF, 0x50000000, 0);
		poseStack.popPose();
	}

	/** Front face: one colour per letter from the colour scheme, flashing white as it pops in. */
	private static MutableComponent gradient(String text, FontDescription fontDesc, ColorScheme colors, int group,
			double timeS, float phase, float flash) {
		int letters = (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
		MutableComponent line = Component.empty();
		int i = 0;

		for (int off = 0; off < text.length(); ) {
			int cp = text.codePointAt(off);
			String ch = new String(Character.toChars(cp));
			off += Character.charCount(cp);

			int rgb = colors.letter(group, i, letters, timeS, phase);
			if (flash > 0f) rgb = Colors.mix(rgb, 0xFFFFFF, flash);
			line.append(plain(ch, fontDesc, rgb));
			if (!Character.isWhitespace(cp)) i++;
		}

		return line;
	}

	/** @param rgb colour, or -1 to use the colour passed when drawing */
	private static MutableComponent plain(String text, FontDescription fontDesc, int rgb) {
		MutableComponent c = Component.literal(text);
		if (rgb >= 0) c = c.withColor(rgb);
		if (fontDesc != null) c = c.withStyle(style -> style.withFont(fontDesc));
		return c;
	}

	public static FontDescription fontFor(SkyLyricsConfig cfg) {
		String f = cfg.font == null ? "block" : cfg.font.strip().toLowerCase(java.util.Locale.ROOT);
		return switch (f) {
			case "minecraft", "default", "vanilla" -> null;
			case "lobster", "fancy", "script" -> LOBSTER_FONT;
			default -> BLOCK_FONT;
		};
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	private static float easeOutCubic(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	private static float easeInCubic(float t) {
		return t * t * t;
	}

	private static float easeOutBack(float t) {
		float c1 = 1.9f;
		float c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}
}
