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
| `audio_info` | probe duration / format of an audio document | instant |
| `audio_trim` | cut a window out of an audio document | instant |
| `audio_mix` | mix two documents (narration + music bed) | instant |
| `audio_concat` | join clips in order, optional crossfade | instant |
| `audio_convert` | re-encode to mp3/wav, resample, normalise | instant |

The first four tools **generate** audio through providers; the last five **edit
existing audio documents locally (ffmpeg) — instant, free, no quota. The
generation tools are all **synchronous**: one call, one blocking wait, one document.
Do NOT loop to "improve" a result — every call costs money. For bulk
voice-over, spawn one child per item (Marvin plan).

## Editing audio

`audio_trim`, `audio_mix`, `audio_concat` and `audio_convert` edit existing
audio documents with local ffmpeg — instant, free, no AI involved. Workflow:

1. `audio_info` first: learn the duration before planning cuts.
2. Plan with numbers: cut windows, fade lengths, music-bed placement.
3. Without `targetPath` the source is overwritten (document versioning archives
   the prior version). Prefer an explicit `targetPath` when the user may still
   want the original.
4. `audio_mix` closes the generation loop: `audio_speak` (narration, the base) +
   `audio_music` (music bed, the overlay), `overlayGain` 0.2-0.5,
   `duckOverlay=true` so the music ducks under the voice.
5. `audio_concat` needs a `targetPath` and never overwrites its clips.

Limits (per scope, configurable): 50 MB input, 4 h duration, 50 MB output.
Errors carry `error` / `message` / `retryable` like the generation tools.

## Working with audio the user provides

- The user **drops an audio file into the chat** (or names one in the
  project): that is `audio_transcribe` material — the attachment hint
  carries the document id, pass it as `documentId`. Nothing else can
  consume audio yet (no audio understanding in chat).
- A **YouTube link** is `video_transcript`, not `audio_transcribe`
  (caption track first — cheaper, faster, better).
- **Long recordings:** tell the user cost and latency before you
  start (see Costs below). For hour-long material ask first whether
  they want the full transcript or a summary (transcribe, then
  summarize).
- The transcript is text: pass `path` to persist it as a document
  when it is long or should stay around; return it inline when the
  user just asked "what did they say".
- Generated audio is a document like any other — link it in your
  reply (`[listen](path)`), don't describe it at length.

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