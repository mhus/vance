package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle status of an inbox item.
 *
 * <ul>
 *   <li>{@link #PENDING} — awaits user (or auto-resolver) action;
 *       the originating process may be BLOCKED on it.</li>
 *   <li>{@link #ANSWERED} — user (or auto-default) provided an
 *       answer; originating process has been notified.</li>
 *   <li>{@link #DISMISSED} — user closed it without a substantive
 *       answer (Process treats this as a skip).</li>
 *   <li>{@link #ARCHIVED} — explicit User-Aktion oder
 *       Auto-Archive nach N Tagen; aus dem Live-View
 *       herausgefiltert, im Audit-Trail erhalten.</li>
 * </ul>
 */
@GenerateTypeScript("inbox")
public enum InboxItemStatus {
    PENDING,
    ANSWERED,
    DISMISSED,
    ARCHIVED
}
