/**
 * Wire DTOs for the {@code /brain/{tenant}/backup/...} endpoints used by
 * {@code vance-liver}, the local backup tool ({@code planning/user-backup.md}).
 *
 * <p>The {@code backup} profile is the only thing that mints tokens that can
 * reach these surfaces; the wire format is therefore client-private, not a
 * public contract. Fields are kept minimal on purpose — the tool is a single
 * consumer, and adding generic fields for hypothetical future clients tends
 * to drift from what the tool actually needs.
 */
@NullMarked
package de.mhus.vance.api.backup;

import org.jspecify.annotations.NullMarked;
