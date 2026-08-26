/**
 * Central owner of the Cortex URL query contract.
 *
 * Cortex keeps its entire per-view state in the URL so it survives hard
 * navigations (chat ↔ chatless), browser history (back/forward), a plain
 * refresh (F5) and bookmarking — and stays fully independent per browser
 * tab. There is deliberately NO hidden storage (an earlier sessionStorage
 * approach was dropped because it lost state across mode switches and
 * produced restore/echo races): the URL *is* the state.
 *
 * Params owned here:
 *  - `open` — comma-separated open-tab document ids, in tab order.
 *  - `doc`  — the currently visible (active) tab. Always a member of `open`.
 *  - `bind` — chat-bind mode (`pinned` / `off`; omitted when the `auto` default).
 *  - `pin`  — the pinned document id (only with `bind=pinned`, must be in `open`).
 *  - `at`   — `0` when auto-target is off (omitted for the `true` default).
 *             Mirror of the localStorage-backed user preference
 *             (`vance:cortex:auto-target`); localStorage is the source of
 *             truth, the URL only carries it for shared/bookmarked links.
 *  - `sg`   — `0` when follow-up suggestions are off (omitted for the
 *             `true` default). Same mirror semantics as `at`
 *             (`vance:cortex:suggestions`).
 *  - `entry` — per-tab sub-position for application tabs: which workbook page,
 *             which wiki page, which canvas board is open inside the app.
 *             Comma-separated `<docId>:<handle>` pairs, handle percent-encoded,
 *             restricted to members of `open`.
 *  - `q`    — per-tab read parameters: the query of a parameterised view
 *             (`from=…&to=…` against a mounted document). Same
 *             `<docId>:<value>` shape as `entry` and for the same reason —
 *             two such tabs open at once must not fight over one param.
 *
 * Boot context (`project`, `sessionId`) is preserved verbatim. The one-shot
 * handoff params (`create`, `path`) are ALWAYS stripped by {@link writeCortexView}
 * so a rebuilt URL can never re-trigger the create-document modal.
 */

export type CortexBindMode = 'auto' | 'pinned' | 'off';

export interface CortexView {
  open: string[];
  doc: string | null;
  bind: CortexBindMode;
  pinned: string | null;
  autoTarget: boolean;
  suggestions: boolean;
  /**
   * Sub-position per open application tab, keyed by the tab's document id.
   * The value is the app's own opaque handle — a page id in Workbook and
   * Canvasbook, a space-qualified slug in Wiki.
   *
   * Owned here rather than by each app: Workbook and Wiki both used to write a
   * bare `?page=` straight onto the location, so two such tabs open at once
   * fought over one param and the second one won (planning/inter-links.md
   * §5.2). Keying by document id is what makes them independent — and it is
   * the same reason `open`/`doc` live here and not in the tabs.
   */
  entries: Record<string, string>;
  /**
   * Read parameters per open tab, keyed by document id — the query of a
   * parameterised view, without the leading `?`.
   *
   * Part of the view rather than of the document because that is what it
   * is: the same row answers differently per set of parameters
   * (`specification/public/jaglan-system.md` §5a). Keeping it here is also
   * what makes the view *shareable* — a bookmarked chart over a window is
   * only reproducible if the window is in the URL.
   */
  queries: Record<string, string>;
}

const OPEN_PARAM = 'open';
const DOC_PARAM = 'doc';
const BIND_PARAM = 'bind';
const PIN_PARAM = 'pin';
const AUTOTARGET_PARAM = 'at';
const SUGGESTIONS_PARAM = 'sg';
const ENTRY_PARAM = 'entry';
const QUERY_PARAM = 'q';

/**
 * Separator between a tab's document id and its handle inside one `entry`
 * pair. `:` and `,` both survive a round trip because the handle is
 * percent-encoded and `encodeURIComponent` escapes both — unlike `~` or `-`,
 * which it leaves alone and which a handle may therefore contain.
 */
const ENTRY_SEPARATOR = ':';

/** One-shot handoff params that must never survive a URL rebuild. */
const TRANSIENT_PARAMS = ['create', 'path'] as const;

/**
 * Every param this module owns, plus the boot context it preserves. The
 * complement is what {@link splitForeignParams} hands to a `?path=` handoff
 * as read parameters — see there for why the leftovers are collected at all.
 */
const KNOWN_PARAMS = new Set<string>([
  OPEN_PARAM, DOC_PARAM, BIND_PARAM, PIN_PARAM, AUTOTARGET_PARAM,
  SUGGESTIONS_PARAM, ENTRY_PARAM, QUERY_PARAM,
  ...TRANSIENT_PARAMS,
  'project', 'sessionId',
]);

/**
 * Split a raw query string into the params Cortex knows and the rest.
 *
 * <p>Needed because a query cannot be nested inside a query without
 * encoding: in `?path=a.yaml?from=1&to=2` the `&` ends the `path` param, so
 * `to` arrives as a URL param of its own and the read parameters would be
 * silently cut in half. Collecting the leftovers is what makes that link
 * typeable by hand — which is the entire point of the handoff.
 *
 * <p>It is safe precisely because the owned set is closed: a param outside
 * it means nothing to Cortex today and would have been ignored anyway. Both
 * halves keep their original percent-encoding — they are handed on as
 * written, never re-serialised.
 */
export function splitForeignParams(search: string): { known: string; foreign: string } {
  const known: string[] = [];
  const foreign: string[] = [];
  for (const pair of search.replace(/^\?/, '').split('&')) {
    if (!pair) continue;
    const eq = pair.indexOf('=');
    const key = eq < 0 ? pair : pair.slice(0, eq);
    (KNOWN_PARAMS.has(key) ? known : foreign).push(pair);
  }
  return { known: known.join('&'), foreign: foreign.join('&') };
}

