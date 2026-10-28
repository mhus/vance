---
triggers: audio edit, cut audio, trim, schneiden, kuerzen, fade, mix, mischen, music bed, Hintergrundmusik, concat, zusammenfuegen, join clips, crossfade, convert audio, umwandeln, mp3, wav, normalize, loudness, resample, audio_info, audio_trim, audio_mix, audio_concat, audio_convert
summary: Edit existing audio documents (probe, cut, mix, join, convert) with the local audio_info / audio_trim / audio_mix / audio_concat / audio_convert tools — workflow, overwrite rules, limits.
requires-capabilities: audio-tools
---
# Audio editing

Five tools edit existing audio **documents** with ffmpeg — instant, free, no
AI and no quota involved:

| Tool | What it does |
|---|---|
| `audio_info` | probe duration / format of an audio document |
| `audio_trim` | cut a window out of an audio document, optional fades |
| `audio_mix` | mix two documents (narration + music bed) |
| `audio_concat` | join clips in order, optional crossfade |
| `audio_convert` | re-encode to mp3/wav, resample, downmix, normalise |

## Workflow

1. `audio_info` first: learn the duration before planning cuts.
2. Plan with numbers: cut windows, fade lengths, music-bed placement.
3. Without `targetPath` the source is overwritten (document versioning archives
   the prior version). Prefer an explicit `targetPath` when the user may still
   want the original.
4. `audio_mix` closes the generation loop: `audio_speak` (narration, the base) +
   `audio_music` (music bed, the overlay), `overlayGain` 0.2-0.5,
   `duckOverlay=true` so the music ducks under the voice (see manual
   `audio-generation` for the generating side).
5. `audio_concat` needs a `targetPath` and never overwrites its clips.

## Limits and errors

Limits (per scope, configurable): 50 MB input, 4 h duration, 50 MB output.
Errors carry `error` / `message` / `retryable` like the generation tools.
