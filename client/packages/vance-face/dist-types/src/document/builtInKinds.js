/**
 * Bootstrap-time registration of host-built-in document Kinds.
 *
 * The runtime {@code @vance/kind-registry} is the single place
 * DocumentApp.vue looks up a Kind's view + codec for any
 * registry-driven branch. Built-ins land here; addons populate
 * the same registry from their {@code ./register} federation
 * expose. When a Kind moves from built-in to addon (Calendar →
 * vance-addon-brain-calendar), the call below moves verbatim into
 * the addon's register.ts and this file shrinks by one entry —
 * DocumentApp.vue stays unchanged.
 *
 * Only Kinds that DocumentApp.vue dispatches *via the registry*
 * land here. Most built-ins still use the static {@code if/else}
 * dispatch and don't need a registration — they'll migrate as
 * additional addons get carved out.
 */
import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';
import { parseCalendar, CalendarCodecError, } from './calendarCodec';
const CalendarView = defineAsyncComponent(() => import('./CalendarView.vue'));
function isCalendarMime(mime) {
    if (!mime)
        return false;
    return mime === 'application/json'
        || mime === 'application/yaml'
        || mime === 'application/x-yaml'
        || mime === 'text/yaml'
        || mime === 'text/x-yaml';
}
export function registerBuiltInKinds() {
    registerKind({
        id: 'calendar',
        matches: (kind, mime) => (kind ?? '').toLowerCase() === 'calendar' && isCalendarMime(mime),
        view: CalendarView,
        parse: (body, mime) => parseCalendar(body, mime),
        isParseError: (e) => e instanceof CalendarCodecError,
        tabLabelKey: 'documents.detail.tabCalendar',
        parseErrorKey: 'documents.detail.calendarParseError',
    });
}
//# sourceMappingURL=builtInKinds.js.map