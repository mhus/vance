import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { parseCalendar, emptyCalendar } from './calendarCodec';
const props = withDefaults(defineProps(), {
    mode: 'editor',
    meta: () => ({}),
});
const { t, locale } = useI18n();
// ── Document resolution ─────────────────────────────────────────────
const resolvedDoc = computed(() => {
    if (props.doc)
        return props.doc;
    if (props.mode === 'inline') {
        try {
            return parseCalendar(props.content ?? '', 'application/yaml');
        }
        catch (e) {
            console.warn('CalendarView: failed to parse inline content', e);
            return emptyCalendar();
        }
    }
    const d = props.document;
    if (!d || !d.inlineText)
        return emptyCalendar();
    try {
        return parseCalendar(d.inlineText, d.mimeType ?? 'application/yaml');
    }
    catch (e) {
        console.warn('CalendarView: failed to parse embedded document', e);
        return emptyCalendar();
    }
});
// Inline channel is agenda-only — month-grid is too cramped for the
// chat-bubble width. Embedded defaults to month for the "calendar
// app" feel, with a quick switch to agenda.
const view = ref(props.mode === 'inline' ? 'agenda' : 'month');
/** Anchor date for the month view. Defaults to today; user can step. */
const monthAnchor = ref(startOfMonth(new Date()));
// ── Date utilities ──────────────────────────────────────────────────
function startOfDay(d) {
    const x = new Date(d);
    x.setHours(0, 0, 0, 0);
    return x;
}
function startOfMonth(d) {
    return new Date(d.getFullYear(), d.getMonth(), 1);
}
function addDays(d, n) {
    const x = new Date(d);
    x.setDate(x.getDate() + n);
    return x;
}
function addMonths(d, n) {
    const x = new Date(d);
    x.setMonth(x.getMonth() + n);
    return x;
}
function sameDay(a, b) {
    return a.getFullYear() === b.getFullYear()
        && a.getMonth() === b.getMonth()
        && a.getDate() === b.getDate();
}
/** Parse a CalendarEvent's `start` / `end` string into a Date. Accepts
 *  ISO date (`yyyy-MM-dd`) — interpreted as local midnight — and any
 *  ISO date-time. */
function parseEventDate(s) {
    if (!s)
        return null;
    // Date-only — treat as local midnight so the month grid puts the
    // event on the date the user typed regardless of viewer timezone.
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
        const [y, m, d] = s.split('-').map(Number);
        return new Date(y, m - 1, d);
    }
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? null : d;
}
function parseRrule(rrule) {
    const body = rrule.replace(/^RRULE:/i, '').trim();
    if (!body)
        return null;
    const spec = { freq: 'DAILY', interval: 1, byday: [], until: null, count: null };
    let freqSet = false;
    for (const part of body.split(';')) {
        const [k, v] = part.split('=').map(s => s?.trim());
        if (!k || v === undefined)
            continue;
        switch (k.toUpperCase()) {
            case 'FREQ':
                if (v === 'DAILY' || v === 'WEEKLY' || v === 'MONTHLY' || v === 'YEARLY') {
                    spec.freq = v;
                    freqSet = true;
                }
                break;
            case 'INTERVAL': {
                const n = parseInt(v, 10);
                if (n > 0)
                    spec.interval = n;
                break;
            }
            case 'BYDAY':
                spec.byday = v.split(',').map(s => s.trim().toUpperCase())
                    .filter(s => /^(?:[+-]?\d+)?(MO|TU|WE|TH|FR|SA|SU)$/.test(s))
                    .map(s => s.replace(/^[+-]?\d+/, ''));
                break;
            case 'UNTIL':
                spec.until = parseUntilDate(v);
                break;
            case 'COUNT': {
                const n = parseInt(v, 10);
                if (n > 0)
                    spec.count = n;
                break;
            }
        }
    }
    return freqSet ? spec : null;
}
function parseUntilDate(v) {
    // RFC 5545 UNTIL can be `yyyymmdd` or `yyyymmddTHHmmssZ`.
    const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})Z?)?$/.exec(v);
    if (m) {
        const [, y, mo, d, hh, mm, ss] = m;
        if (hh !== undefined) {
            // The regex makes ss optional inside the time group — guard
            // against undefined explicitly (?? on `+ss` would always see
            // NaN, which is not nullish, so the fallback was unreachable).
            const secs = ss === undefined ? 0 : +ss;
            return new Date(Date.UTC(+y, +mo - 1, +d, +hh, +mm, secs));
        }
        return new Date(+y, +mo - 1, +d, 23, 59, 59);
    }
    // Tolerate ISO format too.
    const d = new Date(v);
    return Number.isNaN(d.getTime()) ? null : d;
}
const WEEKDAY_RRULE = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];
/**
 * Expand a recurring event into concrete occurrences whose start date
 * falls in [rangeStart, rangeEnd]. Returns a list of effective start
 * dates; the renderer pairs them back with the event's other fields.
 *
 * Non-recurring events just yield [start] if it's in range.
 */
