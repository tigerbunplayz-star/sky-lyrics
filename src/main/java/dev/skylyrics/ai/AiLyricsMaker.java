package dev.skylyrics.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.skylyrics.media.NowPlaying;

/**
 * Runs the optional AI lyric maker (a private Python with Demucs + faster-whisper, installed
 * on demand into %LOCALAPPDATA%\SkyLyrics\ai). It listens to the PC's sound while a song
 * plays, separates the vocals, recognises the words and writes a timed .lrc file.
 */
public final class AiLyricsMaker {
	private static final Logger LOG = LoggerFactory.getLogger("skylyrics");

	public enum State { NOT_INSTALLED, INSTALLING, READY, LISTENING, WORKING, DONE, FAILED }

	private final Path home;
	private final Consumer<Path> onDone;
	private volatile State state;
	private volatile int percent;
	private volatile String message = "";
	private volatile Process process;
	private volatile String songKey;
	private volatile String songTitle = "";

	/** @param onDone called (on a background thread) with the .lrc file when lyrics are made */
	public AiLyricsMaker(Path home, Consumer<Path> onDone) {
		this.home = home;
		this.onDone = onDone;
		this.state = isInstalled() ? State.READY : State.NOT_INSTALLED;
		Runtime.getRuntime().addShutdownHook(new Thread(this::kill, "SkyLyrics AI shutdown"));
	}

	/** %LOCALAPPDATA%\SkyLyrics\ai (outside the Minecraft folder, so profile exports stay small). */
	public static Path defaultHome(Path fallback) {
		String local = System.getenv("LOCALAPPDATA");
		return local != null && !local.isBlank() ? Path.of(local, "SkyLyrics", "ai") : fallback;
	}

	public boolean isInstalled() {
		return Files.isRegularFile(home.resolve("ready.txt")) && Files.isRegularFile(python());
	}

	public State state() {
		return state;
	}

	public boolean isBusy() {
		return state == State.INSTALLING || state == State.LISTENING || state == State.WORKING;
	}

	public boolean isListening() {
		return state == State.LISTENING;
	}

	/** Track key of the song being listened to / worked on. */
	public String songKey() {
		return songKey;
	}

	/** One line for the menu. */
	public String status() {
		return switch (state) {
			case NOT_INSTALLED -> "not installed";
			case INSTALLING -> "installing " + percent + "% – " + message;
			case READY -> "ready";
			case LISTENING -> "listening to " + songTitle + " " + message.replace("listening", "").strip();
			case WORKING -> percent + "% – " + message;
			case DONE -> "done: " + message;
			case FAILED -> "failed: " + message;
		};
	}

