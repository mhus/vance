/**
 * English messages of the wiki addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see.
 *
 * <p>Sentences that wrap a literal in {@code <code>} are split into a
 * pre/post pair — one message with a placeholder would have to give up the
 * markup. The `post` half therefore carries its own leading punctuation.
 */
export default {
  wiki: {
    common: {
      saving: 'Saving…',
      saved: 'Saved',
      cancel: 'Cancel',
      create: 'Create',
      creating: 'Creating…',
      loading: 'Loading…',
    },
    app: {
      fallbackTitle: 'Wiki',
      indexTitle: 'Index',
      searchPlaceholder: 'Search pages…',
      noMatch: 'No matching page.',
      createNamed: 'Create page “{name}”',
      createNamedInSpace: 'Create page “{name}” in {space}',
      newPage: 'New page',
      newShort: 'New',
      home: 'Home — main page',
      indexTip: 'Index — generated page list',
      rebuilding: 'Rebuilding…',
      rebuild: 'Rebuild indexes + backlinks',
      newPagePlaceholder: 'New page title…',
      newPageInSpacePlaceholder: 'New page in {space}…',
      loadingWiki: 'Loading wiki…',
      loadingPage: 'Loading page…',
      missingTitle: 'The home page “main” doesn’t exist yet.',
      missingPre: 'A wiki opens on its',
      missingMid: 'page. Create it now — or open the',
      indexLink: 'index',
      createMain: '＋ Create “main” page',
      noPageSelected: 'No page selected. Create one with ＋ New.',
      generatedBadge: 'generated · read-only',
      deletePage: 'Delete page',
      status: {
        edited: 'Edited',
        saveFailed: 'Save failed',
      },
      confirmDelete: 'Delete page “{title}”?',
      confirmCreate: 'Page “{name}” does not exist. Create it?',
      confirmCreateInSpace: 'Page “{name}” does not exist. Create it in space “{space}”?',
      error: {
        scan: 'Could not scan wiki.',
        loadPage: 'Could not load page.',
        save: 'Save failed.',
        openLink: 'Could not open wiki link.',
        createMain: 'Could not create the main page.',
        rebuild: 'Rebuild failed.',
        titleRequired: 'Title required',
        createPage: 'Could not create page.',
        delete: 'Delete failed.',
        search: 'Search failed.',
      },
    },
    backlinks: {
      title: 'What links here',
      emptyPre: 'No pages link here yet. Link with',
      emptyPost: ', then rebuild.',
      error: 'Could not load backlinks.',
    },
    notes: {
      title: 'Notes',
      newNote: 'New note',
      empty: 'No notes yet. Click ＋ to add one.',
      done: 'Done',
      markDone: 'Mark done',
      delete: 'Delete',
      error: {
        load: 'Could not load notes.',
        add: 'Could not add note.',
        update: 'Could not update note.',
        delete: 'Could not delete note.',
      },
    },
    versions: {
      title: 'Versions',
      empty: 'No archived versions yet — they appear after the next edit.',
      restore: 'Restore',
      confirmRestore: 'Restore this version? The current content is archived first.',
      error: {
        load: 'Could not load versions.',
        restore: 'Restore failed.',
      },
    },
  },
};
