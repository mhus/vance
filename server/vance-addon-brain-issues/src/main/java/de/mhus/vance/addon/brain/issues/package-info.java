/**
 * Issues addon — first-party Vance application ({@code app: issues}).
 *
 * <p>A lightweight GitHub-Issues-style tracker: {@code kind: issue} pages with
 * a stable monotonic number (#42), an {@code open}/{@code closed} state field
 * (not a folder), labels + assignee, an {@code archive/} path to clear old
 * issues out of the active tracker, and a comment thread built on the existing
 * {@link de.mhus.vance.shared.document.DocumentNote} subsystem.
 *
 * <p>Distinct from Kanban (spatial board / column position) — Issues is an
 * item-lifecycle tracker (discussion, open/closed, stable references). Framed
 * as Vance's own bug/idea tracker, not a Jira/Linear replacement. Future
 * synergy: Fook-triaged tickets could land here (see planning/app-issues.md §10).
 *
 * <p>Persistence goes exclusively through
 * {@link de.mhus.vance.shared.document.DocumentService}.
 */
@NullMarked
package de.mhus.vance.addon.brain.issues;

import org.jspecify.annotations.NullMarked;
