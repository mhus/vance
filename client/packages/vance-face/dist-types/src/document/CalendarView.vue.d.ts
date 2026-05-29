import { type CalendarDocument } from './calendarCodec';
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
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    doc?: CalendarDocument;
    content?: string;
    meta?: FenceMeta;
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {
    meta: FenceMeta;
    mode: "editor" | "inline" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CalendarView.vue.d.ts.map