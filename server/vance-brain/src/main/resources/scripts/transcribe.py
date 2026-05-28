#!/usr/bin/env python3
# Vance Brain — faster-whisper wrapper for VideoTranscriptTool.
#
# Reads:
#   argv[1] = audio file path
#   argv[2] = model name (tiny|base|small|medium|large-v3|large-v3-turbo)
#   argv[3] = language code or "auto" for auto-detect (optional)
#
# Emits two stream channels:
#   - stderr  → progress lines, one per chunk processed, format
#                "PROGRESS chunkEndSec=<float> durationSec=<float>"
#   - stdout  → final JSON object with the full transcript
#
# The Java side reads stderr line-by-line for live progress and parses
# the single stdout JSON document at the end.
#
# Lazy-loads faster_whisper so import errors surface as a clean JSON
# error on stdout rather than a Python traceback in the tool result.

import json
import sys
import time


def fail(message):
    print(json.dumps({"error": message}), flush=True)
    sys.exit(1)


def main():
    if len(sys.argv) < 3:
        fail("usage: transcribe.py <audio> <model> [<language|auto>]")

    audio_path = sys.argv[1]
    model_name = sys.argv[2]
    language = None
    if len(sys.argv) > 3 and sys.argv[3] != "auto":
        language = sys.argv[3]

    try:
        from faster_whisper import WhisperModel
    except ImportError as e:
        fail(
            "faster-whisper not installed in this Python environment: "
            + str(e)
            + " — install with: pip install faster-whisper"
        )

    started = time.time()
    # int8 keeps the memory + speed envelope tight on CPU-only hosts
    # (Linux containers, Mac CPU fallback). On GPU-equipped hosts the
    # underlying CTranslate2 picks the matching dtype automatically.
    model = WhisperModel(model_name, device="auto", compute_type="int8")

    segments_iter, info = model.transcribe(
        audio_path,
        language=language,
        vad_filter=True,
    )

    total = info.duration if info.duration else 0.0
    fragments = []
    for seg in segments_iter:
        fragments.append({
            "start": seg.start,
            "end": seg.end,
            "text": seg.text.strip(),
        })
        # Progress to stderr — the Java side parses these lines.
        print(
            f"PROGRESS chunkEndSec={seg.end:.2f} durationSec={total:.2f}",
            file=sys.stderr,
            flush=True,
        )

    elapsed = time.time() - started
    result = {
        "language": info.language,
        "languageProbability": info.language_probability,
        "durationSec": total,
        "elapsedSec": elapsed,
        "modelName": model_name,
        "segments": fragments,
    }
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
