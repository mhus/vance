/**
 * Journal addon — first-party Vance application.
 *
 * <p>Implements {@code app: journal}: a folder-as-diary container of
 * {@code kind: journal-entry} pages, one per calendar day
 * ({@code entries/<YYYY>/<YYYY-MM-DD>.md}), with calendar navigation,
 * mood + tags, a streak/mood/tag {@code _stats.yaml} and an
 * "on this day" retrospective. Date-anchored reflective prose — a
 * first-class Vance artefact, distinct from the workflow-state Kanban
 * board and the free-tree Workbook.
 *
 * <p>Entries carry a small typed front-matter ({@code date}, {@code mood})
 * owned by {@link de.mhus.vance.addon.brain.journal.JournalEntryCodec};
 * the prose body is edited with the shared block editor in body-only
 * mode. Persistence goes exclusively through
 * {@link de.mhus.vance.shared.document.DocumentService} (data
 * sovereignty — the journal owns no MongoDB collection of its own).
 *
 * <p>Loaded by Spring Boot via {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@NullMarked
package de.mhus.vance.addon.brain.journal;

import org.jspecify.annotations.NullMarked;
