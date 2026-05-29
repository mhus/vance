/**
 * Calendar-domain tools — currently {@link IcsToCalendarTool}, the
 * iCalendar (.ics) importer that lifts a calendar invite into a
 * Vance {@code kind: calendar} document.
 *
 * <p>The codec lives in {@code vance-shared} ({@code CalendarCodec}
 * + {@code CalendarDocument}); this package owns the LLM-facing
 * Tool wrappers. Spec: {@code specification/doc-kind-calendar.md}.
 */
@NullMarked
package de.mhus.vance.brain.tools.calendar;

import org.jspecify.annotations.NullMarked;
