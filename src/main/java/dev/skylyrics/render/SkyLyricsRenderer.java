package dev.skylyrics.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import dev.skylyrics.LyricsEngine;
import dev.skylyrics.SkyLyricsConfig;
import dev.skylyrics.render.SkyLayout.Row;
import dev.skylyrics.render.SkyLayout.Style;

/** Draws the lyric rows as huge glowing text floating in the sky in front of the player. */
public final class SkyLyricsRenderer {
	/** Vertical distance between rows, in font pixels. */
	private static final float LINE_PX = 12f;
	/** Blocks of text height per block of distance, per font pixel (keeps the angular size constant). */
	private static final float SIZE_PER_DISTANCE = 0.0095f;
	private static final long NOTICE_NANOS = 1_800_000_000L;

	private final Supplier<SkyLyricsConfig> config;
	private final LyricsEngine engine;

	private float smoothedYaw = Float.NaN;
	private long lastFrameNanos;
	private volatile String notice;
	private volatile long noticeUntilNanos;

	public SkyLyricsRenderer(Supplier<SkyLyricsConfig> config, LyricsEngine engine) {
		this.config = config;
		this.engine = engine;
	}

	/** Briefly show a small message in the sky (toggle / timing changes). */
	public void showNotice(String text) {
		notice = text;
		noticeUntilNanos = System.nanoTime() + NOTICE_NANOS;
	}

	public void collect(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;

		SkyLyricsConfig cfg = config.get();
		long now = System.nanoTime();
		updateYaw(cfg.followView ? mc.player.getYRot() : (float) cfg.fixedYaw, (float) cfg.followSpeed, now);

		List<Row> rows = new ArrayList<>();

		if (cfg.enabled) {
			rows.addAll(SkyLayout.compute(engine.snapshot(now, cfg.offsetMs), cfg.showNextLine, cfg.karaoke));
		}

		String n = notice;
		long left = noticeUntilNanos - now;

		if (n != null && left > 0) {
			float a = (float) Math.min(1.0, left / 300_000_000.0);
			rows.add(new Row(n, Style.DIM, a, 0.45f, -2.4f, -1));
		}

		if (rows.isEmpty()) return;

		draw(context, mc.font, cfg, rows);
	}

	private void updateYaw(float targetYaw, float speed, long now) {
		float dt = (now - lastFrameNanos) / 1_000_000_000f;
		lastFrameNanos = now;

		if (Float.isNaN(smoothedYaw) || dt > 1f || dt < 0f || speed <= 0f) {
			smoothedYaw = targetYaw;
			return;
		}

		float delta = wrapDegrees(targetYaw - smoothedYaw);
		float k = 1f - (float) Math.exp(-speed * dt);
		smoothedYaw = wrapDegrees(smoothedYaw + delta * k);
	}

	private void draw(LevelRenderContext context, Font font, SkyLyricsConfig cfg, List<Row> rows) {
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector out = context.submitNodeCollector();

		float distance = (float) Math.max(8, cfg.distance);
		float elevation = (float) Math.toRadians(Math.max(-80, Math.min(85, cfg.elevation)));
		Quaternionf facing = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(smoothedYaw), elevation, 0f);
		Vector3f forward = facing.transform(new Vector3f(0f, 0f, -1f));
		float s = distance * SIZE_PER_DISTANCE * (float) Math.max(0.1, cfg.size);

		int lyricRgb = SkyLyricsConfig.parseColor(cfg.lyricColor, 0xFFFFFF);
		int unsungRgb = SkyLyricsConfig.parseColor(cfg.unsungColor, 0x9FB0C8);
		int titleRgb = SkyLyricsConfig.parseColor(cfg.titleColor, 0x1ED760);
		int dimRgb = SkyLyricsConfig.parseColor(cfg.dimColor, 0xDDE6F0);

		// Wrap everything first so rows can make room for multi-line neighbours.
		List<List<String>> wrapped = new ArrayList<>(rows.size());
		float mainExtra = 0f;

		for (Row row : rows) {
			int maxWidth = Math.max(40, Math.round(cfg.maxLineWidth / row.scale()));
			List<String> lines = TextWrap.wrap(row.text(), maxWidth, font::width);
			wrapped.add(lines);

			if (row.style() == Style.LYRIC || row.style() == Style.TITLE) {
				mainExtra = Math.max(mainExtra, (lines.size() - 1) * LINE_PX * row.scale());
			}
		}

		poseStack.pushPose();
		poseStack.translate(forward.x() * distance, forward.y() * distance, forward.z() * distance);
		poseStack.mulPose(facing);
		poseStack.scale(s, -s, s);

		for (int r = 0; r < rows.size(); r++) {
			Row row = rows.get(r);
			List<String> lines = wrapped.get(r);
			if (lines.isEmpty()) continue;

			float alpha = row.alpha() * (float) Math.max(0, Math.min(1, cfg.opacity));
			if (alpha < 0.02f) continue;

			boolean main = row.style() == Style.LYRIC || row.style() == Style.TITLE;
			float ownExtra = (lines.size() - 1) * LINE_PX * row.scale();
			float baseY = row.y() * LINE_PX;
			if (!main && row.y() > 0) baseY += mainExtra / 2f + ownExtra / 2f;
			if (!main && row.y() < 0) baseY -= mainExtra / 2f + ownExtra / 2f;

			int rgb = switch (row.style()) {
				case LYRIC, SUB -> lyricRgb;
				case TITLE -> titleRgb;
				case DIM -> dimRgb;
			};
			int argb = (Math.max(5, Math.round(alpha * 255f)) << 24) | rgb;
			int background = cfg.background ? (Math.round(alpha * 0x70) << 24) : 0;

			int totalChars = String.join(" ", lines).length();
			int sungTotal = row.sung() >= 0 ? Math.round(row.sung() * totalChars) : -1;
			int charStart = 0;

			for (int i = 0; i < lines.size(); i++) {
				String text = lines.get(i);
				FormattedCharSequence seq;

				if (sungTotal >= 0) {
					seq = karaokeLine(text, sungTotal - charStart, lyricRgb, unsungRgb);
				} else {
					seq = Component.literal(text).getVisualOrderText();
				}

				charStart += text.length() + 1;

				float lineY = baseY + (i - (lines.size() - 1) / 2f) * LINE_PX * row.scale();

				poseStack.pushPose();
				poseStack.translate(0f, lineY, 0f);
				poseStack.scale(row.scale(), row.scale(), row.scale());
				float x = -font.width(seq) / 2f;
				float y = -font.lineHeight / 2f;
				out.submitText(poseStack, x, y, seq, cfg.shadow, Font.DisplayMode.NORMAL,
						LightCoordsUtil.FULL_BRIGHT, argb, background, 0);
				poseStack.popPose();
			}
		}

		poseStack.popPose();
	}

	private static FormattedCharSequence karaokeLine(String text, int sungChars, int sungRgb, int unsungRgb) {
		int k = Math.max(0, Math.min(text.length(), sungChars));
		if (k > 0 && k < text.length() && Character.isLowSurrogate(text.charAt(k))) k++;

		MutableComponent line = Component.empty();
		if (k > 0) line.append(Component.literal(text.substring(0, k)).withColor(sungRgb));
		if (k < text.length()) line.append(Component.literal(text.substring(k)).withColor(unsungRgb));
		return line.getVisualOrderText();
	}

	private static float wrapDegrees(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}
}
