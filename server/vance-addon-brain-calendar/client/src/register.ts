/**
 * Federation expose `./register` — called by the vance-face host at
 * boot after fetching {@code /face/addons}.
 *
 * The Calendar addon contributes a single-document Kind: documents
 * with {@code kind: calendar} and YAML/JSON mime types render via
 * {@link CalendarView}. The host's DocumentApp looks the Kind up
 * via {@code resolveKind('calendar')} from {@code @vance/kind-registry}
 * — the lookup is the only coupling between host and addon.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
import {
  parseCalendar,
  type CalendarDocument,
} from './calendarCodec';
import {
  parseTimeline,
  type TimelineDocument,
} from './timelineCodec';

const CalendarView = defineAsyncComponent(() => import('./CalendarView.vue'));
const CalendarAppKind = defineAsyncComponent(() => import('./CalendarAppKind.vue'));
const TimelineView = defineAsyncComponent(() => import('./TimelineView.vue'));

/**
 * Type-guard the host's DocumentApp uses to surface a Calendar-specific
 * parse-error banner vs. a generic one. The parser only throws
 * {@code CalendarCodecError} from {@link parseCalendar}; everything
 * else bubbles up untouched.
 */
function isCalendarParseError(e: unknown): boolean {
  return e instanceof Error && e.name === 'CalendarCodecError';
}

function isCalendarMime(mime: string | null | undefined): boolean {
  if (!mime) return false;
  return mime === 'application/json'
    || mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml'
    || mime === 'text/x-yaml';
}

/** Same predicate as the calendar's — both kinds are YAML/JSON only. */
function isTimelineParseError(e: unknown): boolean {
  return e instanceof Error && e.name === 'TimelineCodecError';
}

export function register(): void {
  // eslint-disable-next-line no-console
  console.log('[vance-addon/calendar] register() called');
  registerKind<CalendarDocument>({
    id: 'calendar',
    matches: (kind, mime) =>
      (kind ?? '').toLowerCase() === 'calendar' && isCalendarMime(mime),
    view: CalendarView,
    parse: (body, mime) => parseCalendar(body, mime),
    isParseError: isCalendarParseError,
    tabLabelKey: 'documents.detail.tabCalendar',
    parseErrorKey: 'documents.detail.calendarParseError',
  });
  // Timeline shares the addon and the mime set, not the data model: a
  // declared axis with lanes and nested periods, rather than
  // appointments on the Gregorian calendar.
  registerKind<TimelineDocument>({
    id: 'timeline',
    matches: (kind, mime) =>
      (kind ?? '').toLowerCase() === 'timeline' && isCalendarMime(mime),
    view: TimelineView,
    parse: (body, mime) => parseTimeline(body, mime),
    isParseError: isTimelineParseError,
    // No tabLabelKey / parseErrorKey: a federated remote does not share
    // the host's i18n instance, so a key here would render as its own
    // path. The host falls back to the kind id, which reads correctly.
  });
  // Application-kind entry: kind: application + app: calendar inside
  // an _app.yaml manifest dispatches to the folder-level Planner. The
  // matcher returns false on purpose — the host's docTypeRegistry
  // resolves this entry by explicit id lookup (resolveKind), not via
  // the generic kind+mime scan, so multiple application:<type> entries
  // don't collide on shared kind/mime metadata.
  registerKind({
    id: 'application:calendar',
    matches: () => false,
    view: CalendarAppKind,
  });
}
