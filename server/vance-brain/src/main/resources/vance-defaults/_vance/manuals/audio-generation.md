---
triggers: audio, speech, voice, text to speech, TTS, speak, vorlesen, Sprache erzeugen, Audiodatei, transcribe, transcription, speech to text, STT, transkribieren, Diktat, Untertitel, music, Musik, compose, komponieren, song, sound bed, jingle, audio_speak, audio_transcribe, audio_music, audio_voices, Hotblack
summary: How to produce speech, transcribe recordings, and compose music with the audio_* tool family — tool contracts, languages, voices, costs, anti-patterns.
---
# Audio — Hotblack

The `audio_*` family produces and consumes **audio documents**:
speech (TTS), transcripts (STT), and music / sound beds. Generated
files land in the document store like any other document — link them
with Markdown (`[listen](path)`), they are playable in the UI.

## The tools

| Tool | What it does | Typical latency |
|---|---|---|
| `audio_speak` | text → speech document (mp3/wav) | 2–10 s |
| `audio_transcribe` | audio document → transcript text | ~real-time factor |
| `audio_music` | prompt → music / sound clip | 30 s – a few min |
| `audio_voices` | list the voices a TTS model offers | instant |

All are **synchronous**: one call, one blocking wait, one document.
Do NOT loop to "improve" a result — every call costs money. For bulk
voice-over, spawn one child per item (Marvin plan).

## Language

- `audio_speak` speaks the **conversation language** by default — pass
  `language` only when the text differs from the chat language.
- `audio_transcribe` **auto-detects** — pass `language` to pin it
  (helps with short or noisy recordings).
- `audio_music` takes `language` for the lyrics; pass
  `language: "none"` for instrumental.

## Voices

Call `audio_voices` first when the user cares about the voice ("a
female German voice", "sounds like a newsreader"). It returns ids +
locales; pass the id as `voice` to `audio_speak`. Omit `voice` for the
model default. Persistent per-user voice presets are not a thing yet —
set `ai.hotblack.default-voice` in the Hotblack settings form instead.

## Costs (approximate)

- Speech: priced per character (fractions of a cent per sentence).
- Transcription: priced per second/minute of audio — a one-hour
  recording is real money; ask before transcribing very long files.
- Music: priced **per clip** (~$0.04 and up) — the most expensive tool
  here. Generate once. If the user wants variations, say what each
  regeneration costs.

The tool result reports `costUsd` when the vendor reports a price.

## Error handling

The tools return `{error, message, retryable}` — never a stack trace.

- `quota_exceeded` — daily/monthly limit; tell the user, don't retry.
- `content_policy` — the provider refused the prompt (music); rephrase,
  do not retry verbatim.
- `unsupported_language` / `invalid_choice` — model/voice mismatch;
  check `audio_voices` or pick another alias.
- `timeout` — retryable once; long recordings need patience.

## Anti-patterns

- Reading long documents aloud instead of linking them.
- Generating music to illustrate a point a sentence would make.
- Calling `audio_speak` per paragraph of a long text — one call per
  document, the strip handles the markdown.
- Transcribing audio the user only wants "summarized" — if the audio is
  a YouTube video, use `video_transcript` instead (it prefers the
  caption track and only falls back to ASR).