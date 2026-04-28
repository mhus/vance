/**
 * Wire-contract DTOs for the read-only insights inspector.
 *
 * <p>Served by {@code de.mhus.vance.brain.insights.InsightsAdminController}
 * under {@code /brain/{tenant}/admin/sessions} and
 * {@code /brain/{tenant}/admin/processes}. The view is diagnostic — no
 * mutation endpoints belong here.
 */
@NullMarked
package de.mhus.vance.api.insights;

import org.jspecify.annotations.NullMarked;
