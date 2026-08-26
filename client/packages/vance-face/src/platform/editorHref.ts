/**
 * The one place that builds a URL to a surface of this web UI.
 *
 * <p>Before the workspace cluster there were ~120 hand-written `.html` string
 * literals spread through the tree, and that — not the routing itself — was
 * what made the move expensive. They would have grown back. Everything that
 * links to a surface goes through here now, so the next change to the URL
 * shape is one file.
 *
 * <p>The precedent is `vanceRef` in `@vance/components`, which is the single
 * place `vance:` URIs are built, for the same reason: two encoding traps that
 * bite silently. Here the trap is the split below.
 *
 * <h3>Two kinds of target, and the difference is load-bearing</h3>
 *
 * <ul>
 *   <li><b>Cluster surfaces</b> (`home`, `cortex`, `chat`, `inbox`,
 *       `documents`) live inside the shell. Linking to one with an `<a href>`
 *       works but throws the session away — the point of the cluster is that
 *       switching between these does <em>not</em> reload. Prefer
 *       {@code <RouterLink>}; use this function only where a real anchor is
 *       required (a new-tab target, an addon that has no router).</li>
 *   <li><b>Standalone surfaces</b> (`profile`, `scopes`, `users`, …) are their
 *       own HTML entries and a full page load is correct — for the seldom-used
 *       admin screens, the teardown on leaving is a feature.</li>
 * </ul>
 *
 * <p>`login` is deliberately in the second group even though it is the most
 * linked-to target of all: it must keep working when the shell bundle does
 * not.
 */

/** Surfaces that are routes inside the shell (index.html). */
export type ClusterSurface = 'home' | 'cortex' | 'chat' | 'inbox' | 'documents';

/** Surfaces that are their own HTML entry. */
export type StandaloneSurface =
  | 'login'
  | 'profile'
  | 'scopes'
  | 'tools'
  | 'tool-templates'
  | 'setting-forms'
  | 'insights'
  | 'runs'
  | 'users'
  | 'oauth-providers'
  | 'connected-accounts'
  | 'addon';

export type Surface = ClusterSurface | StandaloneSurface;

const CLUSTER_PATHS: Record<ClusterSurface, string> = {
  home: '/',
  cortex: '/cortex',
  chat: '/chat',
  inbox: '/inbox',
  documents: '/documents',
};

function isCluster(surface: Surface): surface is ClusterSurface {
  return surface in CLUSTER_PATHS;
}

/** Query values; `null`/`undefined` entries are omitted rather than sent empty. */
export type HrefParams = Record<string, string | number | boolean | null | undefined>;

function queryString(params?: HrefParams): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue;
    search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

/**
 * An absolute, same-origin URL for a surface.
 *
 * @example
 *   editorHref('chat', { sessionId })   // → /chat?sessionId=…
 *   editorHref('home')                  // → /
 *   editorHref('profile')               // → /profile.html
 */
export function editorHref(surface: Surface, params?: HrefParams): string {
  const base = isCluster(surface) ? CLUSTER_PATHS[surface] : `/${surface}.html`;
  return `${base}${queryString(params)}`;
}

/**
 * The login URL carrying where to come back to.
 *
 * <p>The `next` value is read back by the login screen, which accepts only
 * same-origin relative paths — see `login/LoginApp.vue`. Passing the current
 * location is the caller's job because only they know whether the hash matters.
 */
export function loginHref(next?: string): string {
  const target = next ?? window.location.pathname + window.location.search + window.location.hash;
  return `/login.html?next=${encodeURIComponent(target)}`;
}
