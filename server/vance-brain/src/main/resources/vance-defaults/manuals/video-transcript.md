---
triggers: video, YouTube, transcript, transcription, captions, Untertitel, subtitles, what is said in this video, what does this video say, fasse das Video zusammen, summarise the video, action items from this video, extract from video, ASR, speech to text, whisper
summary: Fetch the spoken-word transcript of a YouTube video — captions when available, Whisper ASR as a seamless fallback.
---
# Tool — `video_transcript`

Get the spoken text of a YouTube video. Two stages, tried in order:

1. **Captions** (seconds, free): manual subtitles preferred, falls
   back to auto-generated. No audio download.
2. **ASR fallback** (minutes): when the video has no captions at
   all, the tool downloads the audio with `yt-dlp` and transcribes
   it with `faster-whisper`. Live progress messages keep the user
   informed during the long run.

Use this whenever the user asks "what is said in this video",
"summarise this YouTube link", "extract the action items from this
recording" — no need to ask them to paste a transcript.

## When to use this

Trigger phrases (German + English):

- "Was wird in https://youtube.com/… gesagt?"
- "Fasse dieses Video zusammen"
- "Action items aus dieser Konferenz-Aufzeichnung"
- "Was sagt der Sprecher zu Thema X?"
- "Transcribe / transcript / Untertitel / Whisper"

The user typically pastes a YouTube URL. Don't ask them to copy
the transcript manually — call this tool, then formulate the
answer yourself.

## Parameters

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `url` | string | yes | YouTube URL (any common form) or bare 11-char video id |
| `language` | string | no | Comma-separated BCP-47 codes, e.g. `"de"` or `"de,en"`. Default `"en,de"`. First available wins for captions. The first entry is also passed as a hint to Whisper (or `"auto"` for auto-detect) |
| `fallback` | string | no | `"auto"` (default) — captions, then ASR. `"captions"` — captions only, error out if none. `"asr"` — skip captions, go straight to Whisper |
| `asrModel` | string | no | Whisper model: `tiny`, `base`, `small` (default), `medium`, `large-v3`, `large-v3-turbo`. Bigger = more accurate, slower |
| `timestamps` | boolean | no | Default `false`. When `true`, each line is prefixed with `[hh:mm:ss]` — useful for citing specific moments |

Accepted URL shapes: `youtube.com/watch?v=…`, `youtu.be/…`,
`youtube.com/embed/…`, `youtube.com/shorts/…`, `youtube.com/live/…`,
`m.youtube.com/…`, `youtube-nocookie.com/embed/…`, or a bare id.

## Returns

```
{
  videoId:           "YNavwk7qk24",
  language:          "Deutsch" | "de",   // human-readable name (captions) or code (ASR)
  languageCode:      "de",
  source:            "manual" | "asr-youtube" | "asr-whisper-small",
  segmentCount:      342,
  durationSec:       1742.5,
  transcriptionSec:  87.4,                // only present in ASR responses
  contentLength:     12834,
  truncated:         false,
  text:              "<plain text, newline-separated>"
}
```

Text is truncated past 50 000 characters; `contentLength` reports
the full size.

## Stage choice — what the agent should think about

- **Default** (`fallback="auto"`): the user just wants the content,
  any source is fine.
- **Cheap & deterministic**: if the user explicitly says "only use
  captions", set `fallback="captions"`.
- **Skip captions** (`fallback="asr"`): rarely useful — only when
  the captions are known to be garbage and you want clean Whisper
  output regardless of cost.

The ASR stage is **non-trivial in cost** — a 60-minute video on
`small` takes ~10-15 minutes wall-clock on a CPU host. The tool
emits progress messages so the user can watch it advance; you
don't need to.

## Live progress

While the ASR stage runs, the user sees status pings in the
progress side-channel:

- `FETCH: Downloading audio for video <id> (yt-dlp)…`
- `INFO: Audio ready (12.4 MB). Transcribing with Whisper 'small'…`
- `INFO: Transcribed 00:01:30 / 00:08:42 (17%)` — every ~30 audio-
  seconds, with running percentage
- `INFO: Done — 138 segments, 522.4 s of audio, 87.4 s of compute.`

No action needed from you — Vance ships this through the
user-progress channel automatically.

## Examples

**Basic — auto language, auto fallback**:
```
video_transcript(url="https://www.youtube.com/watch?v=YNavwk7qk24")
```

**Force German captions, no ASR**:
```
video_transcript(
  url="https://youtu.be/YNavwk7qk24",
  language="de",
  fallback="captions"
)
```

**Force ASR with a bigger model**:
```
video_transcript(
  url="https://www.youtube.com/watch?v=YNavwk7qk24",
  fallback="asr",
  asrModel="large-v3-turbo"
)
```

**With timestamps for citing**:
```
video_transcript(
  url="https://www.youtube.com/watch?v=YNavwk7qk24",
  timestamps=true
)
```
Returns lines like `[00:03:42] So the key insight here is …` —
you can cite "(at 3:42 the speaker says …)" in your answer.

## Anti-patterns

- **Don't ask the user to paste the transcript** when they gave you
  a YouTube URL. Call this tool.
- **Don't call this for Vimeo, podcasts, or arbitrary media URLs** —
  YouTube only. The tool fails with a clear error.
- **Don't request `timestamps=true` for a plain summary task.**
  Timestamps burn tokens. Use them only when the user asks for
  citations or wants to jump to a specific moment.
- **Don't ask for `large-v3` "to be safe"** when `small` would do.
  `small` is the sweet spot for German + English; `large-v3-turbo`
  is the next sensible step. `large-v3` itself is slow on CPU.
- **Don't summarise inside the tool call** — fetch the text, then
  summarise yourself. That's how Vance keeps tools composable.

## Failure modes

The tool throws `ToolException` for each of these — the message
tells you which:

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Could not extract a YouTube video id" | URL isn't YouTube / is malformed | Ask user to double-check the link |
| "No captions available … fallback is set to 'captions'" | Explicit captions-only and no caption track | Retry with `fallback="auto"` to enable ASR |
| "yt-dlp failed (exit …)" | YouTube blocked the host IP / bot detection / age-restricted | Operator concern; tell the user honestly |
| "Whisper transcription failed" | Python / `faster-whisper` missing on host | Operator concern — local setup needs `pip install faster-whisper` |
| "Whisper transcription timed out" | Hour-long video on the wrong model | Retry with a smaller `asrModel` |

## Not in this iteration

- Vimeo, generic media URLs, audio-only podcasts → still need
  separate plumbing.
- Speaker diarization ("who said what") — neither captions nor
  faster-whisper carry it.
- Translation — the tool fetches in the speaker's language. Vance
  does post-translation in the answer itself, not in the tool.
