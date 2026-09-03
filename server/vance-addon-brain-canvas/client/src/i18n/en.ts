/**
 * English messages of the canvas addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace even for words like "Cancel". A remote ships and deploys on its own
 * schedule, so a bundle that depends on the host's key layout would break on a
 * rename it cannot see. The duplicate words cost bytes; the independence is the
 * point.
 */
export default {
  // Extends a host namespace: the `tabLabelKey` this addon declares in
  // `register.ts` is resolved by the host, so the key has to live where the
  // host looks for it. mergeLocaleMessage deep-copies, so the built-in
  // `documents.detail.*` keys stay untouched.
  documents: {
    detail: {
      tabCanvas: 'Canvas',
    },
  },
  canvas: {
    common: {
      cancel: 'Cancel',
      ok: 'OK',
      back: 'Back',
      loading: 'Loading…',
      searching: 'Searching…',
      title: 'Title',
    },
    board: {
      note: 'Note',
      doc: 'Doc',
      link: 'Link',
      group: 'Group',
      select: 'Select',
      selectHint: 'Select several by dragging a box (or hold Shift and drag)',
      newNote: 'New note',
      groupDialogTitle: 'Group',
      newGroupLabel: 'Group',
    },
    node: {
      noColour: 'No colour',
      textColour: 'Text colour',
      rename: 'Rename',
      changeTarget: 'Change target',
      copyLink: 'Copy link',
      copied: 'Copied!',
      bringToFront: 'Bring to front',
      sendToBack: 'Send to back',
      delete: 'Delete',
      openInCortex: 'Open in Cortex',
      titlePlaceholder: 'Title',
      emptyNote: '(empty note)',
      group: 'Group',
    },
    book: {
      noCanvases: 'No canvases yet',
      addCanvas: 'Canvas',
      rebuildIndex: 'Index',
      saving: 'Saving…',
      unsaved: 'Unsaved',
      saved: 'Saved',
      newCanvas: 'New canvas',
      empty: 'Empty canvasbook — create one with “+ Canvas”.',
      pick: 'Pick a canvas.',
    },
    picker: {
      title: 'Insert reference',
      tabDocument: 'Document',
      tabThisApp: 'This app',
      tabStarred: 'Starred',
      tabApps: 'Applications',
      searchPlaceholder: 'Search by path or title …',
      noHits: 'No matches.',
      filterPlaceholder: 'Filter canvases in this app …',
      appItself: 'The app itself, without a specific canvas',
      noCanvasMatch: 'No canvas matches the filter.',
      noStarredApps: 'No app in your favourites.',
      noProjectApps: 'No app in this project.',
      appItselfNoPlace: 'The app itself, without a specific place',
    },
    edge: {
      title: 'Edit edge',
      label: 'Label',
      colour: 'Colour',
      colours: {
        default: 'Default',
        red: 'Red',
        orange: 'Orange',
        yellow: 'Yellow',
        green: 'Green',
        blue: 'Blue',
        purple: 'Purple',
        grey: 'Grey',
      },
      arrowStart: 'Arrow at start',
      arrowEnd: 'Arrow at end',
      dashed: 'Dashed',
      thick: 'Thick',
    },
  },
};
