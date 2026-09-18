/**
 * English messages of the scribble addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see. The duplicate words cost bytes; the independence is
 * the point.
 */
export default {
  // Extends a host namespace: the `tabLabelKey` this addon declares in
  // `register.ts` is resolved by the host, so the key has to live where the
  // host looks for it. mergeLocaleMessage deep-copies, so the built-in
  // `documents.detail.*` keys stay untouched.
  documents: {
    detail: {
      tabScribble: 'Sheet',
    },
  },
  scribble: {
    common: {
      loading: 'Loading…',
      loadFailed: 'Could not load the sheet.',
      title: 'Title',
      cancel: 'Cancel',
      ok: 'OK',
    },
    state: {
      saved: 'Saved',
      saving: 'Saving…',
      unsaved: 'Unsaved changes',
    },
    editor: {
      emptyHint: 'Write or sketch with your pen — every stroke is saved automatically.',
      pen: 'Pen',
      sizeSmall: 'Thin',
      sizeMedium: 'Medium',
      sizeLarge: 'Bold',
      colorBlack: 'Black',
      colorRed: 'Red',
      colorBlue: 'Blue',
      colorGreen: 'Green',
      eraser: 'Eraser — tap or drag across a stroke to remove it',
      undo: 'Undo',
      redo: 'Redo',
      fit: 'Fit to page',
      fingerDraw: 'Draw with finger (for when the pen is out of battery)',
      zoom: 'Zoom',
    },
    book: {
      newSheet: 'New sheet',
      noSheets: 'No sheets yet',
      addSheet: 'New sheet',
      rebuildIndex: 'Rebuild index',
      empty: 'This notebook is empty — add the first sheet and start writing.',
      pick: 'Pick a sheet from the menu above.',
      toggleEnabled: 'Include this sheet in the book export',
      toggleDefault: 'Open this sheet first when the book opens',
    },
  },
};
