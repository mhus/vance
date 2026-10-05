/**
 * The {@code audio_*} tool family (Hotblack): {@code audio_speak}
 * (TTS), {@code audio_transcribe} (STT), {@code audio_music}
 * (music / non-speech audio) and {@code audio_voices} (voice listing).
 * Thin wrappers over {@code de.mhus.vance.brain.hotblack.HotblackService}
 * that map the typed results / {@code HotblackException} into the
 * tool-result JSON shapes.
 */
@NullMarked
package de.mhus.vance.brain.tools.hotblack;

import org.jspecify.annotations.NullMarked;