/** Defensive cap so a pathological open-set can't blow the URL length. */
const MAX_OPEN = 40;

/** Split a comma list into a deduped, trimmed, capped id array. */
function parseIdList(raw: string | null): string[] {
  if (!raw) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const part of raw.split(',')) {
    const id = part.trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(id);
    if (out.length >= MAX_OPEN) break;
  }
  return out;
}

/**
 * Read the current Cortex view from a query string. Normalises the
 * {@code open}/{@code doc} contract: a lone {@code ?doc=} (deep-link or
 * legacy bookmark) with no {@code ?open=} is treated as a single open tab,
 * and {@code doc} is forced to be a member of {@code open} (falling back to
 * the first open tab).
 */
export function readCortexView(search: string = window.location.search): CortexView {
  const p = new URLSearchParams(search);

  const open = parseIdList(p.get(OPEN_PARAM));
  let doc = p.get(DOC_PARAM);
  if (doc && !open.includes(doc)) open.unshift(doc);
  if ((!doc || !open.includes(doc)) && open.length > 0) doc = open[0];
  if (open.length === 0) doc = null;

  const bindRaw = p.get(BIND_PARAM);
  const bind: CortexBindMode = bindRaw === 'pinned' || bindRaw === 'off' ? bindRaw : 'auto';

  let pinned = bind === 'pinned' ? p.get(PIN_PARAM) : null;
  if (pinned && !open.includes(pinned)) pinned = null;

  const autoTarget = p.get(AUTOTARGET_PARAM) !== '0';
  const suggestions = p.get(SUGGESTIONS_PARAM) !== '0';
  const openSet = new Set(open);
  const entries = parsePerTab(p.get(ENTRY_PARAM), openSet);
  const queries = parsePerTab(p.get(QUERY_PARAM), openSet);

  return {
    open: open.slice(0, MAX_OPEN),
    doc,
    bind,
    pinned,
    autoTarget,
    suggestions,
    entries,
    queries,
  };
}

/**
 * Read a `<docId>:<value>,…` list, dropping pairs whose tab is not open.
 * Shared by `entry` (sub-position) and `q` (read parameters) — two things
 * that differ in meaning but not in shape: both are one opaque,
 * percent-encoded value per open tab.
 *
 * Lenient throughout: a malformed pair is skipped rather than failing the whole
 * read. The URL is user-editable and arrives from links written elsewhere — a
 * single bad pair must not cost the open-tab set.
 */
function parsePerTab(raw: string | null, open: Set<string>): Record<string, string> {
  if (!raw) return {};
  const out: Record<string, string> = {};
  for (const part of raw.split(',')) {
    const sep = part.indexOf(ENTRY_SEPARATOR);
    if (sep <= 0) continue;
    const docId = part.slice(0, sep).trim();
    if (!docId || !open.has(docId) || docId in out) continue;
    const encoded = part.slice(sep + 1);
    if (!encoded) continue;
    let handle: string;
    try {
      handle = decodeURIComponent(encoded);
    } catch {
      continue; // malformed percent-escape
    }
    if (handle) out[docId] = handle;
  }
  return out;
}

/**
 * Serialise a view onto an existing query string, preserving unrelated
 * params (`project`, `sessionId`, …) and always dropping the transient
 * handoff params. Defaults (`bind=auto`, `at=true`, `sg=true`) are omitted to keep the
 * URL clean. Returns the query string WITHOUT a leading `?`.
 */
export function writeCortexView(base: string, view: CortexView): string {
  const p = new URLSearchParams(base);
  for (const t of TRANSIENT_PARAMS) p.delete(t);

  const open = parseIdList(view.open.join(','));
  if (open.length > 0) p.set(OPEN_PARAM, open.join(',')); else p.delete(OPEN_PARAM);

  const doc = view.doc && open.includes(view.doc) ? view.doc : (open[0] ?? null);
  if (doc) p.set(DOC_PARAM, doc); else p.delete(DOC_PARAM);

  if (view.bind !== 'auto') p.set(BIND_PARAM, view.bind); else p.delete(BIND_PARAM);
  if (view.bind === 'pinned' && view.pinned && open.includes(view.pinned)) {
    p.set(PIN_PARAM, view.pinned);
  } else {
    p.delete(PIN_PARAM);
  }

  if (!view.autoTarget) p.set(AUTOTARGET_PARAM, '0'); else p.delete(AUTOTARGET_PARAM);
  if (!view.suggestions) p.set(SUGGESTIONS_PARAM, '0'); else p.delete(SUGGESTIONS_PARAM);

  // Only for tabs that are actually open — a closed tab's sub-position is
  // meaningless and would otherwise accumulate in the address bar forever.
  // Order follows `open`, so the same view always serialises identically and
  // the equality guard in EditorApp.syncUrl keeps working.
  writePerTab(p, ENTRY_PARAM, open, view.entries);
  // Same treatment for the read parameters: a query whose tab was closed
  // describes nothing, and leaving it in the address bar would resurrect a
  // window on the next open.
  writePerTab(p, QUERY_PARAM, open, view.queries);

  return p.toString();
}

/** Serialise one `<docId>:<value>,…` list, or drop the param when empty. */
function writePerTab(
  p: URLSearchParams,
  param: string,
  open: string[],
  values: Record<string, string> | undefined,
): void {
  // Tolerates a missing map rather than throwing: this runs on every URL
  // write, and a view assembled by hand (a caller predating the field) must
  // not take the address bar down with it.
  const map = values ?? {};
  const pairs = open
    .filter((id) => map[id])
    .map((id) => `${id}${ENTRY_SEPARATOR}${encodeURIComponent(map[id])}`);
  if (pairs.length > 0) p.set(param, pairs.join(',')); else p.delete(param);
}
