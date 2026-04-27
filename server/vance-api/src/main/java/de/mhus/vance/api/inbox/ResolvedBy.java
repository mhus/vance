package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Who provided the answer / dismissal — for the audit trail.
 *
 * <ul>
 *   <li>{@link #USER} — a real human submitted the answer.</li>
 *   <li>{@link #AUTO_DEFAULT} — system auto-applied
 *       {@code payload.default} on a {@link Criticality#LOW} item
 *       at create-time.</li>
 *   <li>{@link #AUTO_RESOLVER} — (v2) an auto-resolver worker
 *       returned a {@link AnswerOutcome#DECIDED} answer.</li>
 *   <li>{@link #AUTO_ARCHIVE} — auto-archive Spring-job moved an
 *       answered/dismissed item to {@link InboxItemStatus#ARCHIVED}.</li>
 * </ul>
 */
@GenerateTypeScript("inbox")
public enum ResolvedBy {
    USER,
    AUTO_DEFAULT,
    AUTO_RESOLVER,
    AUTO_ARCHIVE
}
