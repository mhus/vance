/**
 * Project domain — a single workspace inside a tenant.
 *
 * <p>A project optionally belongs to a {@code ProjectGroup} and references the
 * {@code TeamDocument}s that have access. References are by {@code name} (not
 * MongoDB id), per the CLAUDE.md entity convention.
 *
 * <p>Colocated: document + package-private repository + service.
 */
@NullMarked
package de.mhus.vance.shared.project;

import org.jspecify.annotations.NullMarked;
