/**
 * Agent-facing tools for the UrsaEvents subsystem — currently a single
 * {@code event_fire} tool that lets the engine trigger a configured
 * event from the project scope without the bearer-token check used by
 * the public webhook surface. See {@code specification/events.md}.
 */
@NullMarked
package de.mhus.vance.brain.tools.ursaevent;

import org.jspecify.annotations.NullMarked;
