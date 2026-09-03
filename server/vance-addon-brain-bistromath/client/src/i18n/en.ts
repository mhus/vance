/**
 * English messages of the bistromath (applications) addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  bistromath: {
    app: {
      fallbackTitle: 'App',
      loads: 'Loads ({count})',
      loadsTip: '{count} document(s) load for this app',
      rebuild: 'Rebuild',
      rebuildTip: 'Re-read the views, restart the program',
      releaseDefault: 'A tenant admin decides which applications may run here.',
      sending: 'Sending…',
      requestRelease: 'Request release',
      loadOrder: 'Loads, in this order',
      noProgram: 'Nothing — this app has no program.',
      surfaceWithheldPre: 'This view asks for a drawing surface (',
      surfaceWithheldMid: '), which this tenant does not permit for this app. A tenant admin decides that in',
      noViewHeadline: 'No view yet',
      noViewBody:
        'This app is defined by its documents. A view is a document with '
        + '`$meta.kind: app-view` — ask the chat beside this panel for one, or add it under {folder}.',
    },
    view: {
      fallbackTitle: 'View',
      recheck: 'Re-check',
      openApp: 'Open the app ↗',
      openAppTip: 'Open the app this view belongs to',
      previewPre: 'Preview — no program is running, so anything bound to',
      previewMid: 'is empty and',
      previewPost: 'hides what it gates. Open the app to see it with data.',
    },
    widget: {
      selectPlaceholder: '—',
      filterPlaceholder: 'Filter…',
      tableMissingHeadline: 'Nothing to show',
      tableMissingBody:
        'Nothing has written `{key}` yet — the program fills it with '
        + "vance.state.set('{key}', rows). Check the key if this is unexpected.",
      tableEmptyHeadline: 'No entries',
      tableEmptyBody: '`{key}` is empty.',
      filterEmptyHeadline: 'Nothing matches the filter',
      filterEmptyBody: '{count} row(s) are hidden by »{filter}«.',
      sortBy: 'Sort by {column}',
      formEmptyHeadline: 'Nothing selected',
      formEmptyBody: 'Click a row in the table to edit it here.',
      tab: 'Tab {number}',
      repeatEmptyHeadline: 'Nothing here yet',
      repeatEmptyBody: 'The program has not put a list into `{key}`.',
      embedMissingPre: 'Nothing to embed —',
      embedMissingPost: 'holds no document path.',
      embedNoPath: '(no path)',
      embedNoRendererPre: 'This surface cannot render embedded documents. Meant:',
      unknownPre: 'This build cannot render a',
      unknownPost: 'widget.',
    },
    details: {
      emptyHeadline: 'Nothing selected',
      emptyBody: 'Click a row in the table to see it here.',
      empty: '—',
      yes: 'yes',
      no: 'no',
    },
  },
};
