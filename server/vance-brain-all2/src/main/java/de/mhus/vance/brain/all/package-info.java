/**
 * Dev-bundle entry-point module. Contains {@link de.mhus.vance.brain.all.VanceBrainAll2Application}
 * — a thin subclass of {@link de.mhus.vance.brain.VanceBrainApplication} that
 * runs as the IDE launch target. The bundle's pom pulls in every first-party
 * addon (slideshow, calendar, kanban, ...) so Spring's auto-configuration
 * scan finds them on the classpath without any per-addon wiring code here.
 */
@NullMarked
package de.mhus.vance.brain.all;

import org.jspecify.annotations.NullMarked;
