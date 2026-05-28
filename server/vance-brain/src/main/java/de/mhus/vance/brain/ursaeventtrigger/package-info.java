/**
 * External event-trigger subsystem — REST endpoint and runtime logic
 * for {@code /brain/{tenant}/event/{project}/{event}} which spawns
 * Magrathea workflow runs from outside callers (webhooks, CI, IoT, …).
 *
 * <p>The package is separate from {@code brain/events/} (which handles
 * the unrelated server-to-client notification plumbing).
 *
 * <p>YAML parsing and the document-cascade lookup live in
 * {@code vance-shared/ursaevents}.
 *
 * <p>See {@code specification/events.md}.
 */
@NullMarked
package de.mhus.vance.brain.ursaeventtrigger;

import org.jspecify.annotations.NullMarked;
