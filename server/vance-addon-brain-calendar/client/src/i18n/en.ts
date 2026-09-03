/**
 * English messages of the calendar addon surface (calendar kind, planner app,
 * timeline kind).
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see.
 *
 * <p>Counted sentences are single `one | many` messages rather than assembled
 * fragments — German inflects where English does not, and a sentence glued from
 * pieces cannot be reordered by a translator.
 */
export default {
  documents: {
    detail: {
      tabCalendar: 'Calendar',
      tabTimeline: 'Timeline',
      timelineParseError: 'This document is not valid timeline YAML.',
      calendarParseError: 'This document is not valid calendar YAML.',
    },
  },
  calendar: {
    common: {
      cancel: 'Cancel',
      create: 'Create',
      save: 'Save',
      delete: 'Delete',
    },
    view: {
      viewMonth: 'Month',
      viewAgenda: 'Agenda',
      today: 'Today',
      empty: 'No upcoming events.',
      addToCalendar: 'Add to calendar',
      allDay: 'All day',
    },
    planner: {
      reload: 'Reload',
      rebuild: 'Rebuild artefacts',
      loading: 'Loading planner…',
      eventCount: '{n} event | {n} events',
      laneCount: '{n} lane | {n} lanes',
      conflictCount: '{n} conflict | {n} conflicts',
      overview: 'Overview',
      overviewHint: 'Gantt + conflicts',
      undeclared: 'undeclared',
      gantt: 'Gantt',
      ganttRenderError: 'Could not render Gantt: {message}',
      noGanttHeadline: 'No Gantt yet',
      noGanttBody: 'Click “Rebuild artefacts” to generate the Mermaid Gantt diagram.',
      conflicts: 'Conflicts',
      noConflictsHeadline: 'No conflicts',
      noConflictsBody: 'No two events overlap in the current window.',
      colEventA: 'Event A',
      colEventB: 'Event B',
      colOverlap: 'Overlap',
      addEvent: 'Add event',
      noEventsHeadline: 'No events in this lane',
      noEventsBody: 'Add the first event to get started.',
      allDayTag: 'all-day',
      recurringTag: 'recurring',
      addToGoogleCalendar: 'Add to Google Calendar',
      addToOutlook: 'Add to Outlook',
      newEvent: 'New event',
      eventTitlePlaceholder: 'Event title',
      startPlaceholder: 'Start (YYYY-MM-DD or YYYY-MM-DDTHH:mm)',
      endPlaceholder: 'End (optional)',
      error: {
        load: 'Could not load planner: {message}',
        rebuild: 'Rebuild failed: {message}',
        create: 'Create failed: {message}',
        update: 'Update failed: {message}',
        delete: 'Delete failed: {message}',
        mermaid: 'Could not extract Mermaid source from _gantt.md.',
      },
    },
    detail: {
      title: 'Event detail',
      fieldTitle: 'Title',
      start: 'Start',
      startPlaceholder: 'YYYY-MM-DD[THH:mm]',
      end: 'End',
      endPlaceholder: '(optional)',
      allDay: 'All-day event',
      lane: 'Lane',
      location: 'Location',
      attendees: 'Attendees',
      recurrence: 'Recurrence (RRULE)',
      recurrencePlaceholder: 'FREQ=WEEKLY;BYDAY=MO,…',
      tags: 'Tags',
      notes: 'Notes',
      addToGoogle: 'Add to Google',
      addToOutlook: 'Add to Outlook',
      source: 'Source: {path}',
      discard: 'Discard',
      confirmDelete: 'Delete event “{title}”?',
    },
    timeline: {
      entryCount: '{n} entry | {n} entries',
      zoomOut: 'Zoom out',
      zoomIn: 'Zoom in',
      fitTip: 'Fit all entries',
      fit: 'Fit',
      unreadable:
        '{n} entry has a position this {axis} axis cannot read and is not drawn: {list}'
        + ' | {n} entries have a position this {axis} axis cannot read and are not drawn: {list}',
      empty: 'This timeline has no entries that can be placed on its axis.',
      now: 'now',
      lane: 'lane: {name}',
      inside: 'inside: {name}',
      bound: {
        start: 'start',
        end: 'end',
        between: '{what} between {earliest} and {latest}',
        noEarlier: '{what} no earlier than {earliest}',
        noLater: '{what} no later than {latest}',
      },
    },
  },
};