function expandEvent(ev, rangeStart, rangeEnd) {
    const baseStart = parseEventDate(ev.start);
    if (!baseStart)
        return [];
    if (!ev.recurrence) {
        return (baseStart >= rangeStart && baseStart <= rangeEnd) ? [baseStart] : [];
    }
    const spec = parseRrule(ev.recurrence);
    if (!spec) {
        return (baseStart >= rangeStart && baseStart <= rangeEnd) ? [baseStart] : [];
    }
    const out = [];
    // Hard caps so a malformed rule can't loop forever.
    const MAX_ITERATIONS = 2_000;
    const MAX_OUTPUT = 500;
    let cursor = new Date(baseStart);
    let counter = 0;
    let emitted = 0;
    for (let i = 0; i < MAX_ITERATIONS; i++) {
        if (spec.until && cursor > spec.until)
            break;
        if (spec.count !== null && counter >= spec.count)
            break;
        if (cursor > rangeEnd)
            break;
        let occurs;
        if (spec.freq === 'WEEKLY' && spec.byday.length > 0) {
            // Expand the byday list relative to the cursor's week.
            const weekStart = startOfWeek(cursor);
            occurs = spec.byday.map(day => {
                const idx = WEEKDAY_RRULE.indexOf(day);
                if (idx < 0)
                    return null;
                const candidate = addDays(weekStart, idx);
                candidate.setHours(cursor.getHours(), cursor.getMinutes(), cursor.getSeconds(), cursor.getMilliseconds());
                return candidate;
            }).filter((d) => d !== null && d >= baseStart);
        }
        else {
            occurs = [new Date(cursor)];
        }
        for (const occ of occurs) {
            if (spec.until && occ > spec.until)
                continue;
            if (spec.count !== null && counter >= spec.count)
                break;
            if (occ >= rangeStart && occ <= rangeEnd) {
                out.push(occ);
                if (++emitted >= MAX_OUTPUT)
                    return out;
            }
            counter++;
        }
        // Step the cursor to the next interval window.
        switch (spec.freq) {
            case 'DAILY':
                cursor = addDays(cursor, spec.interval);
                break;
            case 'WEEKLY':
                cursor = addDays(cursor, 7 * spec.interval);
                break;
            case 'MONTHLY':
                cursor = addMonths(cursor, spec.interval);
                break;
            case 'YEARLY':
                cursor = addMonths(cursor, 12 * spec.interval);
                break;
        }
    }
    return out;
}
function startOfWeek(d) {
    const x = startOfDay(d);
    x.setDate(x.getDate() - x.getDay()); // Sunday-based to match WEEKDAY_RRULE
    return x;
}
const agendaDays = computed(() => props.mode === 'inline' ? 14 : 30);
const agendaOccurrences = computed(() => {
    const today = startOfDay(new Date());
    const horizon = addDays(today, agendaDays.value);
    const rows = [];
    for (const ev of resolvedDoc.value.events) {
        const starts = expandEvent(ev, today, horizon);
        const baseStart = parseEventDate(ev.start);
        const baseEnd = parseEventDate(ev.end ?? undefined);
        const duration = (baseStart && baseEnd) ? (baseEnd.getTime() - baseStart.getTime()) : null;
        for (const s of starts) {
            rows.push({
                event: ev,
                start: s,
                end: duration !== null ? new Date(s.getTime() + duration) : null,
            });
        }
    }
    rows.sort((a, b) => a.start.getTime() - b.start.getTime());
    return rows;
});
/** Group agenda occurrences by day for the section headers. */
const agendaGrouped = computed(() => {
    const groups = new Map();
    for (const row of agendaOccurrences.value) {
        const dayKey = startOfDay(row.start).getTime();
        let g = groups.get(dayKey);
        if (!g) {
            g = { day: new Date(dayKey), items: [] };
            groups.set(dayKey, g);
        }
        g.items.push(row);
    }
    return Array.from(groups.values()).sort((a, b) => a.day.getTime() - b.day.getTime());
});
const monthGrid = computed(() => {
    const anchor = monthAnchor.value;
    const monthStart = startOfMonth(anchor);
    const gridStart = startOfWeek(monthStart);
    const monthEnd = startOfMonth(addMonths(monthStart, 1));
    // 6 rows × 7 days covers every month layout in the Gregorian calendar.
    const gridEnd = addDays(gridStart, 41);
    // Expand events once over the visible grid.
    const cells = [];
    const today = startOfDay(new Date());
    const occByDay = new Map();
    for (const ev of resolvedDoc.value.events) {
        const starts = expandEvent(ev, gridStart, addDays(gridEnd, 1));
        const baseStart = parseEventDate(ev.start);
        const baseEnd = parseEventDate(ev.end ?? undefined);
        const duration = (baseStart && baseEnd) ? (baseEnd.getTime() - baseStart.getTime()) : null;
        for (const s of starts) {
            const row = {
                event: ev,
                start: s,
                end: duration !== null ? new Date(s.getTime() + duration) : null,
            };
            const key = startOfDay(s).getTime();
            const arr = occByDay.get(key);
            if (arr)
                arr.push(row);
            else
                occByDay.set(key, [row]);
        }
    }
    for (let i = 0; i < 42; i++) {
        const date = addDays(gridStart, i);
        const key = startOfDay(date).getTime();
        const occ = occByDay.get(key) ?? [];
        occ.sort((a, b) => a.start.getTime() - b.start.getTime());
        cells.push({
            date,
            inMonth: date >= monthStart && date < monthEnd,
            isToday: sameDay(date, today),
            occurrences: occ,
        });
    }
    return cells;
});
function monthHeaderLabel(d) {
    return new Intl.DateTimeFormat(locale.value, { month: 'long', year: 'numeric' }).format(d);
}
function weekdayLabels() {
    // Sunday-based to match WEEKDAY_RRULE / startOfWeek.
    const sunday = new Date(2024, 0, 7); // 2024-01-07 was a Sunday
    const fmt = new Intl.DateTimeFormat(locale.value, { weekday: 'short' });
    return Array.from({ length: 7 }, (_, i) => fmt.format(addDays(sunday, i)));
}
function dayLabel(d) {
    return new Intl.DateTimeFormat(locale.value, {
        weekday: 'long', day: 'numeric', month: 'long',
    }).format(d);
}
function timeLabel(d, allDay) {
    if (!d)
        return '';
    if (allDay)
        return t('documents.calendar.allDay');
    return new Intl.DateTimeFormat(locale.value, {
        hour: '2-digit', minute: '2-digit',
    }).format(d);
}
function rangeLabel(row) {
    if (row.event.allDay) {
        if (row.end && !sameDay(row.start, row.end)) {
            const fmt = new Intl.DateTimeFormat(locale.value, { day: 'numeric', month: 'short' });
            return `${fmt.format(row.start)} – ${fmt.format(row.end)} (${t('documents.calendar.allDay')})`;
        }
        return t('documents.calendar.allDay');
    }
    const startT = timeLabel(row.start, false);
    if (row.end && !sameDay(row.start, row.end)) {
        const fmt = new Intl.DateTimeFormat(locale.value, {
            day: 'numeric', month: 'short',
            hour: '2-digit', minute: '2-digit',
        });
        return `${startT} – ${fmt.format(row.end)}`;
    }
    if (row.end) {
        return `${startT} – ${timeLabel(row.end, false)}`;
    }
    return startT;
}
// ── Navigation ──────────────────────────────────────────────────────
function gotoToday() {
    monthAnchor.value = startOfMonth(new Date());
}
function prevMonth() {
    monthAnchor.value = addMonths(monthAnchor.value, -1);
}
function nextMonth() {
    monthAnchor.value = addMonths(monthAnchor.value, 1);
}
// ── Color resolution ────────────────────────────────────────────────
/** Map a free-form color string (palette name or CSS color) to a CSS
 *  color value. Palette names are deliberately limited so calendars
 *  stay visually coherent. */
