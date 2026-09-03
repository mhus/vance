/**
 * English messages of the links addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  links: {
    common: {
      cancel: 'Cancel',
      save: 'Save',
      create: 'Create',
      close: 'Close',
    },
    app: {
      pastePlaceholder: 'Paste a link — or several, one per line',
      groupPlaceholder: 'Group (optional)',
      add: 'Add',
      newGroup: 'New group',
      newGroupButton: '+ Group',
      rebuildTip: 'Regenerate the _index.md link list',
      captureTip: 'Capture access — tokens for browser extensions and scripts',
      backToList: 'Back to the curated list',
      readPile: 'Read through what is left, by date',
      list: '☰ List',
      stack: '▤ Stack {count}',
      newestFirst: 'Newest first',
      oldestFirst: 'Oldest first',
      newest: '↓ Newest',
      oldest: '↑ Oldest',
      showSeen: 'Show seen',
      filterPlaceholder: 'Filter…',
      all: 'All {count}',
      ungrouped: 'Ungrouped {count}',
      noGroup: 'no group',
      renameGroupTip: 'Rename or dissolve this group',
      emptyHeadline: 'No links yet',
      emptyBody:
        'Paste a URL above. The title comes from the page; the teaser and the picture '
        + 'are read from it live, so a link is complete the moment you add it.',
      nothingMatchesHeadline: 'Nothing matches',
      nothingMatchesBody: 'No link in this list matches the filter.',
      pileDoneHeadline: 'Nothing left to read',
      pileDoneBody:
        'Every link here is marked seen. Turn on “Show seen” to look back through them, '
        + 'or switch to the list to curate.',
      clearFilter: 'Clear the filter',
      filterByTag: 'Filter by {tag}',
      putBack: 'Put back on the pile',
      markSeen: 'Mark as seen',
      copied: 'Copied',
      copyLink: 'Copy the link',
      actions: 'Actions',
      share: 'Share…',
      edit: 'Edit…',
      refreshPreview: 'Refresh preview',
      remove: 'Remove',
      seenOn: 'seen {date}',
      confirmRemove: 'Remove “{label}” from this list?',
      promptNewGroup: 'New group:',
      promptRenameGroup: 'Rename “{group}” (empty dissolves the group):',
      clipboardUnavailable: 'The clipboard is not available in this browser context.',
    },
    edit: {
      title: 'Edit link',
      fieldTitle: 'Title',
      titlePlaceholder: 'the page’s own title',
      titleHelp: 'Empty re-fetches the title from the page.',
      teaser: 'Teaser',
      teaserPlaceholder: 'the page has no description',
      teaserHelpLive: 'Empty shows the page’s own description (the grey text above) and keeps it current.',
      teaserHelpNone: 'The page offers no description — write one to give the card a subtitle.',
      group: 'Group',
      groupPlaceholder: '(no group)',
      groupHelp: 'A name that does not exist yet becomes a new group.',
      tags: 'Tags',
      tagsPlaceholder: 'add a tag…',
      note: 'Note',
      notePlaceholder: 'why this list has the link',
      noteHelp: 'Your remark. The teaser describes the page; this describes why you kept it.',
      image: 'Picture URL',
      imagePlaceholder: 'the page offers no picture',
      imageHelp: 'Empty uses the page’s own preview picture.',
    },
    capture: {
      title: 'Capture access',
      intro:
        'A capture token lets an outside tool — a browser extension, a shell alias — '
        + 'work without a login. Pick what it may do; each capability opens exactly the '
        + 'routes shown beside it and nothing else. Link capture, for instance, can look '
        + 'up one URL, read the group names and save — it cannot read this list, change '
        + 'an entry, or delete one.',
      scopePre: 'The token is pinned to project',
      scopePost:
        'travels with it as the destination, not as a limit — a tool that changes it '
        + 'reaches another link list in the same project.',
      scopeFolder: 'The folder',
      existing: 'Tokens for this project',
      none: 'None yet.',
      noCapability: 'no capability',
      created: 'created {date}',
      expires: 'expires {date}',
      lastUsed: 'last used {date}',
      never: 'never',
      revoked: 'revoked {date}',
      revoke: 'Revoke',
      newToken: 'New token',
      label: 'Label',
      labelPlaceholder: 'Browser extension',
      defaultLabel: 'Browser extension',
      days: 'Days',
      liveTokens: '{n} live token already — a new one does not replace them.'
        + ' | {n} live tokens already — a new one does not replace them.',
      connectionString: 'Connection string',
      copiedButton: '✓ Copied',
      copyButton: '⧉ Copy',
      copiedHint:
        'Copied to your clipboard. It is shown once and not stored — paste it before '
        + 'you copy anything else.',
      onceHint:
        'Shown once. The server keeps no copy — if you lose it, revoke this token and '
        + 'create another.',
      blobHint:
        'Holds the brain URL, tenant, project, folder, the token and a checksum. '
        + 'Paste it into the extension as one value.',
      error: {
        days: 'Lifetime must be a number of days.',
        capability: 'Pick at least one capability.',
        clipboard: 'Could not reach the clipboard — select the text and copy it manually.',
      },
      confirmRevoke: 'Revoke “{label}”? Anything still using it stops working within seconds.',
    },
  },
};
