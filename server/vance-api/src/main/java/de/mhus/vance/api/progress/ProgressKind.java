package de.mhus.vance.api.progress;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Discriminator for {@link ProcessProgressNotification}.
 *
 * <p>Determines which payload field is populated:
 * <ul>
 *   <li>{@link #METRICS} → {@code metrics} payload set</li>
 *   <li>{@link #PLAN} → {@code plan} payload set</li>
 *   <li>{@link #STATUS} → {@code status} payload set</li>
 *   <li>{@link #REPLY} → {@code reply} payload set</li>
 * </ul>
 *
 * <p>{@code REPLY} is the only variant whose payload is also routed to
 * the parent process's pending inbox (when a parent exists) — the other
 * three are client-facing only. See
 * {@code planning/process-engine-reply-channel.md}.
 */
@GenerateTypeScript("progress")
public enum ProgressKind {
    METRICS,
    PLAN,
    STATUS,
    REPLY
}
