package de.mhus.vance.brain.memory;

/**
 * Shared tag- and payload-keys for the topic-recompaction subsystem.
 *
 * <p>The {@link de.mhus.vance.brain.thinkengine.plan.PlanModeService}
 * hook writes these onto a freshly-created {@code RECOMPACTION_OFFER}
 * inbox item. The {@code RecompactionOfferAnsweredListener} reads them
 * back when the user accepts the offer and turns the stored range
 * coordinates into a {@link MemoryCompactionService#compactRange} call.
 *
 * <p>The constants live in {@code vance-brain.memory} (next to the
 * compactor) rather than in {@code vance-api}: only brain code emits
 * or consumes them — they never cross the API boundary, and adding an
 * {@code InboxItemType} variant would mean a TypeScript-DTO change and
 * a wire-protocol commitment we don't yet need. The tag-as-discriminator
 * approach keeps {@code APPROVAL} as the inbox-type (its yes/no UI is
 * exactly what we want) and treats {@link #TAG_INBOX_OFFER} as a brain-
 * private routing key.
 *
 * <p>See {@code planning/topic-recompaction.md} §6.
 */
public final class RecompactionTags {

    /** Discriminator tag on the inbox item itself (see §6 in the plan). */
    public static final String TAG_INBOX_OFFER = "RECOMPACTION_OFFER";

    /**
     * Prefix for the SYSTEM-marker tag inserted at the end of the
     * compacted range. The full tag is
     * {@code RECOMPACTION:<topicLabel>}.
     */
    public static final String TAG_MESSAGE_PREFIX = "RECOMPACTION:";

    // Payload keys carried on the inbox item — read back by the
    // answered-listener after the user accepts the offer.

    /** {@code Instant.toString()} — inclusive start of the recompaction range. */
    public static final String PAYLOAD_RANGE_START_AT = "rangeStartAt";

    /** {@code Instant.toString()} — inclusive end of the recompaction range. */
    public static final String PAYLOAD_RANGE_END_AT = "rangeEndAt";

    /** Human-readable label for the topic (used as the SYSTEM-marker tag suffix). */
    public static final String PAYLOAD_TOPIC_LABEL = "topicLabel";

    /** Diagnostic — number of plan todos at the time the offer was created. */
    public static final String PAYLOAD_TODO_COUNT = "todoCount";

    private RecompactionTags() {}
}
