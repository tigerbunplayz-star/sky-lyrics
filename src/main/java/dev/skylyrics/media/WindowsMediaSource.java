package dev.skylyrics.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the now-playing track from Windows' media controls through a small PowerShell
 * helper. Works with the Spotify desktop app (and, optionally, any other player) with
 * no Spotify account setup or Premium needed.
 */
public final class WindowsMediaSource implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");
	/** Drop the reading if the helper goes quiet for this long. */
	private static final long STALE_NANOS = 6_000_000_000L;

	private final String filter;
	private final Path configDir;
	private volatile NowPlaying latest;
	private volatile long latestReceivedNanos;
	private volatile boolean running = true;
	private volatile Process process;
	private Thread thread;

	public WindowsMediaSource(String filter, Path configDir) {
		this.filter = filter == null || filter.isBlank() ? "spotify" : filter.strip();
		this.configDir = configDir;
	}

	public static boolean isSupported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	public void start() {
		thread = new Thread(this::runLoop, "SkyLyrics media reader");
		thread.setDaemon(true);
		thread.start();
		Runtime.getRuntime().addShutdownHook(new Thread(this::close, "SkyLyrics media reader shutdown"));
	}

	/** The newest reading, or null if nothing (matching) is playing. */
	public NowPlaying latest() {
		NowPlaying np = latest;
		if (np != null && System.nanoTime() - latestReceivedNanos > STALE_NANOS) return null;
		return np;
	}

	@Override
	public void close() {
		running = false;
		Process p = process;
		if (p != null) p.destroy();
	}

	private void runLoop() {
		long backoffMs = 2_000;
		int failures = 0;

		while (running) {
			long started = System.nanoTime();

			try {
				Path script = extractScript();
				runHelper(script);
			} catch (IOException e) {
				failures++;
				LOG.warn("[Sky Lyrics] Couldn't run the media reader (attempt {}): {}", failures, e.toString());
			} catch (InterruptedException e) {
				return;
			}

			latest = null;
			if (!running) return;

			// Ran fine for a while → it just stopped; restart quickly. Otherwise back off.
			if (System.nanoTime() - started > 60_000_000_000L) backoffMs = 2_000;
			else backoffMs = Math.min(backoffMs * 2, 60_000);

			try {
				Thread.sleep(backoffMs);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void runHelper(Path script) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(List.of(
				"powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-File", script.toString(),
				"-Filter", filter, "-ParentPid", Long.toString(ProcessHandle.current().pid())));
		pb.redirectInput(ProcessBuilder.Redirect.PIPE);
		Process p = pb.start();
		process = p;
		drainErrors(p.getErrorStream());

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;

			while (running && (line = reader.readLine()) != null) {
				handleLine(line);
			}
		} finally {
			p.destroy();
			p.waitFor();
			process = null;
		}
	}

	private void handleLine(String line) {
		line = line.strip();
		if (line.isEmpty() || line.charAt(0) != '{') return;

		JsonObject o;

		try {
			JsonElement el = JsonParser.parseString(line);
			if (!el.isJsonObject()) return;
			o = el.getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}

		long receivedNanos = System.nanoTime();
		latestReceivedNanos = receivedNanos;

		if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
			if (o.has("error")) LOG.debug("[Sky Lyrics] media reader: {}", o.get("error").getAsString());
			latest = null;
			return;
		}

		String status = str(o, "status");
		boolean playing = "Playing".equalsIgnoreCase(status);
		long pos = lng(o, "pos");
		long dur = lng(o, "dur");
		long updated = lng(o, "updated");
		long winNow = lng(o, "now");

		// Windows reports where the song was at "updated"; move that forward to now.
		if (playing && updated > 0 && winNow >= updated) {
			long elapsed = winNow - updated;
			if (elapsed < 6 * 3_600_000L) pos += elapsed;
		}

		if (dur > 0) pos = Math.min(pos, dur);
		boolean timelineKnown = dur > 0 || pos > 0;

		latest = new NowPlaying(str(o, "title"), str(o, "artist"), str(o, "album"),
				timelineKnown ? pos : -1, Math.max(0, dur), playing, receivedNanos);
	}

	private Path extractScript() throws IOException {
		Path dir = configDir.resolve("skylyrics");
		Files.createDirectories(dir);
		Path target = dir.resolve("media-reader.ps1");

		try (InputStream in = WindowsMediaSource.class.getResourceAsStream("/skylyrics/media.ps1")) {
			if (in == null) throw new IOException("media.ps1 missing from the mod jar");
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}

		return target;
	}

	private static void drainErrors(InputStream err) {
		Thread t = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
				String line;
				int logged = 0;

				while ((line = r.readLine()) != null) {
					if (logged++ < 20 && !line.isBlank()) LOG.warn("[Sky Lyrics] media reader: {}", line);
				}
			} catch (IOException ignored) {
				// process ended
			}
		}, "SkyLyrics media reader errors");
		t.setDaemon(true);
		t.start();
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static long lng(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull()) return 0;

		try {
			return e.getAsLong();
		} catch (RuntimeException ex) {
			return 0;
		}
	}
}