function colorFor(ev) {
    const c = ev.color?.trim().toLowerCase();
    if (!c)
        return 'hsl(var(--p))';
    switch (c) {
        case 'blue': return '#3b82f6';
        case 'green': return '#10b981';
        case 'red': return '#ef4444';
        case 'orange': return '#f59e0b';
        case 'yellow': return '#eab308';
        case 'purple': return '#a855f7';
        case 'pink': return '#ec4899';
        case 'teal': return '#14b8a6';
        case 'gray':
        case 'grey': return '#6b7280';
        default: return ev.color ?? 'hsl(var(--p))';
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_withDefaultsArg = (function (t) { return t; })({
    mode: 'editor',
    meta: () => ({}),
});
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['cal-view-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-header']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-cell--today']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-date']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['cal', `cal--${__VLS_ctx.mode}`]) },
});
if (__VLS_ctx.mode !== 'inline') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-toolbar" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-views" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.mode !== 'inline'))
                    return;
                __VLS_ctx.view = 'month';
            } },
        type: "button",
        ...{ class: (['cal-view-btn', { 'cal-view-btn--active': __VLS_ctx.view === 'month' }]) },
    });
    (__VLS_ctx.t('documents.calendar.viewMonth'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.mode !== 'inline'))
                    return;
                __VLS_ctx.view = 'agenda';
            } },
        type: "button",
        ...{ class: (['cal-view-btn', { 'cal-view-btn--active': __VLS_ctx.view === 'agenda' }]) },
    });
    (__VLS_ctx.t('documents.calendar.viewAgenda'));
    if (__VLS_ctx.view === 'month') {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "cal-nav" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.prevMonth) },
            type: "button",
            ...{ class: "cal-nav-btn" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.gotoToday) },
            type: "button",
            ...{ class: "cal-nav-btn cal-today-btn" },
        });
        (__VLS_ctx.t('documents.calendar.today'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (__VLS_ctx.nextMonth) },
            type: "button",
            ...{ class: "cal-nav-btn" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "cal-month-label" },
        });
        (__VLS_ctx.monthHeaderLabel(__VLS_ctx.monthAnchor));
    }
}
if (__VLS_ctx.view === 'month' && __VLS_ctx.mode !== 'inline') {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-month" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-month-header" },
    });
    for (const [w] of __VLS_getVForSourceType((__VLS_ctx.weekdayLabels()))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (w),
            ...{ class: "cal-weekday" },
        });
        (w);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-month-grid" },
    });
    for (const [cell, idx] of __VLS_getVForSourceType((__VLS_ctx.monthGrid))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (idx),
            ...{ class: ([
                    'cal-month-cell',
                    { 'cal-month-cell--out': !cell.inMonth, 'cal-month-cell--today': cell.isToday }
                ]) },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "cal-month-date" },
        });
        (cell.date.getDate());
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "cal-month-events" },
        });
        for (const [occ, oi] of __VLS_getVForSourceType((cell.occurrences.slice(0, 3)))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                key: (oi),
                ...{ class: "cal-month-event" },
                ...{ style: ({ borderLeftColor: __VLS_ctx.colorFor(occ.event) }) },
                title: (occ.event.title + ' — ' + __VLS_ctx.rangeLabel(occ)),
            });
            if (!occ.event.allDay) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "cal-month-time" },
                });
                (__VLS_ctx.timeLabel(occ.start, false));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "cal-month-title" },
            });
            (occ.event.title);
        }
        if (cell.occurrences.length > 3) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "cal-month-more" },
            });
            (cell.occurrences.length - 3);
        }
    }
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "cal-agenda" },
    });
    if (__VLS_ctx.agendaGrouped.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "cal-empty" },
        });
        (__VLS_ctx.t('documents.calendar.empty'));
    }
    for (const [g, gi] of __VLS_getVForSourceType((__VLS_ctx.agendaGrouped))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (gi),
            ...{ class: "cal-agenda-day" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "cal-agenda-day-header" },
        });
        (__VLS_ctx.dayLabel(g.day));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "cal-agenda-items" },
        });
        for (const [occ, oi] of __VLS_getVForSourceType((g.items))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (oi),
                ...{ class: "cal-agenda-item" },
                ...{ style: ({ borderLeftColor: __VLS_ctx.colorFor(occ.event) }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "cal-agenda-time" },
            });
            (__VLS_ctx.rangeLabel(occ));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "cal-agenda-body" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "cal-agenda-title" },
            });
            (occ.event.title);
            if (occ.event.location) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "cal-agenda-meta" },
                });
                (occ.event.location);
            }
            if (occ.event.attendees.length > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "cal-agenda-meta" },
                });
                (occ.event.attendees.join(', '));
            }
            if (occ.event.notes) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "cal-agenda-notes" },
                });
                (occ.event.notes);
            }
            if (occ.event.tags.length > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "cal-agenda-tags" },
                });
                for (const [t] of __VLS_getVForSourceType((occ.event.tags))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        key: (t),
                        ...{ class: "cal-tag" },
                    });
                    (t);
                }
            }
        }
    }
}
/** @type {__VLS_StyleScopedClasses['cal']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-views']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-view-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-view-btn--active']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-view-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-view-btn--active']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-nav']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-today-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-label']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-header']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-weekday']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-cell']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-cell--out']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-cell--today']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-date']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-events']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-event']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-time']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-title']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-month-more']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-empty']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-day']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-day-header']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-items']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-item']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-time']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-body']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-title']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-meta']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-meta']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-notes']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-agenda-tags']} */ ;
/** @type {__VLS_StyleScopedClasses['cal-tag']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            t: t,
            view: view,
            monthAnchor: monthAnchor,
            agendaGrouped: agendaGrouped,
            monthGrid: monthGrid,
            monthHeaderLabel: monthHeaderLabel,
            weekdayLabels: weekdayLabels,
            dayLabel: dayLabel,
            timeLabel: timeLabel,
            rangeLabel: rangeLabel,
            gotoToday: gotoToday,
            prevMonth: prevMonth,
            nextMonth: nextMonth,
            colorFor: colorFor,
        };
    },
    __typeProps: {},
    props: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
    props: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=CalendarView.vue.js.map