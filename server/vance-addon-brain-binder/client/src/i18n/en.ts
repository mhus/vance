/**
 * English messages of the binder addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see.
 */
export default {
  binder: {
    common: {
      cancel: 'Cancel',
      searching: 'Searching…',
      noHits: 'No matches.',
    },
    app: {
      fallbackTitle: 'Binder',
      rebuildIndex: 'Rebuild index',
      pin: 'Pin',
      filterPlaceholder: 'Filter …',
      empty: 'No documents yet. Use “+ Pin” to anchor one.',
      landing: 'Landing',
      actions: 'Actions',
      changeSection: 'Change section…',
      rename: 'Rename…',
      setLanding: 'Set as landing',
      removeLanding: 'Remove landing',
      remove: 'Remove',
      reload: 'Reload',
      editInCortex: 'Edit in Cortex',
      // Two keys, because the path between them is rendered as <code> — a
      // single message with a {path} placeholder would drop the markup.
      goneTitle: 'This document no longer exists',
      goneHint: 'Remove the entry from the ⋯ menu.',
      noRenderer: 'No embed renderer available.',
      pickOne: 'Pick a document on the left, or pin one with “+ Pin”.',
      confirmRemove: 'Remove “{title}” from the binder?',
      promptTitle: 'Display title (empty = document title):',
      promptSection: 'Section (empty = no section):',
    },
    picker: {
      title: 'Pin document',
      searchPlaceholder: 'Search by path or title …',
    },
  },
};
