export interface CalendarEvent {
    /** Stable identifier (UUID). Auto-filled when missing on read. */
    id: string;
    /** Human-readable title. Required. */
    title: string;
    /** Start instant — ISO-8601 date or date-time. Required. */
    start: string;
    /** End instant. Optional; defaults to a zero-length point at `start`. */
    end?: string;
    /** All-day marker. When true, `start` / `end` are date-only. */
    allDay: boolean;
    /** Free-form location string. */
    location?: string;
    /** Attendee list. Free-form strings (names, emails, handles). */
    attendees: string[];
    /** RFC 5545 RRULE expression, e.g. `"FREQ=WEEKLY;BYDAY=MO,WE"`. */
    recurrence?: string;
    /** Display color (CSS keyword, hex, or palette name). */
    color?: string;
    /** Free-form tags for filtering within a calendar. */
    tags: string[];
    /** Long-form notes (markdown-ish, renderer-dependent). */
    notes?: string;
    /** Unknown event fields, re-emitted on save. */
    extra: Record<string, unknown>;
}
export interface CalendarDocument {
    /** Always `'calendar'`. */
    kind: string;
    /** Flat list of events. Order is preserved round-trip but not
     *  semantically meaningful — views sort chronologically. */
    events: CalendarEvent[];
    /** Unknown top-level fields, passthrough. */
    extra: Record<string, unknown>;
}
export declare class CalendarCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseCalendar(body: string, mimeType: string): CalendarDocument;
export declare function serializeCalendar(doc: CalendarDocument, mimeType: string): string;
export declare function isCalendarMime(mimeType: string | null | undefined): boolean;
export declare function emptyCalendar(): CalendarDocument;
//# sourceMappingURL=calendarCodec.d.ts.map