	/** Install (or repair) the AI in the background. */
	public synchronized void install(String model) {
		if (isBusy()) return;

		try {
			Files.createDirectories(home);
			extract("setup_ai.ps1");
			extract("lyric_maker.py");
		} catch (IOException e) {
			fail("couldn't prepare " + home + ": " + e.getMessage());
			return;
		}

		state = State.INSTALLING;
		percent = 0;
		message = "starting";
		run(List.of(powershell(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
				"-File", home.resolve("setup_ai.ps1").toString(), "-Dir", home.toString(), "-Model", model), line -> {
			if (line.startsWith("DONE")) {
				state = State.READY;
				message = "";
			}
		}, () -> {
			if (state == State.INSTALLING) state = isInstalled() ? State.READY : State.FAILED;
		});
	}

	/**
	 * Start listening to the song playing now. The lyrics are written to {@code outFile} once
	 * {@link #stopListening()} is called (when the song ends) and the AI has finished.
	 */
	public synchronized boolean start(NowPlaying np, long positionMs, Path outFile, String language, String model, long maxSeconds) {
		if (isBusy() || !isInstalled()) return false;

		try {
			extract("lyric_maker.py");
			Files.createDirectories(outFile.getParent());
		} catch (IOException e) {
			fail(e.getMessage());
			return false;
		}

		try {
			Files.deleteIfExists(stopFile());
		} catch (IOException ignored) {
			// it will be overwritten
		}

		songKey = np.trackKey();
		songTitle = np.title();
		state = State.LISTENING;
		percent = 0;
		message = "";

		List<String> cmd = new ArrayList<>(List.of(python().toString(), home.resolve("lyric_maker.py").toString(),
				"--make", "--out", outFile.toString(), "--offset", Long.toString(Math.max(0, positionMs)),
				"--max", Long.toString(Math.max(30, maxSeconds)), "--models", home.resolve("models").toString(),
				"--model", model, "--lang", language, "--title", np.title(), "--artist", np.artist(),
				"--parent-pid", Long.toString(ProcessHandle.current().pid()), "--stop-file", stopFile().toString()));

		run(cmd, line -> {
			if (line.startsWith("DONE ")) {
				Path file = Path.of(line.substring(5).strip());
				state = State.DONE;
				message = file.getFileName().toString();
				onDone.accept(file);
			}
		}, () -> {
			if (state == State.LISTENING || state == State.WORKING) fail("the AI stopped unexpectedly");
		});
		return true;
	}

	/** The song ended (or the player pressed Stop): stop listening and let the AI work. */
	public void stopListening() {
		Process p = process;
		if (p == null || state != State.LISTENING) return;
		state = State.WORKING;
		message = "starting";

		try {
			Files.writeString(stopFile(), "stop");
		} catch (IOException e) {
			LOG.warn("[Sky Lyrics] Couldn't write the AI stop file: {}", e.toString());
		}

		try { // backup signal
			OutputStream in = p.getOutputStream();
			in.write("stop\n".getBytes(StandardCharsets.UTF_8));
			in.flush();
		} catch (IOException ignored) {
			// the stop file does the job
		}
	}

	/** Abort whatever is running. */
	public void cancel() {
		kill();
		state = isInstalled() ? State.READY : State.NOT_INSTALLED;
		message = "";
	}

	private void kill() {
		Process p = process;
		if (p != null) p.descendants().forEach(ProcessHandle::destroy);
		if (p != null) p.destroy();
	}

	private void run(List<String> command, Consumer<String> onLine, Runnable onExit) {
		Thread t = new Thread(() -> {
			List<String> errors = new ArrayList<>();

			try {
				ProcessBuilder pb = new ProcessBuilder(command);
				pb.environment().put("PYTHONIOENCODING", "utf-8");
				pb.environment().put("PYTHONUNBUFFERED", "1");
				pb.environment().put("HF_HUB_DISABLE_TELEMETRY", "1");
				Process p = pb.start();
				process = p;
				drain(p.getErrorStream(), errors);

				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					String line;

					while ((line = r.readLine()) != null) {
						line = line.strip();

						if (line.startsWith("PROGRESS ")) {
							String[] parts = line.split(" ", 3);
							try {
								percent = Integer.parseInt(parts[1]);
							} catch (NumberFormatException ignored) {
								// keep the old value
							}
							message = parts.length > 2 ? parts[2] : "";
							if (state == State.LISTENING && percent > 0) state = State.WORKING;
						} else if (line.startsWith("ERROR ")) {
							fail(line.substring(6));
						} else {
							onLine.accept(line);
						}
					}
				}

				int code = p.waitFor();
				if (code != 0 && state != State.FAILED && state != State.READY && state != State.NOT_INSTALLED) {
					fail(errors.isEmpty() ? "exit code " + code : errors.get(errors.size() - 1));
				}
			} catch (IOException e) {
				fail(e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				process = null;
				onExit.run();
				if (!errors.isEmpty() && state == State.FAILED) {
					LOG.warn("[Sky Lyrics] AI output (last lines):\n{}", String.join("\n", errors.subList(Math.max(0, errors.size() - 15), errors.size())));
				}
			}
		}, "SkyLyrics AI");
		t.setDaemon(true);
		t.start();
	}

	private void fail(String why) {
		state = State.FAILED;
		message = why == null ? "unknown error" : why;
		LOG.warn("[Sky Lyrics] AI lyric maker: {}", message);
	}

	private static void drain(InputStream err, List<String> keep) {
		Thread t = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (keep) {
						keep.add(line);
						if (keep.size() > 60) keep.remove(0);
					}
				}
			} catch (IOException ignored) {
				// process ended
			}
		}, "SkyLyrics AI errors");
		t.setDaemon(true);
		t.start();
	}

	private Path stopFile() {
		return home.resolve("stop.signal");
	}

	private Path python() {
		return home.resolve("python").resolve("python.exe");
	}

	private void extract(String name) throws IOException {
		try (InputStream in = AiLyricsMaker.class.getResourceAsStream("/skylyrics/ai/" + name)) {
			if (in == null) throw new IOException(name + " missing from the mod jar");
			Files.copy(in, home.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String powershell() {
		String root = System.getenv("SystemRoot");
		if (root != null) {
			Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			if (Files.isRegularFile(p)) return p.toString();
		}
		return "powershell.exe";
	}
}
