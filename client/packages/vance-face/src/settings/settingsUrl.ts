/**
 * Central owner of the Settings URL query contract — the Settings page's
 * counterpart of {@code cortexUrl.ts}. The URL *is* the state: scope
 * selection, the active tab and the open form / area / entry all live in
 * the query so a hard refresh, browser back/forward and shared links all
 * reproduce the same view. There is deliberately no hidden storage.
 *
 * <p>Params owned here:
 *  - `scope` — the selected scope row. {@code tenant} and {@code user}
 *    are keywords; any other value is a project name.
 *  - `tab`   - {@code areas} (settings-doc kinds), {@code raw} (the
 *    advanced key/value editor), {@code properties} (tenant/group/
 *    project properties + kits; not valid for user scopes) or omitted
 *    for the default forms tab.
 *  - `group` — selected project-group row (properties tab, tenant
 *    scope base). Cleared by any scope switch.
 *  - `form`  — name of the open Setting Form (forms tab).
 *  - `area`  — kind id of the open area (Bereiche tab).
 *  - `entry` — document id of the open area entry (Bereiche tab).
 *
 * <p>Defaults are never serialized: the forms tab, no open form, no open
 * area/entry produce a bare {@code ?scope=…}.
 */

/** The three persisted setting layers in their wire form. */
export type SettingsScopeKind = 'tenant' | 'user' | 'project';

/** Tabs: areas (settings-doc kinds) · forms (default) · properties (tenant/group/project properties, kits) · raw (advanced). */
export type SettingsTab = 'areas' | 'forms' | 'properties' | 'raw';

export interface SettingsView {
  /** Scope keyword (`tenant`/`user`) or project name. */
  scope: string;
  /** Active tab; the forms tab is the default. */
  tab: SettingsTab;
  /** Open Setting Form name, or {@code null} for the form listing. */
  form: string | null;
  /** Open area kind id (Bereiche tab), or {@code null} for the area list. */
  area: string | null;
  /** Open area entry document id, or {@code null} for the entry list. */
  entry: string | null;
  /** Selected project-group row (properties tab), or {@code null}. */
  group: string | null;
}

const SCOPE_PARAM = 'scope';
const TAB_PARAM = 'tab';
const FORM_PARAM = 'form';
const AREA_PARAM = 'area';
const ENTRY_PARAM = 'entry';
const GROUP_PARAM = 'group';

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
 * Unknown or missing scope falls back to the supplied default; unknown
 * tab values fall back to the forms tab.
 */
export function parseSettingsView(
  search: string,
  fallbackScope: string,
): SettingsView {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const scope = params.get(SCOPE_PARAM)?.trim() || fallbackScope;
  const tabParam = params.get(TAB_PARAM);
  const tab: SettingsTab =
    tabParam === 'areas' || tabParam === 'properties' || tabParam === 'raw'
      ? tabParam
      : 'forms';
  return {
    scope,
    tab,
    form: params.get(FORM_PARAM)?.trim() || null,
    area: params.get(AREA_PARAM)?.trim() || null,
    entry: params.get(ENTRY_PARAM)?.trim() || null,
    group: params.get(GROUP_PARAM)?.trim() || null,
  };
}

/** Serialises the view to a query string with leading `?` (or `''`). */
export function serializeSettingsView(view: SettingsView): string {
  const params = new URLSearchParams();
  params.set(SCOPE_PARAM, view.scope);
  if (view.tab !== 'forms') params.set(TAB_PARAM, view.tab);
  if (view.form) params.set(FORM_PARAM, view.form);
  if (view.area) params.set(AREA_PARAM, view.area);
  if (view.entry) params.set(ENTRY_PARAM, view.entry);
  if (view.group) params.set(GROUP_PARAM, view.group);
  const s = params.toString();
  return s ? `?${s}` : '';
}

/** The query string for a view, relative to the page (`settings.html?…`). */
export function settingsHref(view: SettingsView): string {
  return `/settings.html${serializeSettingsView(view)}`;
}

/**
 * Navigates the browser history to a view (back/forward lands here
 * again). Callers that only refine the current view — e.g. a selection
 * restored after a listing reload — use {@link replaceSettingsView}
 * instead.
 */
export function pushSettingsView(view: SettingsView): void {
  window.history.pushState(null, '', settingsHref(view));
}

/** Rewrites the current history entry to a view. */
export function replaceSettingsView(view: SettingsView): void {
  window.history.replaceState(null, '', settingsHref(view));
}
