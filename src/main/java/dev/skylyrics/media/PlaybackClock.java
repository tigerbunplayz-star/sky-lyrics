package dev.skylyrics.media;

/**
 * Turns occasional position readings into a smooth per-frame playback position.
 * Small drifts are corrected gradually so lyrics never jitter; seeks (big jumps)
 * are applied immediately.
 */
public final class PlaybackClock {
	/** A reading this far from the prediction is treated as a seek. */
	static final double HARD_RESYNC_MS = 700;
	/** How much of a small drift to correct per reading. */
	static final double DRIFT_CORRECTION = 0.3;

	private boolean hasAnchor;
	private double anchorPosMs;
	private long anchorNanos;
	private boolean playing;

	public void reset() {
		hasAnchor = false;
		anchorPosMs = 0;
		playing = false;
	}

	/** Feed a reported position taken at {@code sampleNanos}. */
	public void sync(long reportedPosMs, boolean playing, long sampleNanos) {
		if (!hasAnchor) {
			anchor(reportedPosMs, sampleNanos);
			this.playing = playing;
			return;
		}

		double predicted = positionAt(sampleNanos);
		double error = reportedPosMs - predicted;

		if (!playing || playing != this.playing || Math.abs(error) > HARD_RESYNC_MS) {
			anchor(reportedPosMs, sampleNanos);
		} else {
			anchor(predicted + error * DRIFT_CORRECTION, sampleNanos);
		}

		this.playing = playing;
	}

	/** For players that report no position: run our own clock from the track start. */
	public void runFree(boolean playing, long nowNanos) {
		if (!hasAnchor) {
			anchor(0, nowNanos);
		} else if (playing != this.playing) {
			anchor(positionAt(nowNanos), nowNanos);
		}

		this.playing = playing;
	}

	public double positionAt(long nanos) {
		if (!hasAnchor) return 0;
		if (!playing) return anchorPosMs;
		return anchorPosMs + (nanos - anchorNanos) / 1_000_000.0;
	}

	public boolean isPlaying() {
		return playing;
	}

	private void anchor(double posMs, long nanos) {
		hasAnchor = true;
		anchorPosMs = Math.max(0, posMs);
		anchorNanos = nanos;
	}
}
