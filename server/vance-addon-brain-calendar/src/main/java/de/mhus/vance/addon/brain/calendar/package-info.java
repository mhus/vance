/**
 * Calendar addon for the Vance Brain — first-party.
 *
 * <p>Bundles the Calendar Vance Application (per-folder planner with
 * lanes, RRULE expansion, conflict detection and Gantt rendering),
 * the {@code calendar_*} server tools, and the Calendar document
 * Kind codec (per-document YAML/JSON events). Loaded by Spring Boot
 * via {@code META-INF/spring/.../AutoConfiguration.imports} pointing
 * at {@link de.mhus.vance.addon.brain.calendar.CalendarAddon}.
 *
 * <p>Also home to the {@code timeline} Kind ({@link TimelineCodec},
 * {@link TimelineKindHandler}, {@link TimelineCreateTool}). Same
 * addon because both kinds are about time and share the codec
 * conventions and the {@link ScalarCoercion} hazard; deliberately
 * <em>not</em> the same data model — a calendar is appointments on
 * the Gregorian calendar, a timeline is periods and points on an axis
 * the document declares, which is what lets it hold deep time and a
 * minute-resolution reconstruction alike.
 */
@NullMarked
package de.mhus.vance.addon.brain.calendar;

import org.jspecify.annotations.NullMarked;
