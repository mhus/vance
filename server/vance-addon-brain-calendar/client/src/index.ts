// Barrel for the calendar addon's client surface.

export { default as CalendarPlanner } from './CalendarPlanner.vue';
export { default as CalendarView } from './CalendarView.vue';
export {
  parseCalendar,
  serializeCalendar,
  isCalendarMime,
  emptyCalendar,
  CalendarCodecError,
} from './calendarCodec';
export type { CalendarDocument, CalendarEvent } from './calendarCodec';
export {
  createCalendarEvent,
  deleteCalendarEvent,
  getCalendarPlanner,
  rebuildCalendarPlanner,
  updateCalendarEvent,
} from './api';
export type { CalendarArtefactSummary } from './generated/calendar/CalendarArtefactSummary';
export type { CalendarConflictView } from './generated/calendar/CalendarConflictView';
export type { CalendarEventCreateRequest } from './generated/calendar/CalendarEventCreateRequest';
export type { CalendarEventUpdateRequest } from './generated/calendar/CalendarEventUpdateRequest';
export type { CalendarEventView } from './generated/calendar/CalendarEventView';
export type { CalendarLaneView } from './generated/calendar/CalendarLaneView';
export type { CalendarPlannerView } from './generated/calendar/CalendarPlannerView';
export type { CalendarRebuildResponse } from './generated/calendar/CalendarRebuildResponse';
