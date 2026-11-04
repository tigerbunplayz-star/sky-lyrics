# Sky Lyrics - AI lyric maker.
#
# 1. Listens to the PC's sound output (WASAPI loopback) while a song plays, until it
#    receives "stop" on stdin.
# 2. Separates the singing from the instruments (Demucs). This only happens on the
#    temporary recording; the music you hear is never touched.
# 3. Recognises the sung words and when each one starts (faster-whisper).
# 4. Groups the words into lines and writes a timed .lrc file.
# The recording is deleted afterwards; nothing is uploaded.
#
# Talks to the mod with lines on stdout:  PROGRESS <0-100> <text> | DONE <file> | ERROR <text>
# Keep this file ASCII.

import argparse
import math
import os
import sys
import tempfile
import threading
import time
import traceback


def say(kind, *parts):
    try:
        sys.stdout.write(kind + " " + " ".join(str(p) for p in parts) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


def progress(pct, text):
    say("PROGRESS", int(max(0, min(100, pct))), text)


# ---------------------------------------------------------------------------- recording

class Stopper(object):
    """Stop signal: a 'stop' line on stdin, or the stop file appearing (more reliable)."""

    def __init__(self, stop_file):
        self.event = threading.Event()
        self.reason = ""
        self.stop_file = stop_file

    def stop(self, reason):
        if not self.event.is_set():
            self.reason = reason
            self.event.set()

    def is_set(self):
        if not self.event.is_set() and self.stop_file and os.path.exists(self.stop_file):
            self.stop("stop file")
        return self.event.is_set()


def fake_audio_chunks(path, rate, block):
    """CI only (SKYLYRICS_FAKE_AUDIO): plays a wav file back in real time instead of a sound card."""
    import numpy as np
    import soundfile as sf
    audio, sr = sf.read(path, dtype="float32", always_2d=True)
    if sr != rate:
        idx = np.arange(0, len(audio), sr / float(rate))
        audio = np.stack([np.interp(idx, np.arange(len(audio)), audio[:, c]) for c in range(audio.shape[1])], 1)
    if audio.shape[1] == 1:
        audio = np.repeat(audio, 2, 1)
    pos = 0
    while True:
        part = audio[pos:pos + block, :2]
        if len(part) < block:
            part = np.concatenate([part, np.zeros((block - len(part), 2), dtype=np.float32)])
        pos += block
        time.sleep(block / float(rate))
        yield part.astype(np.float32)


def record_loopback(out_wav, max_seconds, stopper):
    import numpy as np
    import soundfile as sf

    rate = 48000
    block = rate // 4
    chunks = []
    started = time.time()
    last_report = -1
    fake = os.environ.get("SKYLYRICS_FAKE_AUDIO")

    def loop(next_chunk, device):
        nonlocal last_report
        while not stopper.is_set():
            if time.time() - started >= max_seconds:
                stopper.stop("time limit")
                break
            chunks.append(np.asarray(next_chunk(), dtype=np.float32)[:, :2])
            elapsed = int(time.time() - started)
            if elapsed != last_report:
                last_report = elapsed
                progress(0, "listening %d:%02d" % (elapsed // 60, elapsed % 60))
        return device

    if fake:
        gen = fake_audio_chunks(fake, rate, block)
        device = loop(lambda: next(gen), "fake audio file")
    else:
        import soundcard as sc
        speaker = sc.default_speaker()
        mic = sc.get_microphone(id=str(speaker.name), include_loopback=True)
        with mic.recorder(samplerate=rate, channels=2, blocksize=4096) as rec:
            device = loop(lambda: rec.record(numframes=block), speaker.name)

    audio = np.concatenate(chunks) if chunks else np.zeros((0, 2), dtype=np.float32)
    sf.write(out_wav, audio, rate, subtype="FLOAT")
    seconds = len(audio) / float(rate)
    listened = time.time() - started
    level = float(np.sqrt(np.mean(audio ** 2))) if len(audio) else 0.0
    sys.stderr.write("recorded %.1f s in %.1f s, device=%s, stopped by %s, level=%.5f\n"
                     % (seconds, listened, device, stopper.reason, level))
    return seconds, listened, device, level


def watch_stdin(stopper):
    try:
        for line in sys.stdin:
            if line.strip().lower() == "stop":
                stopper.stop("stop command")
                return
    except Exception:
        pass
    # stdin closed or unavailable: don't stop because of that (the stop file / parent watch handle it)


def watch_parent(pid, stopper, quit_event):
    if not pid:
        return
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        SYNCHRONIZE = 0x00100000
        kernel32.OpenProcess.restype = ctypes.c_void_p
        handle = kernel32.OpenProcess(SYNCHRONIZE, False, int(pid))
        if not handle:
            return
        while not quit_event.is_set():
            if kernel32.WaitForSingleObject(ctypes.c_void_p(handle), 1000) == 0:  # parent exited
                stopper.stop("game closed")
                quit_event.set()
                os._exit(0)
    except Exception:
        pass


# ---------------------------------------------------------------------- vocal separation

def separate_vocals(in_wav, models_dir):
    """Returns (mono vocals at 16 kHz as float32 numpy array, duration seconds)."""
    import numpy as np
    import soundfile as sf
    import torch
    import julius
    from demucs.apply import apply_model
    from demucs.pretrained import get_model

    os.environ.setdefault("TORCH_HOME", os.path.join(models_dir, "torch"))
    torch.set_num_threads(max(1, (os.cpu_count() or 2) - 1))

    progress(3, "loading the vocal separator")
    model = get_model("htdemucs")
    model.eval()
    sr_model = model.samplerate

    audio, sr = sf.read(in_wav, dtype="float32", always_2d=True)
    x = torch.from_numpy(audio.T.copy())
    if x.shape[0] == 1:
        x = x.repeat(2, 1)
    x = x[:2]
    if sr != sr_model:
        x = julius.resample_frac(x, sr, sr_model)
    duration = x.shape[1] / float(sr_model)

    ref = x.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    x = (x - mean) / std

    vocals_idx = model.sources.index("vocals")
    chunk = int(30 * sr_model)
    overlap = int(1.5 * sr_model)
    total = x.shape[1]
    out = torch.zeros(total)
    weight = torch.zeros(total)
    start = 0

    with torch.no_grad():
        while start < total:
            end = min(total, start + chunk)
            a = max(0, start - overlap)
            b = min(total, end + overlap)
            part = apply_model(model, x[:, a:b][None], device="cpu", split=True, overlap=0.25, progress=False)[0]
            v = part[vocals_idx].mean(0) * std + mean
            n = b - a
            w = torch.ones(n)
            fade = min(overlap, n // 2)
            if fade > 0:
                ramp = torch.linspace(0, 1, fade)
                if a > 0:
                    w[:fade] = ramp
                if b < total:
                    w[-fade:] = ramp.flip(0)
            out[a:b] += v * w
            weight[a:b] += w
            start = end
            progress(5 + 45 * min(1.0, start / float(total)), "separating the vocals")

    vocals = out / weight.clamp(min=1e-6)
    vocals16 = julius.resample_frac(vocals[None], sr_model, 16000)[0]
    return vocals16.numpy().astype(np.float32), duration


# --------------------------------------------------------------------------- recognition

def transcribe(vocals16, duration, models_dir, model_size, language):
    from faster_whisper import WhisperModel

    progress(52, "loading the speech recogniser")
    model = WhisperModel(model_size, device="cpu", compute_type="int8",
                         download_root=os.path.join(models_dir, "whisper"))
    segments, info = model.transcribe(
        vocals16,
        language=None if language in (None, "", "auto") else language,
        vad_filter=True,
        word_timestamps=True,
        beam_size=5,
        condition_on_previous_text=False,
    )

    words = []
    for seg in segments:
        if seg.no_speech_prob > 0.7 and seg.avg_logprob < -1.0:
            continue
        for w in (seg.words or []):
            text = w.word.strip()
            if text and (w.probability is None or w.probability >= 0.15):
                words.append((float(w.start), float(w.end), text))
        progress(55 + 40 * min(1.0, seg.end / max(1.0, duration)), "writing down the words")
    return words, getattr(info, "language", language)


def words_to_lines(words, max_words=9, gap=0.8):
    lines = []
    cur = []
    for start, end, text in words:
        if cur:
            prev_end = cur[-1][1]
            prev_text = cur[-1][2]
            punct = prev_text[-1:] in ".,!?;:"
            if start - prev_end > gap or len(cur) >= max_words or (punct and len(cur) >= 3):
                lines.append(cur)
                cur = []
        cur.append((start, end, text))
    if cur:
        lines.append(cur)

    out = []
    for i, line in enumerate(lines):
        text = " ".join(t for _, _, t in line).strip()
        text = text.replace(" ,", ",").replace(" .", ".").replace(" ?", "?").replace(" !", "!")
        out.append((line[0][0], line[-1][1], text))

    # Whisper sometimes repeats a line over and over in quiet parts: keep at most 2 in a row.
    cleaned = []
    for item in out:
        if len(cleaned) >= 2 and cleaned[-1][2].lower() == item[2].lower() == cleaned[-2][2].lower():
            continue
        cleaned.append(item)
    return cleaned


def lrc_time(seconds):
    ms = max(0, int(round(seconds * 1000)))
    return "[%02d:%02d.%02d]" % (ms // 60000, (ms // 1000) % 60, (ms % 1000) // 10)


def write_lrc(path, lines, offset_ms, title, artist):
    off = offset_ms / 1000.0
    rows = []
    if artist:
        rows.append("[ar:%s]" % artist)
    if title:
        rows.append("[ti:%s]" % title)
    rows.append("[by:Sky Lyrics AI (Demucs + Whisper)]")
    for i, (start, end, text) in enumerate(lines):
        rows.append(lrc_time(start + off) + text)
        nxt = lines[i + 1][0] if i + 1 < len(lines) else None
        if nxt is None or nxt - end > 4.0:
            rows.append(lrc_time(end + 0.6 + off))  # pause: clear the words
    tmp = path + ".part"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    os.replace(tmp, path)


def process(in_wav, out_lrc, offset_ms, models_dir, model_size, language, title, artist):
    vocals16, duration = separate_vocals(in_wav, models_dir)
    if duration < 3:
        raise RuntimeError("the recording is too short")
    words, lang = transcribe(vocals16, duration, models_dir, model_size, language)
    lines = words_to_lines(words)
    if not lines:
        raise RuntimeError("no singing was recognised")
    write_lrc(out_lrc, lines, offset_ms, title, artist)
    progress(100, "done (%d lines, language %s)" % (len(lines), lang))
    say("DONE", out_lrc)


def warmup(models_dir, model_size):
    os.environ.setdefault("TORCH_HOME", os.path.join(models_dir, "torch"))
    progress(10, "downloading the vocal separator model")
    from demucs.pretrained import get_model
    get_model("htdemucs")
    progress(50, "downloading the speech model (%s)" % model_size)
    from faster_whisper import WhisperModel
    WhisperModel(model_size, device="cpu", compute_type="int8", download_root=os.path.join(models_dir, "whisper"))
    progress(100, "models ready")
    say("DONE", "warmup")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--make", action="store_true", help="record until 'stop', then process")
    p.add_argument("--process", metavar="WAV", help="process an existing recording")
    p.add_argument("--warmup", action="store_true")
    p.add_argument("--out", default="")
    p.add_argument("--offset", type=int, default=0, help="song position (ms) when recording started")
    p.add_argument("--max", type=float, default=900)
    p.add_argument("--models", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))
    p.add_argument("--model", default="small")
    p.add_argument("--lang", default="auto")
    p.add_argument("--title", default="")
    p.add_argument("--artist", default="")
    p.add_argument("--parent-pid", type=int, default=0)
    p.add_argument("--stop-file", default="")
    a = p.parse_args()

    os.makedirs(a.models, exist_ok=True)
    os.environ["HF_HOME"] = os.path.join(a.models, "hf")
    os.environ["TORCH_HOME"] = os.path.join(a.models, "torch")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

    try:
        if a.warmup:
            warmup(a.models, a.model)
            return
        if a.process:
            process(a.process, a.out, a.offset, a.models, a.model, a.lang, a.title, a.artist)
            return
        if a.make:
            stopper = Stopper(a.stop_file)
            if a.stop_file and os.path.exists(a.stop_file):
                os.remove(a.stop_file)
            quit_event = threading.Event()
            threading.Thread(target=watch_stdin, args=(stopper,), daemon=True).start()
            threading.Thread(target=watch_parent, args=(a.parent_pid, stopper, quit_event), daemon=True).start()
            fd, wav = tempfile.mkstemp(prefix="skylyrics-", suffix=".wav")
            os.close(fd)
            try:
                seconds, listened, device, level = record_loopback(wav, a.max, stopper)
                if seconds < 5:
                    raise RuntimeError("only %.1f s of sound came in during %.0f s of listening (device: %s, stopped by: %s)"
                                       % (seconds, listened, device, stopper.reason or "?"))
                if level < 1e-4:
                    raise RuntimeError("only silence was heard from '%s' - is Spotify playing through your default speakers/headphones?" % device)
                process(wav, a.out, a.offset, a.models, a.model, a.lang, a.title, a.artist)
            finally:
                try:
                    os.remove(wav)
                except OSError:
                    pass
            return
        p.print_help()
    except Exception as e:
        msg = str(e).replace("\n", " ")[:300]
        if "DLL load failed" in msg:
            msg += " (installing the Microsoft Visual C++ Redistributable usually fixes this)"
        traceback.print_exc(file=sys.stderr)
        say("ERROR", msg)
        sys.exit(1)


if __name__ == "__main__":
    main()
