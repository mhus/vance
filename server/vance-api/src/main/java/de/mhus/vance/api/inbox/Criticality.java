package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How important an inbox item is for the user — drives auto-answer
 * behaviour and notification routing.
 *
 * <ul>
 *   <li>{@link #LOW} — auto-default-Übernahme bei {@code default}
 *       gesetzt; Item entsteht direkt mit {@link InboxItemStatus#ANSWERED}.
 *       Email- und Mobile-Channels skippen LOW typischerweise.</li>
 *   <li>{@link #NORMAL} — User entscheidet. v2-Hook für
 *       Auto-Resolver-Worker (siehe spec).</li>
 *   <li>{@link #CRITICAL} — User entscheidet, prominent in der UI,
 *       durchbricht Quiet-Hours, audit-pflichtig.</li>
 * </ul>
 */
@GenerateTypeScript("inbox")
public enum Criticality {
    LOW,
    NORMAL,
    CRITICAL
}
