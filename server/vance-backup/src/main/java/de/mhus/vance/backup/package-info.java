/**
 * Vance Backup — the local user-backup tool
 * ({@code planning/user-backup.md}).
 *
 * <p>Pure-JVM CLI: no Spring, no Mongo, no shared server modules. Talks to
 * {@code /brain/{tenant}/backup/...} on a Vance brain over HTTP using an
 * INTEGRATION token of the {@code backup} scope profile.
 *
 * <p>All state lives under {@code ~/.vancetope/backup/} on the workstation.
 * The foot tool uses {@code ~/.vancetope/} directly; this is the
 * planned split ({@code ~/.vancetope/foot/...} once foot migrates) — each
 * tool gets its own subdirectory so configs, tokens, and indexes never
 * collide between them.
 *
 * <p>Maintained at {@code planning/user-backup.md} (design), this package
 * (implementation), and {@code specification/public/backup.md} (canonical
 * spec).
 */
@NullMarked
package de.mhus.vance.backup;

import org.jspecify.annotations.NullMarked;
