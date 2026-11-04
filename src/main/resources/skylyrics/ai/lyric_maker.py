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

def record_loopback(out_wav, max_seconds, stop_event):
    import numpy as np
    import soundcard as sc
    import soundfile as sf

    speaker = sc.default_speaker()
    mic = sc.get_microphone(id=str(speaker.name), include_loopback=True)
    rate = 48000
    chunks = []
    started = time.time()
    last_report = 0

    with mic.recorder(samplerate=rate, channels=2, blocksize=4096) as rec:
        while not stop_event.is_set() and time.time() - started < max_seconds:
            data = rec.record(numframes=rate // 4)
            chunks.append(np.asarray(data, dtype=np.float32))
            elapsed = int(time.time() - started)
            if elapsed != last_report:
                last_report = elapsed
                progress(0, "listening %d:%02d" % (elapsed // 60, elapsed % 60))

    audio = np.concatenate(chunks) if chunks else np.zeros((0, 2), dtype=np.float32)
    sf.write(out_wav, audio, rate, subtype="FLOAT")
    return len(audio) / float(rate)


def watch_stdin(stop_event):
    try:
        for line in sys.stdin:
            if line.strip().lower() == "stop":
                stop_event.set()
                return
    except Exception:
        pass
    stop_event.set()  # stdin closed: the game went away


def watch_parent(pid, stop_event, quit_event):
    if not pid:
        return
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        SYNCHRONIZE = 0x00100000
        handle = kernel32.OpenProcess(SYNCHRONIZE, False, int(pid))
        if not handle:
            return
        while not quit_event.is_set():
            if kernel32.WaitForSingleObject(handle, 1000) == 0:  # parent exited
                stop_event.set()
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
            stop_event = threading.Event()
            quit_event = threading.Event()
            threading.Thread(target=watch_stdin, args=(stop_event,), daemon=True).start()
            threading.Thread(target=watch_parent, args=(a.parent_pid, stop_event, quit_event), daemon=True).start()
            fd, wav = tempfile.mkstemp(prefix="skylyrics-", suffix=".wav")
            os.close(fd)
            try:
                seconds = record_loopback(wav, a.max, stop_event)
                if seconds < 5:
                    raise RuntimeError("heard less than 5 seconds of sound")
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
