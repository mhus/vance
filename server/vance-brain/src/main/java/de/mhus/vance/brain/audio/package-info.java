/**
 * Pure-Java audio manipulation on existing document assets (the edit
 * counterpart to {@code de.mhus.vance.brain.image}): trim, mix,
 * concat, convert, probe. Engine is the host {@code ffmpeg}/{@code
 * ffprobe} pair wrapped by {@link FfmpegAudioEditor} — local,
 * deterministic, no provider, no quota. Tool wrappers live in
 * {@code de.mhus.vance.brain.tools.hotblack} (the {@code audio_*}
 * family); report: {@code planning/audio-edit-tools.md}.
 */
@NullMarked
package de.mhus.vance.brain.audio;

import org.jspecify.annotations.NullMarked;
