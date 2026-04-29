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
 * </ul>
 */
@GenerateTypeScript("progress")
public enum ProgressKind {
    METRICS,
    PLAN,
    STATUS
}
