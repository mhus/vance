const __vite__mapDeps=(i,m=__vite__mapDeps,d=(m.f||(m.f=["assets/CalendarView-D9xoSkYX.js","assets/calendarCodec-DmOnIctH.js","assets/js-yaml-xdlU0wp7.js"])))=>i.map(i=>d[i]);
import { _ as __vitePreload } from './preload-helper-BelkbqnE.js';
import { a as __mf_92 } from './_virtual_mf___mfe_internal__vance_addon_calendar__loadShare__vue__loadShare__.mjs-DvmOVNPO.js';
import { p as parseCalendar } from './calendarCodec-DmOnIctH.js';
import './js-yaml-xdlU0wp7.js';

function store() {
  let s = globalThis.__VANCE_KIND_REGISTRY__;
  if (!s) {
    s = /* @__PURE__ */ new Map();
    globalThis.__VANCE_KIND_REGISTRY__ = s;
  }
  return s;
}
function registerKind(entry) {
  store().set(entry.id, entry);
}

const CalendarView = __mf_92(() => __vitePreload(() => import('./CalendarView-D9xoSkYX.js'),true?__vite__mapDeps([0,1,2]):void 0));
function isCalendarParseError(e) {
  return e instanceof Error && e.name === "CalendarCodecError";
}
function isCalendarMime(mime) {
  if (!mime) return false;
  return mime === "application/json" || mime === "application/yaml" || mime === "application/x-yaml" || mime === "text/yaml" || mime === "text/x-yaml";
}
function register() {
  console.log("[vance-addon/calendar] register() called");
  registerKind({
    id: "calendar",
    matches: (kind, mime) => (kind ?? "").toLowerCase() === "calendar" && isCalendarMime(mime),
    view: CalendarView,
    parse: (body, mime) => parseCalendar(body, mime),
    isParseError: isCalendarParseError,
    tabLabelKey: "documents.detail.tabCalendar",
    parseErrorKey: "documents.detail.calendarParseError"
  });
}

export { register };
