package dev.skylyrics.render;

import java.util.List;

/**
 * How the words are coloured.
 * <ul>
 *   <li>Original: each word group takes the next palette colour, and the hue drifts a little
 *       from letter to letter.</li>
 *   <li>Custom: every group uses the player's colour; with fade on, the letters blend from the
 *       main colour into the fade colour across each group.</li>
 * </ul>
 * Shimmer adds a slow wobble over time in both modes. Pure math, shared by the world renderer
 * and the settings preview.
 */
public final class ColorScheme {
	private static final float LETTER_HUE_STEP = 0.022f;

	private final boolean custom;
	private final int[] palette;
	private final int main;
	private final int fadeTo;
	private final boolean fade;
	private final boolean shimmer;

	public ColorScheme(boolean custom, int[] palette, int main, int fadeTo, boolean fade, boolean shimmer) {
		this.custom = custom;
		this.palette = palette == null || palette.length == 0 ? new int[] {0xFFFFFF} : palette;
		this.main = main & 0xFFFFFF;
		this.fadeTo = fadeTo & 0xFFFFFF;
		this.fade = fade;
		this.shimmer = shimmer;
	}

	public static int[] parsePalette(List<String> hex) {
		if (hex == null || hex.isEmpty()) return new int[] {0xFF5C8A, 0x4DD8FF, 0xFFE45C};
		int[] p = new int[hex.size()];
		for (int i = 0; i < p.length; i++) p[i] = parse(hex.get(i), 0xFFFFFF);
		return p;
	}

	public static int parse(String hex, int fallback) {
		if (hex == null) return fallback;
		String h = hex.strip();
		if (h.startsWith("#")) h = h.substring(1);
		if (h.length() != 6) return fallback;

		try {
			return Integer.parseUnsignedInt(h, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static String hex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	public boolean isCustom() {
		return custom;
	}

	/** The group's base colour (used for the 3D sides). */
	public int base(int group) {
		if (!custom) return palette[Math.floorMod(group, palette.length)];
		return fade ? Colors.mix(main, fadeTo, 0.5f) : main;
	}

	/**
	 * Colour of letter {@code i} of {@code count} in a group.
	 *
	 * @param timeS seconds, for the shimmer (ignored when shimmer is off)
	 */
	public int letter(int group, int i, int count, double timeS, float phase) {
		float drift = shimmer ? (float) Math.sin(timeS * 1.1 + phase) * 0.035f : 0f;

		if (!custom) {
			float[] hsv = Colors.toHsv(base(group));
			return Colors.fromHsv(hsv[0] + i * LETTER_HUE_STEP + drift, hsv[1] * 0.92f, Math.min(1f, hsv[2] * 1.04f + 0.02f));
		}

		float t = count <= 1 ? 0f : (float) i / (count - 1);
		int rgb = fade ? Colors.mix(main, fadeTo, t) : main;
		if (drift == 0f) return rgb;

		float[] hsv = Colors.toHsv(rgb);
		return Colors.fromHsv(hsv[0] + drift, hsv[1], hsv[2]);
	}
}
