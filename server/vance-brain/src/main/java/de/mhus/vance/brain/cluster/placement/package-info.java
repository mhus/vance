/**
 * Project placement — the one place that answers "which pod should run this
 * project". Ownership (who holds it now) stays in
 * {@code de.mhus.vance.shared.project.ProjectOwnership} and
 * {@code ProjectManagerService}; this package only decides where a project
 * that nobody owns <em>should</em> go, and dispatches the bring.
 *
 * <p>Design: {@code planning/project-placement-labels.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.cluster.placement;
