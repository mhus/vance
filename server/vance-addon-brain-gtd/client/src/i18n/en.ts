/**
 * English messages of the GTD addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see.
 */
export default {
  gtd: {
    common: {
      loading: 'Loading…',
      saving: 'Saving…',
      saved: 'Saved',
      saveFailed: 'Save failed',
    },
    // Keyed by bucket id so the template can resolve `gtd.bucket.<id>` —
    // a module-level label map could not follow a language switch.
    bucket: {
      inbox: 'Inbox',
      today: 'Today',
      upcoming: 'Upcoming',
      anytime: 'Anytime',
      someday: 'Someday',
      trash: 'Trash',
    },
    app: {
      searchPlaceholder: 'Search actions…',
      untitled: '(untitled)',
      noMatch: 'No matching action.',
      rebuildTip: 'Rebuild views — moves completed actions to Trash',
      projects: 'Projects',
      contexts: 'Contexts',
      capturePlaceholder: '＋ Capture to Inbox…',
      nothingHere: 'Nothing here.',
      bucketLabel: 'Bucket',
      whenHint: 'when:',
      anytimeValue: '(anytime)',
      project: 'Project',
      noProject: '(no project)',
      newProject: '＋ New project…',
      deadline: 'Deadline',
      contextsPlaceholder: '@calls, @home',
      done: 'Done',
      deleteForGood: 'Delete for good',
      moveToTrash: 'Move to Trash',
      note: 'Note',
      promptUpcoming: 'Upcoming date (yyyy-MM-dd):',
      promptNewProject: 'New project name:',
      confirmDelete: 'Delete this action for good? This cannot be undone here.',
      error: {
        scan: 'Could not scan GTD folder.',
        loadAction: 'Could not load action.',
        save: 'Save failed.',
        update: 'Update failed.',
        move: 'Move failed.',
        refile: 'Re-file failed.',
        delete: 'Delete failed.',
        reorder: 'Reorder failed.',
        capture: 'Capture failed.',
        search: 'Search failed.',
        rebuild: 'Rebuild failed.',
      },
    },
  },
};
