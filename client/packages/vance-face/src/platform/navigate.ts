/**
 * Go to a surface — through the router when there is one, by reloading when
 * there is not.
 *
 * <p>The cluster editors reach each other constantly: Cortex opens a chat,
 * Documents opens a document in Cortex, the Inbox starts a draft. All of that
 * was `window.location.href = …`, which is right when every editor is its own
 * page and wrong the moment they share one — the path would be correct and the
 * page would still reload, giving up exactly what the cluster exists for. That
 * is not a theoretical worry: it is what the first browser run of the cluster
 * actually did.
 *
 * <p>A plain `useRouter()` at each call site would not do, because several of
 * these components are also mounted on the standalone entries (profile,
 * scopes, an addon area) where no router exists. So the router registers
 * itself here once at shell boot, and everything else asks this module. Same
 * rendezvous shape as `configureVanceWs` — a module-level binding that the
 * host fills in and the leaves consume.
 *
 * <p>Outside the shell the binding is absent and every call is a normal
 * navigation, which is the correct behaviour there: those pages *are* leaving
 * their bundle.
 */

import type { Router } from 'vue-router';

let router: Router | null = null;

/** Paths the shell serves as routes. Anything else is a real page load. */
const CLUSTER_PREFIXES = ['/cortex', '/chat', '/inbox', '/documents'];

/** Called once by the shell's boot. Never called by a standalone entry. */
export function bindRouter(r: Router): void {
  router = r;
}

function isClusterHref(href: string): boolean {
  if (href === '/') return true;
  if (!href.startsWith('/')) return false;
  // `/documents?x=1` and `/documents` both count; `/documents-export.html`
  // must not, hence the boundary check rather than a bare startsWith.
  return CLUSTER_PREFIXES.some((p) => {
    if (!href.startsWith(p)) return false;
    const rest = href.slice(p.length);
    return rest === '' || rest.startsWith('?') || rest.startsWith('#');
  });
}

/**
 * Navigate to {@code href}.
 *
 * @param options.replace replace the current history entry instead of pushing
 *   one — for a redirect the reader should not be able to go "back" into.
 */
export function navigateTo(href: string, options?: { replace?: boolean }): void {
  if (router && isClusterHref(href)) {
    void (options?.replace ? router.replace(href) : router.push(href));
    return;
  }
  if (options?.replace) window.location.replace(href);
  else window.location.href = href;
}

/** Whether a click on {@code href} would stay inside the shell. */
export function staysInShell(href: string): boolean {
  return router !== null && isClusterHref(href);
}
