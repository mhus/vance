/**
 * Project maintenance — the service tasks that touch <em>every</em> entity a
 * project owns: counting its data, deleting it, and carrying its name to a new
 * one.
 *
 * <p>The unit of extension is one {@link
 * de.mhus.vance.shared.project.maintenance.ProjectDataHandler} per entity,
 * living next to the entity it answers for. Adding a collection means adding a
 * handler, not editing a switch — and forgetting one is caught rather than
 * silently tolerated, see the coverage probe in {@link
 * de.mhus.vance.shared.project.maintenance.ProjectMaintenanceService}.
 *
 * <p>Spec: {@code specification/public/project-maintenance.md}.
 */
@NullMarked
package de.mhus.vance.shared.project.maintenance;

import org.jspecify.annotations.NullMarked;
