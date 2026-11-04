package dev.skylyrics.render;

/** Small HSV helpers (no AWT). Colours are 0xRRGGBB. */
public final class Colors {
	private Colors() {
	}

	/** Returns {hue 0..1, saturation 0..1, value 0..1}. */
	public static float[] toHsv(int rgb) {
		float r = ((rgb >> 16) & 0xFF) / 255f;
		float g = ((rgb >> 8) & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h = 0f;

		if (d > 0f) {
			if (max == r) h = ((g - b) / d) % 6f;
			else if (max == g) h = (b - r) / d + 2f;
			else h = (r - g) / d + 4f;
			h /= 6f;
			if (h < 0f) h += 1f;
		}

		return new float[] {h, max == 0f ? 0f : d / max, max};
	}

	public static int fromHsv(float h, float s, float v) {
		h = h - (float) Math.floor(h);
		s = clamp(s);
		v = clamp(v);
		float c = v * s;
		float hp = h * 6f;
		float x = c * (1f - Math.abs(hp % 2f - 1f));
		float r, g, b;

		if (hp < 1f) { r = c; g = x; b = 0f; }
		else if (hp < 2f) { r = x; g = c; b = 0f; }
		else if (hp < 3f) { r = 0f; g = c; b = x; }
		else if (hp < 4f) { r = 0f; g = x; b = c; }
		else if (hp < 5f) { r = x; g = 0f; b = c; }
		else { r = c; g = 0f; b = x; }

		float m = v - c;
		return (Math.round((r + m) * 255f) << 16) | (Math.round((g + m) * 255f) << 8) | Math.round((b + m) * 255f);
	}

	public static int scale(int rgb, float k) {
		int r = Math.round(((rgb >> 16) & 0xFF) * k);
		int g = Math.round(((rgb >> 8) & 0xFF) * k);
		int b = Math.round((rgb & 0xFF) * k);
		return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}

	public static int mix(int a, int b, float t) {
		t = clamp(t);
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static float clamp(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}
}
