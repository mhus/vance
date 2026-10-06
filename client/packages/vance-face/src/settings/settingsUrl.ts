/**
 * Central owner of the Settings URL query contract — the Settings page's
 * counterpart of {@code cortexUrl.ts}. The URL *is* the state: scope
 * selection, the open Setting Form and the active tab all live in the
 * query so a hard refresh, browser back/forward and shared links all
 * reproduce the same view. There is deliberately no hidden storage.
 *
 * <p>Params owned here:
 *  - `scope` — the selected scope row. {@code tenant} and {@code user}
 *    are keywords; any other value is a project name.
 *  - `form`  — name of the open Setting Form (omitted when the
 *    category listing is shown).
 *  - `tab`   — {@code raw} for the advanced key/value editor; omitted
 *    for the default guided view.
 *
 * <p>The one-shot boot context `project` is accepted on parse and ignored
 * for state purposes — hosts that deep-link in from another surface
 * sometimes carry it.
 */

/** The three persisted setting layers in their wire form. */
export type SettingsScopeKind = 'tenant' | 'user' | 'project';

export type SettingsTab = 'guided' | 'raw';

export interface SettingsView {
  /** Scope keyword (`tenant`/`user`) or project name. */
  scope: string;
  /** Open Setting Form name, or {@code null} for the category listing. */
  form: string | null;
  /** Active tab; {@code raw} is the advanced key/value editor. */
  tab: SettingsTab;
}

const SCOPE_PARAM = 'scope';
const FORM_PARAM = 'form';
const TAB_PARAM = 'tab';

export const TENANT_SCOPE = 'tenant';
export const USER_SCOPE = 'user';

/** The scope value is a keyword, not a project name. */
export function scopeKeyword(scope: string): SettingsScopeKind | null {
  if (scope === TENANT_SCOPE) return 'tenant';
  if (scope === USER_SCOPE) return 'user';
  return null;
}

/**
 * Reads the view off a query string (with or without leading `?`).
 * Unknown or missing scope falls back to the supplied default.
 */
export function parseSettingsView(
  search: string,
  fallbackScope: string,
): SettingsView {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const scope = params.get(SCOPE_PARAM)?.trim() || fallbackScope;
  const tabParam = params.get(TAB_PARAM);
  return {
    scope,
    form: params.get(FORM_PARAM)?.trim() || null,
    tab: tabParam === 'raw' ? 'raw' : 'guided',
  };
}

/** Serialises the view to a query string with leading `?` (or `''`). */
export function serializeSettingsView(view: SettingsView): string {
  const params = new URLSearchParams();
  params.set(SCOPE_PARAM, view.scope);
  if (view.form) params.set(FORM_PARAM, view.form);
  if (view.tab === 'raw') params.set(TAB_PARAM, 'raw');
  const s = params.toString();
  return s ? `?${s}` : '';
}

/** The query string for a view, relative to the page (`settings.html?…`). */
export function settingsHref(view: SettingsView): string {
  return `/settings.html${serializeSettingsView(view)}`;
}

/**
 * Navigates the browser history to a view (back/forward lands here
 * again). Callers that only refine the current view — e.g. a form
 * selection restored after a listing reload — use
 * {@link replaceSettingsView} instead.
 */
export function pushSettingsView(view: SettingsView): void {
  window.history.pushState(null, '', settingsHref(view));
}

/** Rewrites the current history entry to a view. */
export function replaceSettingsView(view: SettingsView): void {
  window.history.replaceState(null, '', settingsHref(view));
}
