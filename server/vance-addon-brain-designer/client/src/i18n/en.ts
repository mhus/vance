/**
 * English messages of the designer addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  designer: {
    loading: 'Loading designs…',
    designs: '{count} design(s)',
    files: '{count} file(s)',
    create: 'New design',
    createTitle: 'New design',
    nameLabel: 'Name',
    namePlaceholder: 'landing-page',
    nameHelp: 'Folder name — letters, digits, dashes. Becomes <name>/index.html.',
    titleLabel: 'Title',
    titlePlaceholder: 'Shown on the card',
    descriptionLabel: 'Description',
    editMeta: 'Edit title & description',
    editTitle: 'Edit design',
    save: 'Save',
    cancel: 'Cancel',
    deleteDesign: 'Delete',
    deleteTitle: 'Delete design',
    deleteConfirm: 'Delete the design "{name}" and all its files?',
    deleteTrashNote: 'The files move to the trash — recoverable from there.',
    dragHandle: 'Drag to reorder · click to preview',
    theme: {
      auto: 'Follow system (day/night)',
      light: 'Force day (light)',
      dark: 'Force night (dark)',
    },
    device: {
      desktop: 'Desktop width',
      tablet: 'Tablet width (820px)',
      phone: 'Phone width (390px)',
    },
    rotate: 'Rotate (portrait/landscape)',
    reload: 'Reload preview',
    refresh: 'Refresh catalogue',
    previewOf: 'Preview — {name}',
    emptyHeadline: 'No designs yet',
    emptyBody:
      'Click "New design" to create the first one — or ask the chat ' +
      '("create a design landing-page in this app").',
    skills: {
      button: 'Design skills',
      title: 'Design skills',
      refresh: 'Refresh skill list',
      activeBadge: 'active',
      chatHint: 'Activations live in the open chat — activate with /skill <name> in the composer.',
      noChatHint:
        'Open a chat to see which design skills are active there — without one, a skill has no activation state.',
      emptyHeadline: 'No design skills',
      emptyBody:
        'Skills tagged "design" appear here. With a style.css next to their SKILL.md they also show a live style preview.',
      previewOf: 'Style preview — {name}',
      noStyle: 'No style.css — no preview',
      play: 'Activate in the chat — puts "/skill <name>" into the composer',
      clear: 'Deactivate in the chat',
      recipeBound: 'Bound by the recipe — the chat config owns this activation',
      error: {
        load: 'Could not load design skills: {message}',
        clear: 'Could not deactivate the skill: {message}',
      },
    },
    error: {
      load: 'Could not load designs: {message}',
      mint: 'Could not open a preview session: {message}',
      mutate: 'The change failed: {message}',
    },
  },
};
