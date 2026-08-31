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

/**
 * Rewrite the current URL from inside an editor — same route, new query.
 *
 * <p>Cortex writes its open tabs here, Chat its session, Inbox its selected
 * thread. All three used raw {@code history.pushState(null, …)}, and that
 * turned out to be the one thing a router cannot survive: the state object it
 * keeps on every entry ({@code back}/{@code current}/{@code position}/…) was
 * being overwritten with `null`. The router then still believed the route was
 * `/chat` — the address it last set itself — so pressing Back after stepping
 * into a document restored `/chat` without the session, and the chat came up
 * empty. Measured: `history.state === null` right after the chat wrote its
 * URL, `currentRoute.fullPath === '/chat'` while the address bar read
 * `/chat?project=…&sessionId=…`.
 *
 * <p>So the editor still decides *what* the query says — that part of the
 * contract stands — but the writing goes through the router, which keeps the
 * address and the router's own bookkeeping in step. Same path means the route
 * component is not remounted, so this is as cheap as the raw call was.
 *
 * <p>Outside the shell it degrades to a raw write that *preserves*
 * `history.state` instead of nulling it.
 *
 * <p><b>Returns a promise, and that matters.</b> `history.replaceState` updated
 * the address bar synchronously; a router navigation does not — it runs its
 * guard queue and only then writes the history entry, several microtasks
 * later. Every editor here reads its state back out of
 * `window.location.search` (the router's own `route.query` goes stale, see
 * router.ts), so a caller that writes a URL and then reads one in the same
 * turn read the *previous* address and acted on it. Awaiting is what closes
 * that window. Callers who only write and never read back can keep ignoring
 * the result.
 */
export function replaceUrl(url: string): Promise<void> {
  if (router) return router.replace(toRouterTarget(url)).then(() => undefined);
  window.history.replaceState(window.history.state, '', url);
  return Promise.resolve();
}

/** {@link replaceUrl}, but leaving a Back step behind. */
export function pushUrl(url: string): Promise<void> {
  if (router) return router.push(toRouterTarget(url)).then(() => undefined);
  window.history.pushState(window.history.state, '', url);
  return Promise.resolve();
}

/**
 * Reduce whatever the caller passed to what the router understands.
 *
 * <p>The editors build their URLs with `new URL(window.location.href)` and
 * hand over `url.toString()` — an absolute, same-origin address. That was
 * fine for `history.pushState`, which takes any same-origin URL, and is not
 * fine for the router, which expects a path: it read
 * `http://localhost:9900/chat?…` as a relative location and resolved it to
 * `/` with the query attached. The chat's own address turned into the
 * launcher's.
 *
 * <p>A cross-origin URL is returned untouched; the router will refuse it and
 * that refusal is more honest than silently rewriting someone's target.
 */
function toRouterTarget(url: string): string {
  if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) return url;
  try {
    const parsed = new URL(url, window.location.origin);
    if (parsed.origin !== window.location.origin) return url;
    return parsed.pathname + parsed.search + parsed.hash;
  } catch {
    return url;
  }
}

/** Whether a click on {@code href} would stay inside the shell. */
export function staysInShell(href: string): boolean {
  return router !== null && isClusterHref(href);
}

/**
 * Turn a click on a real `<a href>` into a route change when the target is a
 * shell route, and leave it alone otherwise.
 *
 * <p>The links stay anchors on purpose — middle-click, cmd-click, "copy link
 * address" and the status-bar preview all keep working, and on the standalone
 * entries there is no router to route with. This only takes over the plain
 * left click, and only inside the shell.
 *
 * <p>Modified clicks are never touched: cmd-click means "new tab", and
 * hijacking that is the kind of thing that makes a web app feel like it is
 * fighting the browser.
 *
 * @example
 *   <a href="/inbox" @click="(e) => handleShellLinkClick(e, '/inbox')">
 */
export function handleShellLinkClick(event: MouseEvent, href: string): void {
  if (event.defaultPrevented) return;
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return;
  }
  if (!staysInShell(href)) return;
  event.preventDefault();
  navigateTo(href);
}
