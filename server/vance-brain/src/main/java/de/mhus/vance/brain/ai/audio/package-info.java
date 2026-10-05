/**
 * Audio provider dispatch (Hotblack): text-to-speech, transcription,
 * and music/audio generation — parallel to
 * {@code de.mhus.vance.brain.ai.image} for images.
 *
 * <p>Providers implement {@link AiAudioModelProvider} and write
 * through {@code AudioDestinationStream} (TTS/music) or return a
 * {@link SttResult} (transcription); the dispatch lives in
 * {@link AiAudioService}. Providers never see the document model.
 */
@NullMarked
package de.mhus.vance.brain.ai.audio;

import org.jspecify.annotations.NullMarked;
