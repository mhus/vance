/**
 * English messages of the journal addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 *
 * <p>Month and weekday names are deliberately absent — they come from
 * {@code Intl.DateTimeFormat} with the UI locale, which is both correct for
 * every language and free of a translation table to maintain.
 */
export default {
  journal: {
    today: 'Today',
    searchPlaceholder: 'Search entries…',
    untitled: '(untitled)',
    noMatch: 'No matching entry.',
    rebuilding: 'Rebuilding…',
    rebuildTip: 'Rebuild index + stats',
    statEntries: 'entries',
    statDayStreak: 'day streak',
    statLongest: 'longest',
    loading: 'Loading journal…',
    loadingEntry: 'Loading entry…',
    badgeNew: 'new',
    moodPlaceholder: 'mood…',
    deleteEntry: 'Delete entry',
    tagsPlaceholder: 'tags, comma, separated',
    pickDay: 'Pick a day in the calendar.',
    onThisDay: 'On this day',
    nothingEarlier: 'Nothing from earlier years.',
    status: {
      edited: 'Edited',
      saving: 'Saving…',
      saved: 'Saved',
    },
    confirmDelete: 'Delete the entry for {date}?',
    error: {
      scan: 'Could not scan journal.',
      delete: 'Delete failed.',
      search: 'Search failed.',
      rebuild: 'Rebuild failed.',
    },
  },
};
