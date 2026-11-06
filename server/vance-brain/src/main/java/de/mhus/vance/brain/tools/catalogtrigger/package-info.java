/**
 * Tools of the polling triggers of the service catalog — {@code catalog_poll} asks the service,
 * {@code catalog_poll_commit} remembers what was taken care of. Called by the workflow that is generated for a
 * polling trigger; see {@code specification/public/events.md} §2a.
 */
@NullMarked
package de.mhus.vance.brain.tools.catalogtrigger;

import org.jspecify.annotations.NullMarked;
