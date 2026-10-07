/**
 * Wire-contract DTOs for the admin action REST endpoints — the generic
 * operator-action surface the Settings page's "Aktionen" tab renders.
 *
 * <p>Served by {@code de.mhus.vance.brain.admin.action.AdminActionController}
 * under {@code /brain/{tenant}/admin/actions/...}. Actions themselves
 * implement the brain-side {@code AdminAction} SPI; these DTOs are the
 * deliberately action-agnostic shapes (descriptor, run result, item)
 * that keep the client ignorant of concrete actions.
 */
@NullMarked
package de.mhus.vance.api.admin;

import org.jspecify.annotations.NullMarked;
