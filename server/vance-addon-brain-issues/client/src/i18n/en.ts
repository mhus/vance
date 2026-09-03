/**
 * English messages of the issues addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  issues: {
    tabOpen: 'Open',
    tabClosed: 'Closed',
    tabArchived: 'Archived',
    searchPlaceholder: 'Search issues…',
    untitled: '(untitled)',
    noMatch: 'No matching issue.',
    rebuildTip: 'Rebuild index + stats',
    newIssuePlaceholder: '＋ New issue title…',
    allLabels: 'all',
    loading: 'Loading…',
    nothingHere: 'Nothing here.',
    archived: 'archived',
    // Server-side state value, rendered as a badge.
    state: {
      open: 'open',
      closed: 'closed',
    },
    close: 'Close',
    reopen: 'Reopen',
    archive: 'Archive',
    unarchive: 'Unarchive',
    labels: 'Labels',
    labelsPlaceholder: 'bug, auth',
    assignee: 'Assignee',
    priority: 'Priority',
    description: 'Description',
    discussion: 'Discussion ({count})',
    deleteComment: 'Delete',
    commentPlaceholder: 'Add a comment…',
    comment: 'Comment',
    confirmDelete: 'Delete this issue?',
    status: {
      saving: 'Saving…',
      saved: 'Saved',
      saveFailed: 'Save failed',
    },
    error: {
      scan: 'Could not scan issues.',
      loadIssue: 'Could not load issue.',
      save: 'Save failed.',
      archive: 'Archive failed.',
      delete: 'Delete failed.',
      comment: 'Comment failed.',
      deleteComment: 'Delete comment failed.',
      create: 'Create failed.',
      search: 'Search failed.',
      rebuild: 'Rebuild failed.',
    },
  },
};
