package de.mhus.vance.api.slartibartfast;

/**
 * Discriminator for an outstanding inbox dialog. Tells the engine
 * how to interpret a {@link de.mhus.vance.api.inbox.AnswerPayload}
 * arriving on {@link ArchitectState#getPendingInboxItemId()}.
 *
 * <p>Only one inbox dialog is in flight at a time —
 * {@link #NONE} means no inbox is currently being awaited.
 */
public enum PendingInboxKind {
    /** No inbox dialog in flight. */
    NONE,

    /** {@link ConfirmationMode#ASK_LOW_CONF}: the inbox item lists
     *  low-confidence assumed criteria for user approval; on
     *  answer, accepted entries flip origin to
     *  {@link CriterionOrigin#USER_CONFIRMED}. */
    CONFIRMATION,

    /** {@link EscalationMode#ASK_USER}: the inbox item asks the
     *  user whether to retry (reset recovery budget) or fail
     *  after the max-recovery cap was hit. */
    ESCALATION,
}
