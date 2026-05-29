<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { parseCalendar, type CalendarDocument, type CalendarEvent, emptyCalendar } from './calendarCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';

/**
 * Read-only renderer for `kind: calendar` documents.
 *
 * v1 modes (spec doc-kind-calendar.md §4):
 *   - `inline`   — agenda for the next 14 days, compact.
 *   - `embedded` — agenda + month view, switchable.
 *   - `editor`   — same as embedded; edits happen on the Source tab.
 *
 * Recurrence: a small RFC 5545 subset is expanded inline
 * (FREQ=DAILY|WEEKLY|MONTHLY|YEARLY, INTERVAL, BYDAY, UNTIL, COUNT).
 * Exotic rules pass through but only fire on `start`. Replacing the
 * mini-expander with `rrule.js` is a v2 drop-in.
 */
const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: CalendarDocument;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
  embedRef?: EmbedRef;
}>(), {
  mode: 'editor',
  meta: () => ({}),
});

const { t, locale } = useI18n();

// ── Document resolution ─────────────────────────────────────────────

const resolvedDoc = computed<CalendarDocument>(() => {
  if (props.doc) return props.doc;
  if (props.mode === 'inline') {
    try { return parseCalendar(props.content ?? '', 'application/yaml'); }
    catch (e) {
      console.warn('CalendarView: failed to parse inline content', e);
      return emptyCalendar();
    }
  }
  const d = props.document;
  if (!d || !d.inlineText) return emptyCalendar();
  try { return parseCalendar(d.inlineText, d.mimeType ?? 'application/yaml'); }
  catch (e) {
    console.warn('CalendarView: failed to parse embedded document', e);
    return emptyCalendar();
  }
});

// ── View state ──────────────────────────────────────────────────────

type ViewKind = 'agenda' | 'month';

// Inline channel is agenda-only — month-grid is too cramped for the
// chat-bubble width. Embedded defaults to month for the "calendar
// app" feel, with a quick switch to agenda.
const view = ref<ViewKind>(props.mode === 'inline' ? 'agenda' : 'month');

/** Anchor date for the month view. Defaults to today; user can step. */
const monthAnchor = ref<Date>(startOfMonth(new Date()));

// ── Date utilities ──────────────────────────────────────────────────

