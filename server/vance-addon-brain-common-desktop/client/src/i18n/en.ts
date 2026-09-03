/**
 * English messages of the desktop addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  desktop: {
    refresh: 'Refresh',
    emptyHeadline: 'No apps here',
    emptyBody: 'Add an app under this folder, then refresh.',
    open: 'Open',
    openInNewWindow: 'Open in a new window',
  },
};
