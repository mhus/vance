/**
 * GTD addon — first-party Vance application ({@code app: gtd}), modelled on
 * the <b>Things</b> paradigm (Cultured Code), not on Kanban.
 *
 * <p>Actions ({@code kind: action}) live under a suite folder. Their
 * <b>bucket</b> — Inbox / Today / Upcoming / Anytime / Someday — is not a
 * folder but a <b>derived function</b> of the action's {@code when} attribute
 * (+ optional {@code deadline}) and today's date; see
 * {@link de.mhus.vance.addon.brain.gtd.GtdBucketResolver}. A scheduled action
 * "slides" from Upcoming into Today on its start date with no file move. The
 * only folder that carries meaning is {@code inbox/} (unprocessed capture);
 * {@code projects/<name>/} groups project actions.
 *
 * <p>Persistence goes exclusively through
 * {@link de.mhus.vance.shared.document.DocumentService} (data sovereignty —
 * the GTD addon owns no MongoDB collection of its own).
 *
 * <p>Loaded by Spring Boot via {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@NullMarked
package de.mhus.vance.addon.brain.gtd;

import org.jspecify.annotations.NullMarked;
