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
}

const OPEN_PARAM = 'open';
const DOC_PARAM = 'doc';
const BIND_PARAM = 'bind';
const PIN_PARAM = 'pin';
const AUTOTARGET_PARAM = 'at';
const SUGGESTIONS_PARAM = 'sg';
const ENTRY_PARAM = 'entry';

/**
 * Separator between a tab's document id and its handle inside one `entry`
 * pair. `:` and `,` both survive a round trip because the handle is
 * percent-encoded and `encodeURIComponent` escapes both — unlike `~` or `-`,
 * which it leaves alone and which a handle may therefore contain.
 */
const ENTRY_SEPARATOR = ':';

/** One-shot handoff params that must never survive a URL rebuild. */
const TRANSIENT_PARAMS = ['create', 'path'] as const;

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
  const entries = parseEntries(p.get(ENTRY_PARAM), openSet);

  return { open: open.slice(0, MAX_OPEN), doc, bind, pinned, autoTarget, suggestions, entries };
}

/**
 * Read `entry=<docId>:<handle>,…`, dropping pairs whose tab is not open.
 *
 * Lenient throughout: a malformed pair is skipped rather than failing the whole
 * read. The URL is user-editable and arrives from links written elsewhere — a
 * single bad pair must not cost the open-tab set.
 */
function parseEntries(raw: string | null, open: Set<string>): Record<string, string> {
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
  const entryPairs = open
    .filter((id) => view.entries[id])
    .map((id) => `${id}${ENTRY_SEPARATOR}${encodeURIComponent(view.entries[id])}`);
  if (entryPairs.length > 0) p.set(ENTRY_PARAM, entryPairs.join(',')); else p.delete(ENTRY_PARAM);

  return p.toString();
}

/**
 * Build a full {@code /cortex.html} target for a hard navigation, carrying
 * the given view. Used when opening a chat from the session picker or
 * leaving a chat back into chatless mode, so the open tabs survive the
 * cross-mode reload instead of vanishing.
 */
export function cortexHref(
  ctx: { project?: string | null; sessionId?: string | null },
  view: CortexView,
): string {
  const p = new URLSearchParams();
  if (ctx.sessionId) p.set('sessionId', ctx.sessionId);
  if (ctx.project) p.set('project', ctx.project);
  const qs = writeCortexView(p.toString(), view);
  return `/cortex.html${qs ? `?${qs}` : ''}`;
}
