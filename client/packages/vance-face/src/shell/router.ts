/**
 * The workspace router.
 *
 * <p>Four editors plus the landing used to be five HTML entries, and switching
 * between them meant a full page load: ~1.5 MB of shared JavaScript re-parsed,
 * Vue and Pinia rebuilt from nothing, and the WebSocket torn down and
 * re-established — which made this user's presence badge flicker for
 * *everybody else* on every click. They are routes now.
 *
 * <h3>The contract: the router owns the path, the editor owns its query</h3>
 *
 * Every one of these editors already manages its own URL state, and does it
 * with raw {@code history.pushState} / {@code replaceState} — Cortex writes its
 * open tabs, Chat its session, Inbox its selected thread. That is left exactly
 * as it is, and the router is deliberately not told about it: it decides
 * <em>which component</em> a path mounts, nothing more.
 *
 * <p>The split works because a raw {@code replaceState} never changes the path,
 * only the query — so {@code currentRoute.path} stays true even though
 * {@code currentRoute.query} goes stale the moment an editor rewrites its URL.
 * <b>Do not read {@code route.query} in an editor.</b> Read
 * {@code window.location.search}, which is what all four already do and is the
 * only source that cannot be stale.
 *
 * <p>Landing is `/` rather than a named path because the rule for this
 * deployment is that whatever answers `/` is `index.html` — see
 * planning/web-ui-reorg.md §4.2.
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';

/**
 * <h3>No query filtering, and that is a correction</h3>
 *
 * An earlier version of this router dropped "editor-specific" query keys on a
 * hop, keeping only `project`/`projectId`/`path`. The idea was that a session
 * id must not ride along from Chat into Cortex.
 *
 * <p>It was a rule invented for a mechanism that does not exist. Nothing
 * carries a query across a route change by itself: `push('/cortex?project=p')`
 * sets exactly that query and nothing else. What the filter actually did was
 * strip the parameters an editor deliberately sends to another one — the
 * Explorer's `doc=` (open this document), its `create=1` (start a new one),
 * the Inbox's `createDraft=1`. Clicking a file in `/documents` landed in
 * Cortex with nothing open.
 *
 * <p>So: the caller builds the target URL and is the authority on what belongs
 * in it. A stale parameter is a bug at the call site, not something the router
 * should paper over — and papering over it here cost three working flows.
 */

const routes: RouteRecordRaw[] = [
  // `meta.title` replaces the per-file <title> each of these had as its own
  // HTML entry. Without it every tab and every history entry would read
  // "Vance" — the shell's own title — which is how you lose a window among
  // twenty. Strings kept verbatim from the deleted files rather than
  // translated: they name a surface, and the tab is where a person hunts.
  {
    path: '/',
    name: 'home',
    component: () => import('./LandingView.vue'),
    meta: { title: 'Vance' },
  },
  {
    path: '/cortex',
    name: 'cortex',
    component: () => import('@/cortex/EditorApp.vue'),
    meta: { title: 'Vance · Cortex' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/chat/ChatApp.vue'),
    meta: { title: 'Vance · Chat' },
  },
  {
    path: '/inbox',
    name: 'inbox',
    component: () => import('@/inbox/InboxApp.vue'),
    meta: { title: 'Vance · Inbox' },
  },
  {
    path: '/documents',
    name: 'documents',
    component: () => import('@/document/DocumentExplorerApp.vue'),
    meta: { title: 'Vance · Documents' },
  },

  // The `.html` forms these routes replaced. Kept as redirects rather than as
  // stub files: nginx already funnels every unknown path here (its fallback is
  // index.html, which is now the shell), so the redirect costs nothing and no
  // file has to be explained to anyone in two years. The query is carried
  // through — a bookmarked `/cortex?doc=…` must still open that document.
  // NB: written as string concatenation so a future sweep that strips `.html`
  // from cluster links cannot turn these aliases into self-redirects. That
  // already happened once during the migration itself.
  { path: `/cortex${'.html'}`, redirect: (to) => ({ path: '/cortex', query: to.query }) },
  { path: `/chat${'.html'}`, redirect: (to) => ({ path: '/chat', query: to.query }) },
  // Two renames ago the chat editor was `chat-editor.html`, and a redirect stub
  // sat in public/ for it. The stub is deleted — a route line does the same job
  // without a file nobody can date.
  { path: `/chat-editor${'.html'}`, redirect: (to) => ({ path: '/chat', query: to.query }) },
  { path: `/inbox${'.html'}`, redirect: (to) => ({ path: '/inbox', query: to.query }) },
  { path: `/documents${'.html'}`, redirect: (to) => ({ path: '/documents', query: to.query }) },
  // notepad merged into cortex in 2026-06 and its stub file is gone; this line
  // is what remains of it.
  { path: `/notepad${'.html'}`, redirect: (to) => ({ path: '/cortex', query: to.query }) },

  // Anything else inside the shell is the landing. Deliberately not a 404
  // page: the shell only ever receives a path nginx could not serve as a file,
  // which in practice is a typo or a stale link, and the landing is where a
  // person can carry on from.
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
  // Each editor is a full-height application that manages its own scroll
  // containers; restoring a window scroll position would fight them.
  scrollBehavior: () => false,
});

/**
 * Set the tab title for the route we just entered.
 *
 * `afterEach` on purpose: it runs before the route component mounts, so an
 * editor that computes a richer title of its own — Cortex writes
 * "Cortex · <project>" once a document is open — assigns after this and wins.
 * The order is what makes both work without either knowing about the other.
 */
router.afterEach((to) => {
  const title = to.meta?.title;
  if (typeof title === 'string') document.title = title;
});

/**
 * A route chunk that will not load is almost always a deploy: the entry chunks
 * are content-hashed, so a session older than the last release asks for a file
 * name that no longer exists. Reloading picks up the new bundle, and it is the
 * only moment where a reload is unambiguously right — the old session cannot
 * serve this navigation at all.
 *
 * <p>Guarded by a session-scoped flag, because the same failure shape occurs
 * when the network is simply down, and a reload loop is worse than an error.
 */
const RELOAD_FLAG = 'vance.chunkReload';

router.onError((error) => {
  const message = error instanceof Error ? error.message : String(error);
  const looksLikeChunk = /dynamically imported module|Importing a module script failed|Failed to fetch/i
    .test(message);
  if (!looksLikeChunk) return;
  try {
    if (sessionStorage.getItem(RELOAD_FLAG)) return;
    sessionStorage.setItem(RELOAD_FLAG, '1');
  } catch {
    // Private mode / storage disabled — reload once rather than never; a
    // second failure lands on the same branch and the browser's own error
    // takes over.
  }
  window.location.reload();
});