function startOfDay(d: Date): Date {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

function addDays(d: Date, n: number): Date {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

function addMonths(d: Date, n: number): Date {
  const x = new Date(d);
  x.setMonth(x.getMonth() + n);
  return x;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
      && a.getMonth() === b.getMonth()
      && a.getDate() === b.getDate();
}

/** Parse a CalendarEvent's `start` / `end` string into a Date. Accepts
 *  ISO date (`yyyy-MM-dd`) — interpreted as local midnight — and any
 *  ISO date-time. */
function parseEventDate(s: string | undefined): Date | null {
  if (!s) return null;
  // Date-only — treat as local midnight so the month grid puts the
  // event on the date the user typed regardless of viewer timezone.
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    const [y, m, d] = s.split('-').map(Number);
    return new Date(y, m - 1, d);
  }
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

// ── Recurrence expansion ────────────────────────────────────────────

interface RruleSpec {
  freq: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';
  interval: number;
  byday: string[];
  until: Date | null;
  count: number | null;
}

function parseRrule(rrule: string): RruleSpec | null {
  const body = rrule.replace(/^RRULE:/i, '').trim();
  if (!body) return null;
  const spec: RruleSpec = { freq: 'DAILY', interval: 1, byday: [], until: null, count: null };
  let freqSet = false;
  for (const part of body.split(';')) {
    const [k, v] = part.split('=').map(s => s?.trim());
    if (!k || v === undefined) continue;
    switch (k.toUpperCase()) {
      case 'FREQ':
        if (v === 'DAILY' || v === 'WEEKLY' || v === 'MONTHLY' || v === 'YEARLY') {
          spec.freq = v; freqSet = true;
        }
        break;
      case 'INTERVAL': {
        const n = parseInt(v, 10);
        if (n > 0) spec.interval = n;
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
        if (n > 0) spec.count = n;
        break;
      }
    }
  }
  return freqSet ? spec : null;
}

function parseUntilDate(v: string): Date | null {
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
function expandEvent(ev: CalendarEvent, rangeStart: Date, rangeEnd: Date): Date[] {
  const baseStart = parseEventDate(ev.start);
  if (!baseStart) return [];
  if (!ev.recurrence) {
    return (baseStart >= rangeStart && baseStart <= rangeEnd) ? [baseStart] : [];
  }
  const spec = parseRrule(ev.recurrence);
  if (!spec) {
    return (baseStart >= rangeStart && baseStart <= rangeEnd) ? [baseStart] : [];
  }

  const out: Date[] = [];
  // Hard caps so a malformed rule can't loop forever.
  const MAX_ITERATIONS = 2_000;
  const MAX_OUTPUT = 500;

  let cursor = new Date(baseStart);
  let counter = 0;
  let emitted = 0;

  for (let i = 0; i < MAX_ITERATIONS; i++) {
    if (spec.until && cursor > spec.until) break;
    if (spec.count !== null && counter >= spec.count) break;
    if (cursor > rangeEnd) break;

    let occurs: Date[];
    if (spec.freq === 'WEEKLY' && spec.byday.length > 0) {
      // Expand the byday list relative to the cursor's week.
      const weekStart = startOfWeek(cursor);
      occurs = spec.byday.map(day => {
        const idx = WEEKDAY_RRULE.indexOf(day);
        if (idx < 0) return null;
        const candidate = addDays(weekStart, idx);
        candidate.setHours(
          cursor.getHours(), cursor.getMinutes(),
          cursor.getSeconds(), cursor.getMilliseconds());
        return candidate;
      }).filter((d): d is Date => d !== null && d >= baseStart);
    } else {
      occurs = [new Date(cursor)];
    }

    for (const occ of occurs) {
      if (spec.until && occ > spec.until) continue;
      if (spec.count !== null && counter >= spec.count) break;
      if (occ >= rangeStart && occ <= rangeEnd) {
        out.push(occ);
        if (++emitted >= MAX_OUTPUT) return out;
      }
      counter++;
    }

    // Step the cursor to the next interval window.
    switch (spec.freq) {
      case 'DAILY': cursor = addDays(cursor, spec.interval); break;
      case 'WEEKLY': cursor = addDays(cursor, 7 * spec.interval); break;
      case 'MONTHLY': cursor = addMonths(cursor, spec.interval); break;
      case 'YEARLY': cursor = addMonths(cursor, 12 * spec.interval); break;
    }
  }
  return out;
}

function startOfWeek(d: Date): Date {
  const x = startOfDay(d);
  x.setDate(x.getDate() - x.getDay()); // Sunday-based to match WEEKDAY_RRULE
  return x;
}

// ── Agenda view ─────────────────────────────────────────────────────

interface OccurrenceRow {
  event: CalendarEvent;
  start: Date;
  end: Date | null;
}

const agendaDays = computed<number>(() => props.mode === 'inline' ? 14 : 30);

const agendaOccurrences = computed<OccurrenceRow[]>(() => {
  const today = startOfDay(new Date());
  const horizon = addDays(today, agendaDays.value);
  const rows: OccurrenceRow[] = [];
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
const agendaGrouped = computed<Array<{ day: Date; items: OccurrenceRow[] }>>(() => {
  const groups = new Map<number, { day: Date; items: OccurrenceRow[] }>();
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

// ── Month view ──────────────────────────────────────────────────────

interface MonthCell {
  date: Date;
  inMonth: boolean;
  isToday: boolean;
  occurrences: OccurrenceRow[];
}

const monthGrid = computed<MonthCell[]>(() => {
  const anchor = monthAnchor.value;
  const monthStart = startOfMonth(anchor);
  const gridStart = startOfWeek(monthStart);
  const monthEnd = startOfMonth(addMonths(monthStart, 1));
  // 6 rows × 7 days covers every month layout in the Gregorian calendar.
  const gridEnd = addDays(gridStart, 41);

  // Expand events once over the visible grid.
  const cells: MonthCell[] = [];
  const today = startOfDay(new Date());
  const occByDay = new Map<number, OccurrenceRow[]>();

  for (const ev of resolvedDoc.value.events) {
    const starts = expandEvent(ev, gridStart, addDays(gridEnd, 1));
    const baseStart = parseEventDate(ev.start);
    const baseEnd = parseEventDate(ev.end ?? undefined);
    const duration = (baseStart && baseEnd) ? (baseEnd.getTime() - baseStart.getTime()) : null;
    for (const s of starts) {
      const row: OccurrenceRow = {
        event: ev,
        start: s,
        end: duration !== null ? new Date(s.getTime() + duration) : null,
      };
      const key = startOfDay(s).getTime();
      const arr = occByDay.get(key);
      if (arr) arr.push(row);
      else occByDay.set(key, [row]);
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

function monthHeaderLabel(d: Date): string {
  return new Intl.DateTimeFormat(locale.value, { month: 'long', year: 'numeric' }).format(d);
}

function weekdayLabels(): string[] {
  // Sunday-based to match WEEKDAY_RRULE / startOfWeek.
  const sunday = new Date(2024, 0, 7); // 2024-01-07 was a Sunday
  const fmt = new Intl.DateTimeFormat(locale.value, { weekday: 'short' });
  return Array.from({ length: 7 }, (_, i) => fmt.format(addDays(sunday, i)));
}

function dayLabel(d: Date): string {
  return new Intl.DateTimeFormat(locale.value, {
    weekday: 'long', day: 'numeric', month: 'long',
  }).format(d);
}

function timeLabel(d: Date | null, allDay: boolean): string {
  if (!d) return '';
  if (allDay) return t('documents.calendar.allDay');
  return new Intl.DateTimeFormat(locale.value, {
    hour: '2-digit', minute: '2-digit',
  }).format(d);
}

function rangeLabel(row: OccurrenceRow): string {
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

function gotoToday(): void {
  monthAnchor.value = startOfMonth(new Date());
}
function prevMonth(): void {
  monthAnchor.value = addMonths(monthAnchor.value, -1);
}
function nextMonth(): void {
  monthAnchor.value = addMonths(monthAnchor.value, 1);
}

// ── Color resolution ────────────────────────────────────────────────

// ── External-calendar link builders ─────────────────────────────────
//
// Each occurrence in the rendered agenda gets a small "add to my
// calendar" button. We don't bridge to Google/Outlook/Apple via an
// API — we hand the user a one-click link that opens their existing
// calendar app with the event pre-filled. No OAuth, no sync,
// no backend.

interface ExternalLink {
  label: string;
  href: string;
  download?: string;
}

function googleCalendarUrl(ev: CalendarEvent, occStart: Date, occEnd: Date | null): string {
  const params = new URLSearchParams();
  params.set('action', 'TEMPLATE');
  params.set('text', ev.title);
  params.set('dates', googleDateRange(ev, occStart, occEnd));
  if (ev.notes) params.set('details', ev.notes);
  if (ev.location) params.set('location', ev.location);
  return 'https://calendar.google.com/calendar/render?' + params.toString();
}

function googleDateRange(ev: CalendarEvent, occStart: Date, occEnd: Date | null): string {
  // Google Calendar render URL date format:
  //   all-day:   YYYYMMDD/YYYYMMDD   (end is exclusive — add 1 day)
  //   timed:     YYYYMMDDTHHMMSSZ/YYYYMMDDTHHMMSSZ (always UTC)
  if (ev.allDay) {
    const start = formatYmd(occStart);
    const endExcl = occEnd ? addDays(occEnd, 1) : addDays(occStart, 1);
    return `${start}/${formatYmd(endExcl)}`;
  }
  const end = occEnd ?? new Date(occStart.getTime() + 30 * 60 * 1000);
  return `${formatUtcCompact(occStart)}/${formatUtcCompact(end)}`;
}

function outlookCalendarUrl(ev: CalendarEvent, occStart: Date, occEnd: Date | null): string {
  // Outlook Live deeplink. Times go as ISO-8601 local strings.
  // Outlook Web parses them in the user's profile timezone.
  const params = new URLSearchParams();
  params.set('path', '/calendar/action/compose');
  params.set('rru', 'addevent');
  params.set('subject', ev.title);
  params.set('startdt', formatLocalIso(occStart, ev.allDay));
  const end = occEnd ?? new Date(occStart.getTime() + 30 * 60 * 1000);
  params.set('enddt', formatLocalIso(end, ev.allDay));
  if (ev.notes) params.set('body', ev.notes);
  if (ev.location) params.set('location', ev.location);
  if (ev.allDay) params.set('allday', 'true');
  return 'https://outlook.live.com/calendar/0/deeplink/compose?' + params.toString();
}

/** Build a tiny single-event RFC 5545 ICS body. The CN/role parameter
 *  flora of full iCalendar is overkill for "add this one to my
 *  calendar app" — we keep it minimal so any calendar client accepts
 *  it. The same shape feeds the .ics download button. */
function singleEventIcs(ev: CalendarEvent, occStart: Date, occEnd: Date | null): string {
  const lines: string[] = [];
  lines.push('BEGIN:VCALENDAR');
  lines.push('VERSION:2.0');
  lines.push('PRODID:-//Vance//Calendar//EN');
  lines.push('CALSCALE:GREGORIAN');
  lines.push('BEGIN:VEVENT');
  lines.push('UID:' + ev.id + '@vance');
  lines.push('DTSTAMP:' + formatUtcCompact(new Date()));
  if (ev.allDay) {
    lines.push('DTSTART;VALUE=DATE:' + formatYmd(occStart));
    const endExcl = occEnd ? addDays(occEnd, 1) : addDays(occStart, 1);
    lines.push('DTEND;VALUE=DATE:' + formatYmd(endExcl));
  } else {
    lines.push('DTSTART:' + formatUtcCompact(occStart));
    const end = occEnd ?? new Date(occStart.getTime() + 30 * 60 * 1000);
    lines.push('DTEND:' + formatUtcCompact(end));
  }
  lines.push('SUMMARY:' + escapeIcsText(ev.title));
  if (ev.location) lines.push('LOCATION:' + escapeIcsText(ev.location));
  if (ev.notes) lines.push('DESCRIPTION:' + escapeIcsText(ev.notes));
  lines.push('END:VEVENT');
  lines.push('END:VCALENDAR');
  return lines.join('\r\n') + '\r\n';
}

function icsDownloadHref(ics: string): string {
  return 'data:text/calendar;charset=utf-8,' + encodeURIComponent(ics);
}

function escapeIcsText(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/,/g, '\\,').replace(/;/g, '\\;');
}

function formatYmd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${y}${m}${dd}`;
}

function formatUtcCompact(d: Date): string {
  // YYYYMMDDTHHMMSSZ
  return d.toISOString().replace(/[-:.]/g, '').replace(/\d{3}Z$/, 'Z');
}

function formatLocalIso(d: Date, dateOnly: boolean): string {
  // 2026-06-12T09:00:00 — Outlook expects local-time ISO without zone.
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  if (dateOnly) return `${y}-${m}-${dd}`;
  const hh = String(d.getHours()).padStart(2, '0');
  const mi = String(d.getMinutes()).padStart(2, '0');
  return `${y}-${m}-${dd}T${hh}:${mi}:00`;
}

function externalLinksFor(row: OccurrenceRow): ExternalLink[] {
  const ics = singleEventIcs(row.event, row.start, row.end);
  return [
    { label: 'Google', href: googleCalendarUrl(row.event, row.start, row.end) },
    { label: 'Outlook', href: outlookCalendarUrl(row.event, row.start, row.end) },
    {
      label: 'Apple / .ics',
      href: icsDownloadHref(ics),
      download: (row.event.title || 'event').replace(/[^a-z0-9-_]+/gi, '-').toLowerCase() + '.ics',
    },
  ];
}

/** Which occurrence row currently has its add-to-calendar popup open.
 *  `null` = no popup. We key the popup by start-time so recurrences
 *  with multiple occurrences each get their own targeted button. */
const openLinksKey = ref<string | null>(null);

function linkKey(row: OccurrenceRow): string {
  return row.event.id + '@' + row.start.getTime();
}

function toggleLinks(row: OccurrenceRow): void {
  const k = linkKey(row);
  openLinksKey.value = openLinksKey.value === k ? null : k;
}

function closeLinks(): void {
  openLinksKey.value = null;
}

// Dismiss the popup when the user clicks anywhere outside it. The
// menu itself stops propagation, and item clicks call closeLinks
// explicitly.
function onDocumentClick(_event: MouseEvent): void {
  if (openLinksKey.value !== null) closeLinks();
}
onMounted(() => document.addEventListener('click', onDocumentClick));
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick));

/** Map a free-form color string (palette name or CSS color) to a CSS
 *  color value. Palette names are deliberately limited so calendars
 *  stay visually coherent. */
function colorFor(ev: CalendarEvent): string {
  const c = ev.color?.trim().toLowerCase();
  if (!c) return 'hsl(var(--p))';
  switch (c) {
    case 'blue':   return '#3b82f6';
    case 'green':  return '#10b981';
    case 'red':    return '#ef4444';
    case 'orange': return '#f59e0b';
    case 'yellow': return '#eab308';
    case 'purple': return '#a855f7';
    case 'pink':   return '#ec4899';
    case 'teal':   return '#14b8a6';
    case 'gray':
    case 'grey':   return '#6b7280';
    default:       return ev.color ?? 'hsl(var(--p))';
  }
}
</script>

<template>
  <div :class="['cal', `cal--${mode}`]">
    <!-- Toolbar: view switch + month nav (embedded/editor only). -->
    <div v-if="mode !== 'inline'" class="cal-toolbar">
      <div class="cal-views">
        <button
          type="button"
          :class="['cal-view-btn', { 'cal-view-btn--active': view === 'month' }]"
          @click="view = 'month'"
        >{{ t('documents.calendar.viewMonth') }}</button>
        <button
          type="button"
          :class="['cal-view-btn', { 'cal-view-btn--active': view === 'agenda' }]"
          @click="view = 'agenda'"
        >{{ t('documents.calendar.viewAgenda') }}</button>
      </div>
      <div v-if="view === 'month'" class="cal-nav">
        <button type="button" class="cal-nav-btn" @click="prevMonth">‹</button>
        <button type="button" class="cal-nav-btn cal-today-btn" @click="gotoToday">
          {{ t('documents.calendar.today') }}
        </button>
        <button type="button" class="cal-nav-btn" @click="nextMonth">›</button>
        <span class="cal-month-label">{{ monthHeaderLabel(monthAnchor) }}</span>
      </div>
    </div>

    <!-- Month grid -->
    <div v-if="view === 'month' && mode !== 'inline'" class="cal-month">
      <div class="cal-month-header">
        <div v-for="w in weekdayLabels()" :key="w" class="cal-weekday">{{ w }}</div>
      </div>
      <div class="cal-month-grid">
        <div
          v-for="(cell, idx) in monthGrid"
          :key="idx"
          :class="[
            'cal-month-cell',
            { 'cal-month-cell--out': !cell.inMonth, 'cal-month-cell--today': cell.isToday }
          ]"
        >
          <div class="cal-month-date">{{ cell.date.getDate() }}</div>
          <div class="cal-month-events">
            <div
              v-for="(occ, oi) in cell.occurrences.slice(0, 3)"
              :key="oi"
              class="cal-month-event"
              :style="{ borderLeftColor: colorFor(occ.event) }"
              :title="occ.event.title + ' — ' + rangeLabel(occ)"
            >
              <span v-if="!occ.event.allDay" class="cal-month-time">
                {{ timeLabel(occ.start, false) }}
              </span>
              <span class="cal-month-title">{{ occ.event.title }}</span>
            </div>
            <div v-if="cell.occurrences.length > 3" class="cal-month-more">
              +{{ cell.occurrences.length - 3 }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Agenda view -->
    <div v-else class="cal-agenda">
      <div v-if="agendaGrouped.length === 0" class="cal-empty">
        {{ t('documents.calendar.empty') }}
      </div>
      <div v-for="(g, gi) in agendaGrouped" :key="gi" class="cal-agenda-day">
        <div class="cal-agenda-day-header">{{ dayLabel(g.day) }}</div>
        <ul class="cal-agenda-items">
          <li
            v-for="(occ, oi) in g.items"
            :key="oi"
            class="cal-agenda-item"
            :style="{ borderLeftColor: colorFor(occ.event) }"
          >
            <div class="cal-agenda-time">{{ rangeLabel(occ) }}</div>
            <div class="cal-agenda-body">
              <div class="cal-agenda-title-row">
                <div class="cal-agenda-title">{{ occ.event.title }}</div>
                <div class="cal-add-wrap">
                  <button
                    type="button"
                    class="cal-add-btn"
                    :title="t('documents.calendar.addToCalendar')"
                    @click.stop="toggleLinks(occ)"
                  >📅</button>
                  <div
                    v-if="openLinksKey === linkKey(occ)"
                    class="cal-add-menu"
                    @click.stop
                  >
                    <a
                      v-for="(link, li) in externalLinksFor(occ)"
                      :key="li"
                      class="cal-add-menu-item"
                      :href="link.href"
                      :download="link.download"
                      :target="link.download ? undefined : '_blank'"
                      rel="noopener"
                      @click="closeLinks"
                    >{{ link.label }}</a>
                  </div>
                </div>
              </div>
              <div v-if="occ.event.location" class="cal-agenda-meta">
                📍 {{ occ.event.location }}
              </div>
              <div v-if="occ.event.attendees.length > 0" class="cal-agenda-meta">
                👥 {{ occ.event.attendees.join(', ') }}
              </div>
              <div v-if="occ.event.notes" class="cal-agenda-notes">{{ occ.event.notes }}</div>
              <div v-if="occ.event.tags.length > 0" class="cal-agenda-tags">
                <span v-for="t in occ.event.tags" :key="t" class="cal-tag">{{ t }}</span>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cal {
  font-size: 0.9rem;
}
.cal--inline { max-height: 28rem; overflow-y: auto; }

.cal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.3rem 0.4rem 0.6rem;
  flex-wrap: wrap;
}
.cal-views { display: flex; gap: 0.2rem; }
.cal-view-btn {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.25rem 0.6rem;
  font: inherit;
  font-size: 0.85rem;
  color: inherit;
  cursor: pointer;
}
.cal-view-btn:hover { background: hsl(var(--bc) / 0.06); }
.cal-view-btn--active {
  background: hsl(var(--p) / 0.15);
  border-color: hsl(var(--p) / 0.4);
}
.cal-nav { display: flex; align-items: center; gap: 0.3rem; }
.cal-nav-btn {
  background: transparent;
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.25rem;
  padding: 0.2rem 0.55rem;
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
  color: inherit;
}
.cal-nav-btn:hover { background: hsl(var(--bc) / 0.06); }
.cal-today-btn { font-size: 0.78rem; }
.cal-month-label {
  margin-left: 0.4rem;
  font-weight: 600;
  font-size: 0.95rem;
}

/* Month grid */
.cal-month-header,
.cal-month-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}
.cal-month-header { border-bottom: 1px solid hsl(var(--bc) / 0.15); }
.cal-weekday {
  padding: 0.4rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  opacity: 0.7;
  text-align: center;
}
.cal-month-cell {
  border-right: 1px solid hsl(var(--bc) / 0.08);
  border-bottom: 1px solid hsl(var(--bc) / 0.08);
  min-height: 5.6rem;
  padding: 0.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  background: transparent;
}
.cal-month-cell:nth-child(7n) { border-right: none; }
.cal-month-cell--out {
  opacity: 0.4;
  background: hsl(var(--bc) / 0.03);
}
.cal-month-cell--today {
  background: hsl(var(--p) / 0.07);
}
.cal-month-cell--today .cal-month-date {
  background: hsl(var(--p));
  color: hsl(var(--pc));
  border-radius: 50%;
  width: 1.6rem;
  height: 1.6rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.cal-month-date {
  font-size: 0.85rem;
  font-weight: 500;
  margin-bottom: 0.1rem;
}
.cal-month-events {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  min-height: 0;
  overflow: hidden;
}
.cal-month-event {
  display: flex;
  align-items: baseline;
  gap: 0.25rem;
  font-size: 0.72rem;
  padding: 0.05rem 0.3rem;
  border-left: 3px solid hsl(var(--p));
  background: hsl(var(--bc) / 0.05);
  border-radius: 0.15rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cal-month-time { opacity: 0.7; font-variant-numeric: tabular-nums; }
.cal-month-title { font-weight: 500; }
.cal-month-more {
  font-size: 0.7rem;
  opacity: 0.6;
  padding: 0 0.25rem;
}

/* Agenda */
.cal-agenda { padding-top: 0.25rem; }
.cal-empty {
  padding: 1.5rem 0.5rem;
  text-align: center;
  opacity: 0.55;
  font-style: italic;
}
.cal-agenda-day { margin-bottom: 1rem; }
.cal-agenda-day-header {
  position: sticky;
  top: 0;
  background: hsl(var(--b2));
  font-weight: 600;
  font-size: 0.85rem;
  padding: 0.35rem 0.5rem;
  border-bottom: 1px solid hsl(var(--bc) / 0.1);
  margin-bottom: 0.3rem;
}
.cal-agenda-items {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.cal-agenda-item {
  display: grid;
  grid-template-columns: 7.5rem 1fr;
  gap: 0.6rem;
  padding: 0.4rem 0.5rem;
  border-left: 3px solid hsl(var(--p));
  background: hsl(var(--bc) / 0.04);
  border-radius: 0.25rem;
}
.cal-agenda-time {
  font-size: 0.8rem;
  opacity: 0.75;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.cal-agenda-body { min-width: 0; }
.cal-agenda-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.5rem;
  margin-bottom: 0.15rem;
}
.cal-agenda-title {
  font-weight: 600;
  flex: 1 1 auto;
  min-width: 0;
}
.cal-add-wrap {
  position: relative;
  flex-shrink: 0;
}
.cal-add-btn {
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.25rem;
  cursor: pointer;
  padding: 0.05rem 0.35rem;
  font-size: 0.95rem;
  line-height: 1;
  opacity: 0.5;
}
.cal-add-btn:hover {
  opacity: 1;
  background: hsl(var(--bc) / 0.06);
  border-color: hsl(var(--bc) / 0.15);
}
.cal-add-menu {
  position: absolute;
  right: 0;
  top: calc(100% + 0.2rem);
  z-index: 4;
  background: hsl(var(--b1));
  border: 1px solid hsl(var(--bc) / 0.2);
  border-radius: 0.3rem;
  box-shadow: 0 4px 14px hsl(var(--bc) / 0.18);
  display: flex;
  flex-direction: column;
  min-width: 9rem;
  overflow: hidden;
}
.cal-add-menu-item {
  display: block;
  padding: 0.35rem 0.7rem;
  font-size: 0.82rem;
  text-decoration: none;
  color: inherit;
  white-space: nowrap;
}
.cal-add-menu-item:hover {
  background: hsl(var(--p) / 0.1);
}
.cal-agenda-meta {
  font-size: 0.78rem;
  opacity: 0.7;
  margin-top: 0.1rem;
}
.cal-agenda-notes {
  margin-top: 0.25rem;
  font-size: 0.82rem;
  white-space: pre-wrap;
  opacity: 0.85;
}
.cal-agenda-tags {
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
  margin-top: 0.25rem;
}
.cal-tag {
  font-size: 0.7rem;
  padding: 0.05rem 0.4rem;
  background: hsl(var(--bc) / 0.1);
  border-radius: 999px;
  opacity: 0.8;
}
</style>
