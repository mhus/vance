/**
 * Project orchestration on the brain side: pod-affinity claim and
 * lifecycle. Wraps the persistence-level
 * {@link de.mhus.vance.shared.project.ProjectService} with brain-only
 * policy (which pod we are, when to take over, how to clean up
 * project-scoped assets).
 */
@NullMarked
package de.mhus.vance.brain.project;

import org.jspecify.annotations.NullMarked;
