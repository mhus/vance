/**
 * English messages of the kanban addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  kanban: {
    common: {
      cancel: 'Cancel',
      create: 'Create',
      delete: 'Delete',
      done: 'Done',
    },
    board: {
      cardCount: '{n} card | {n} cards',
      columnCount: '{n} column | {n} columns',
      reload: 'Reload',
      rebuild: 'Rebuild artefacts',
      loading: 'Loading board…',
      emptyHeadline: 'No columns yet',
      emptyBody: 'Add columns to _app.yaml to start using this board.',
      undeclared: 'undeclared',
      addCard: 'Add card',
      blocked: 'blocked',
      newCard: 'New card',
      cardTitlePlaceholder: 'Card title',
      assigneePlaceholder: 'Assignee (optional)',
      dueDatePlaceholder: 'Due date YYYY-MM-DD (optional)',
      error: {
        load: 'Could not load board: {message}',
        rebuild: 'Rebuild failed: {message}',
        move: 'Move failed: {message}',
        delete: 'Delete failed: {message}',
        create: 'Create failed: {message}',
      },
    },
    priority: {
      none: 'No priority',
      low: 'Low',
      med: 'Medium',
      high: 'High',
      critical: 'Critical',
    },
    detail: {
      title: 'Card detail',
      fieldTitle: 'Title',
      priority: 'Priority',
      assignee: 'Assignee',
      dueDate: 'Due date',
      dueDatePlaceholder: 'YYYY-MM-DD',
      estimate: 'Estimate',
      labels: 'Labels',
      blocked: 'Blocked',
      color: 'Color',
      editContent: 'Edit content…',
      contentTitle: 'Card content',
      confirmDelete: 'Delete card “{title}”?',
      status: {
        edited: 'Edited',
        saving: 'Saving…',
        saved: 'Saved',
      },
    },
  },
};
