/**
 * English messages of the workbook addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see.
 *
 * <p>Where a sentence wraps a path in {@code <code>}, it is split into a
 * prefix/middle pair instead of using one placeholder — a single message would
 * have to give up the markup.
 */
export default {
  documents: {
    detail: {
      tabWorkPage: 'Page',
    },
  },
  workbook: {
    common: {
      cancel: 'Cancel',
      create: 'Create',
      creating: 'Creating…',
      save: 'Save',
      saving: 'Saving…',
      saved: 'Saved',
      searching: 'Searching…',
    },
    app: {
      fallbackTitle: 'Workbook',
      designMode: 'Design mode — editing form fields. Click for Work mode.',
      workMode: 'Work mode — entering data. Click for Design mode.',
      newPage: 'New page',
      rebuilding: 'Rebuilding…',
      rebuildIndex: 'Rebuild _index.md',
      filterPlaceholder: 'Filter pages…',
      pageTitlePlaceholder: 'Page title',
      newSectionPlaceholder: 'Section (optional)',
      sectionWorkbook: 'Workbook',
      index: 'Index',
      sectionDefault: 'Pages',
      moreActions: 'More actions',
      showPages: 'Show pages',
      hidePages: 'Hide pages',
      landingPage: 'Landing page',
      emptyPages: 'No pages yet.',
      loadingWorkbook: 'Loading workbook…',
      loadingPage: 'Loading page…',
      pickPage: 'Pick a page from the sidebar.',
      changeCover: 'Change cover',
      removeCover: 'Remove',
      addIcon: 'Add icon',
      addCover: 'Add cover',
      changeIcon: 'Change icon',
      status: {
        edited: 'Edited',
        saveFailed: 'Save failed',
      },
      ctx: {
        renameMove: 'Rename / Move…',
        duplicate: 'Duplicate',
        pinLanding: 'Pin as landing page',
        unpinLanding: 'Unpin landing page',
        delete: 'Delete',
        renameSection: 'Rename section…',
      },
      rename: {
        header: 'Rename / Move',
        title: 'Title',
        section: 'Section',
        sectionPlaceholder: '(top-level)',
      },
      confirmDelete: 'Delete page “{title}”?',
      error: {
        titleRequired: 'Title required',
        createPage: 'Could not create page.',
        scan: 'Could not scan workbook.',
        loadPage: 'Could not load page.',
        save: 'Save failed.',
        rebuild: 'Rebuild failed.',
        rename: 'Rename failed.',
        duplicate: 'Duplicate failed.',
        landing: 'Could not update landing page.',
        delete: 'Delete failed.',
        sectionRename: 'Section rename failed.',
        move: 'Move failed.',
        reorder: 'Reorder failed.',
      },
    },
    assets: {
      title: 'Insert image',
      tabApp: 'App',
      tabProject: 'Project',
      tabShared: 'Shared',
      upload: 'Upload new',
      uploading: 'Uploading…',
      loadingApp: 'Loading assets…',
      emptyAppPre: 'No images yet under',
      searchPlaceholder: 'Search images by name or path…',
      emptyProject: 'No images found in the project.',
      loadingShared: 'Loading shared images…',
      emptySharedPre: 'No shared images yet under',
      emptySharedMid: 'in project',
      truncated: 'Showing {shown} of {total} — refine the search to narrow.',
      error: {
        loadApp: 'Could not load assets.',
        search: 'Search failed.',
        loadShared: 'Could not load shared images.',
      },
    },
    embed: {
      title: 'Embed document',
      tabApp: 'App',
      tabProject: 'Project',
      searchPlaceholder: 'Search documents to embed…',
      emptyApp: 'No embeddable documents found in this app.',
      emptyProject: 'No embeddable documents found in the project.',
      truncated: 'Showing {shown} of {total} — refine the search.',
      searchFailed: 'Search failed',
    },
    form: {
      title: 'Insert form',
      searchPlaceholder: 'Search data documents in this app…',
      empty: 'No data documents found in this app.',
      hintPre: 'Create one below — that yields a',
      hintMid: 'document with its schema under',
      newNamePlaceholder: 'New form name…',
      searchFailed: 'Search failed',
      createFailed: 'Create failed',
    },
    input: {
      title: 'Insert text input',
      searchPlaceholder: 'Search text documents in this app…',
      empty: 'No text documents found in this app — create one below.',
      newNamePlaceholder: 'New text name (optional)…',
      searchFailed: 'Search failed',
      createFailed: 'Create failed',
    },
    indexBlock: {
      slashTitle: 'Workbook Index',
      slashHint: 'Link block that jumps to the workbook index',
      goto: 'To the workbook index',
      label: 'Workbook index',
    },
    emoji: {
      title: 'Pick an icon',
      remove: 'Remove icon',
    },
  },
};
