/**
 * Jaglan dispatcher — the brain-side half of mounted documents.
 *
 * <p>{@link de.mhus.vance.brain.jaglan.JaglanService} implements the
 * {@code JaglanPort} that {@code DocumentService} calls for paths under
 * {@code _ext/}. Without this bean in the context there is no mount support at
 * all and mounted paths resolve to nothing — which is the normal state for any
 * process that loads {@code vance-shared} without the brain, anus among them.
 *
 * <p>Division of labour, deliberately narrow at the centre:
 *
 * <ul>
 *   <li>{@link de.mhus.vance.brain.jaglan.JaglanSourceFactory} — resolves
 *       {@code jaglan.mount.<name>.*} into instances, per project, cached.</li>
 *   <li>{@link de.mhus.vance.brain.jaglan.JaglanCapabilitiesCache} — holds what
 *       each source says about itself, with a peek that never fetches so
 *       folder listings stay cheap.</li>
 *   <li>{@link de.mhus.vance.brain.jaglan.JaglanService} — finds the instance
 *       behind a mount name and translates protocol failures into refusal
 *       versus outage.</li>
 *   <li>{@code JaglanShellService} in {@code vance-shared} — keeps the
 *       metadata shell rows in step. It sits there, not here, because it
 *       writes {@code DocumentDocument} rows.</li>
 * </ul>
 *
 * <p>Same build as {@code de.mhus.vance.brain.centauri} and
 * {@code de.mhus.vance.brain.zarniwoop}: protocol SPI in the toolpack,
 * project-scoped factory, dispatcher on top. Concept and decisions:
 * {@code planning/jaglan-mounted-docs.md}.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.brain.jaglan;

import org.jspecify.annotations.NullMarked;
