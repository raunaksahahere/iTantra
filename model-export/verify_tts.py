#!/usr/bin/env python3
"""
Drives the exported int8 ONNX pair exactly as the Kotlin FastPitchTts does, and writes a
WAV so the result can actually be listened to.

Mirrors the app's path deliberately: same longest-match tokenisation against
tokens.json, same two-stage call, same tensor layouts. If this produces speech, the
contract the app relies on is correct; if it produces noise, the bug is in the export
rather than somewhere on the phone.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort


def tokenize(text: str, symbols: list[str]) -> list[int]:
    """Longest-match over the symbol table — the same rule FastPitchTts.tokenize uses."""
    # First id wins on duplicates, matching the Kotlin putIfAbsent.
    lookup: dict[str, int] = {}
    for i, s in enumerate(symbols):
        lookup.setdefault(s, i)

    longest = max((len(s) for s in lookup), default=1)
    ids: list[int] = []
    i = 0
    skipped = []
    while i < len(text):
        for n in range(min(longest, len(text) - i), 0, -1):
            chunk = text[i:i + n]
            if chunk in lookup:
                ids.append(lookup[chunk])
                i += n
                break
        else:
            skipped.append(text[i])
            i += 1
    if skipped:
        print(f"  unmapped characters skipped: {''.join(skipped)!r}")
    return ids


def write_wav(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    pcm = np.clip(audio, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    data = pcm.tobytes()
    with open(path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + len(data)))
        f.write(b"WAVEfmt ")
        f.write(struct.pack("<IHHIIHH", 16, 1, 1, sample_rate, sample_rate * 2, 2, 16))
        f.write(b"data")
        f.write(struct.pack("<I", len(data)))
        f.write(data)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, required=True, help="directory of exported files")
    ap.add_argument("--lang", required=True)
    ap.add_argument("--text", required=True)
    ap.add_argument("--sample-rate", type=int, default=22050)
    args = ap.parse_args()

    d = args.dir
    symbols = json.loads((d / f"fastpitch-{args.lang}.tokens.json").read_text(encoding="utf-8"))
    print(f"symbols: {len(symbols)}")

    ids = tokenize(args.text, symbols)
    print(f"text   : {args.text!r}")
    print(f"tokens : {len(ids)} -> {ids[:20]}{'...' if len(ids) > 20 else ''}")
    if not ids:
        print("FAIL: no tokens produced")
        return 1

    fp = ort.InferenceSession(str(d / f"fastpitch-{args.lang}.int8.onnx"),
                              providers=["CPUExecutionProvider"])
    hg = ort.InferenceSession(str(d / f"hifigan-{args.lang}.int8.onnx"),
                              providers=["CPUExecutionProvider"])
    print(f"fastpitch in : {[(i.name, i.shape) for i in fp.get_inputs()]}")
    print(f"fastpitch out: {[(o.name, o.shape) for o in fp.get_outputs()]}")
    print(f"hifigan   in : {[(i.name, i.shape) for i in hg.get_inputs()]}")
    print(f"hifigan   out: {[(o.name, o.shape) for o in hg.get_outputs()]}")

    tokens = np.array([ids], dtype=np.int64)
    mel = fp.run(None, {fp.get_inputs()[0].name: tokens})[0]
    print(f"mel    : {mel.shape}  range [{mel.min():.2f}, {mel.max():.2f}]")

    audio = hg.run(None, {hg.get_inputs()[0].name: mel.astype(np.float32)})[0]
    audio = np.squeeze(audio)
    print(f"audio  : {audio.shape}  range [{audio.min():.3f}, {audio.max():.3f}]")

    seconds = audio.size / args.sample_rate
    rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
    peak = float(np.max(np.abs(audio)))
    print(f"duration: {seconds:.2f}s   rms {rms:.4f}   peak {peak:.4f}")

    out = d / f"sample-{args.lang}.wav"
    write_wav(out, audio.astype(np.float32), args.sample_rate)
    print(f"wrote   : {out}")

    # A working vocoder gives audible level and a plausible duration for the token count.
    problems = []
    if peak < 0.01:
        problems.append(f"near-silent output (peak {peak:.4f})")
    if rms < 0.001:
        problems.append(f"no energy (rms {rms:.5f})")
    if seconds < 0.2:
        problems.append(f"implausibly short ({seconds:.2f}s for {len(ids)} tokens)")
    if not np.all(np.isfinite(audio)):
        problems.append("non-finite samples")

    if problems:
        print("SUSPECT: " + "; ".join(problems))
        return 1
    print("PASS: audio looks well-formed — listen to the WAV to judge quality")
    return 0


if __name__ == "__main__":
    sys.exit(main())
