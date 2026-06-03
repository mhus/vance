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

const CalendarView = defineAsyncComponent(() => import('./CalendarView.vue'));

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
}
