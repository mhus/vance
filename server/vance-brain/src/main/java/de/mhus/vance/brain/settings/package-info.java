/**
 * Admin REST endpoints for tenant-scoped settings.
 *
 * <p>All paths live under {@code /brain/{tenant}/admin/settings/...} so the
 * {@code BrainAccessFilter} enforces a valid JWT and that the caller's
 * {@code tid} claim matches the path {@code tenant}.
 */
@NullMarked
package de.mhus.vance.brain.settings;

import org.jspecify.annotations.NullMarked;
