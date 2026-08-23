package de.mhus.vance.shared.llmusage;

/**
 * What kind of model call a ledger row describes. One ledger for all
 * three, because a second collection would need a second reader in the
 * report and a merge — which is exactly the state that left image cost
 * invisible: {@code image_call_records} was written for a year and never
 * read by {@code LlmUsageReportService}.
 *
 * <p>The units differ per kind, which is why the discriminator exists at
 * all: {@link #CHAT} and {@link #EMBEDDING} are priced per token,
 * {@link #IMAGE} per generated image.
 */
public enum UsageKind {

    /** Chat / completion round-trip, priced per input+output token. */
    CHAT,

    /** Embedding batch, priced per input token. One row per batch, not per chunk. */
    EMBEDDING,

    /** Image generation, priced per image and quality tier. */
    IMAGE
}
