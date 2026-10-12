package de.mhus.vance.shared.llmusage;

/**
 * What kind of model call a ledger row describes. One ledger for all
 * three, because a second collection would need a second reader in the
 * report and a merge — which is exactly the state that left image cost
 * invisible: {@code image_call_records} was written for a year and never
 * read by {@code LlmUsageReportService}.
 *
 * <p>The units differ per kind, which is why the discriminator exists at
 * all: {@link #CHAT}, {@link #EMBEDDING} and {@link #DECISION} are priced
 * per token from a rate snapshot; {@link #IMAGE} and {@link #AUDIO} carry
 * a flat per-call amount instead — see {@link #flatPriced()}.
 */
public enum UsageKind {

    /** Chat / completion round-trip, priced per input+output token. */
    CHAT,

    /** Embedding batch, priced per input token. One row per batch, not per chunk. */
    EMBEDDING,

    /** Image generation, priced per image and quality tier. */
    IMAGE,

    /**
     * Audio generation / processing (Hotblack): TTS, transcription, and
     * music. Priced per character, per second of audio, or per clip —
     * never per token, which is why it is its own kind.
     */
    AUDIO,

    /**
     * Decision call (Zem, System-One models): priced per input token like
     * {@link #EMBEDDING}, but the unit that matters is the number of
     * decisions, which rides the {@code images} counter of the bucket
     * (one write may book many decisions — bulk callers aggregate).
     */
    DECISION;

    /**
     * Whether a call of this kind is priced by a flat per-call amount the
     * caller already knows (vendor-reported or catalog estimate) rather
     * than by tokens × rate. For these kinds the ledger books the amount
     * as-is, and a missing amount means "unpriced" — there is no rate
     * snapshot to fall back on.
     */
    public boolean flatPriced() {
        return this == IMAGE || this == AUDIO;
    }
}
