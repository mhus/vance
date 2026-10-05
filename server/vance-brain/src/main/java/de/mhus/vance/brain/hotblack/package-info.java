/**
 * Hotblack — the audio stack (TTS, transcription, music): synchronous,
 * single-shot service + tool-set behind the {@code audio_*} tool
 * family. Mirrors {@code de.mhus.vance.brain.fenchurch} (images):
 * quota reserve, title generation, provider dispatch through
 * {@code de.mhus.vance.brain.ai.audio}, and the document-store commit.
 *
 * <p>Only the service and tools live here; the provider layer sits in
 * {@code de.mhus.vance.brain.ai.audio} and the ledger document in
 * {@code de.mhus.vance.shared.hotblack}.
 */
@NullMarked
package de.mhus.vance.brain.hotblack;

import org.jspecify.annotations.NullMarked;
