/**
 * Hotblack persistence — the audio-call ledger (TTS, transcription,
 * music).
 *
 * <p>Only the document lives here; the service that writes it
 * ({@code AudioCallTracker}) and everything else Hotblack does stay in
 * {@code vance-brain} with the AI stack they need. Same split and same
 * reason as {@code de.mhus.vance.shared.fenchurch}: these rows are
 * project-scoped, so the admin shell — which has {@code vance-shared}
 * and no brain — has to be able to see them when a project is deleted
 * or renamed.
 */
@NullMarked
package de.mhus.vance.shared.hotblack;

import org.jspecify.annotations.NullMarked;
