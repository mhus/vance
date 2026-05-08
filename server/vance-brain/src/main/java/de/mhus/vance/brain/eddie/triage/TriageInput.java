package de.mhus.vance.brain.eddie.triage;

import org.jspecify.annotations.Nullable;

/**
 * Input to {@link OutputTriageService#classify}. Bundles the worker
 * text with the metadata the heuristic and the LLM step both consult.
 *
 * @param text          the raw worker output that Eddie has to surface
 *                      to the user
 * @param outputHint    optional voluntary hint from the worker
 *                      ({@code VERBATIM} / {@code INBOX} / {@code FREE});
 *                      respected without further LLM check (skip path
 *                      from {@code planning/eddie-moderator-erweiterung.md})
 * @param workerEngine  worker engine name (e.g. {@code "arthur"},
 *                      {@code "ford"}) — used to compose the spoken
 *                      announcement default
 * @param voiceMode     {@code true} when Eddie's reply will be read
 *                      aloud (Eddie's default — see
 *                      {@code specification/eddie-engine.md} §5).
 *                      Drives different length thresholds: voice has
 *                      tighter VERBATIM / INBOX cutoffs because TTS
 *                      can't render long prose or any Markdown.
 */
public record TriageInput(
        String text,
        @Nullable String outputHint,
        @Nullable String workerEngine,
        boolean voiceMode) {

    /** Convenience accessor: outputHint normalised to upper case, or {@code null}. */
    public @Nullable String normalisedHint() {
        return outputHint == null || outputHint.isBlank()
                ? null
                : outputHint.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
