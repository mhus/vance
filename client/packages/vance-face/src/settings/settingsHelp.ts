import type { SettingsView } from './settingsUrl';

/**
 * Right-panel help resolution for the Settings page — the Settings
 * counterpart of {@code cortex/help.ts}. The panel shows one help file
 * per context, three levels deep:
 *
 * <ol>
 *   <li><b>Entry open</b> (file editor): {@code doc-kind-<kindId>.md} —
 *   the Cortex convention, the very same file the Cortex help tab shows
 *   for that document. One file per kind, one place to maintain.</li>
 *   <li><b>Area open</b> (entry list): {@code settings-area-<areaId>.md} —
 *   what this area configures, how entries are named, which cascade tier
 *   writes win. Settings-specific: no Cortex counterpart.</li>
 *   <li><b>Anything else</b> (area list, forms/properties/raw tab):
 *   {@code settings.md} — the page itself.</li>
 * </ol>
 *
 * <p>The brain serves these from {@code help/{lang}/<path>}, falling
 * back to {@code en} when a language file is missing. A missing file
 * surfaces as the panel's "no help yet" hint, not an error — coverage
 * can grow incrementally.
 */

/** Kind/area ids are not file names — the brain's help endpoint rejects
 * anything outside [A-Za-z0-9._-], so fold the rest to hyphens. */
function helpFileSegment(id: string): string {
  return id.replace(/[^A-Za-z0-9._-]+/g, '-');
}

/**
 * The help file for the current view. {@code entryKind} is the open row's
 * kind id — rows carry their own, because an area can host more than one
 * (a model-area row can be a provider sidecar, not the area's own kind).
 */
export function resolveSettingsHelpPath(
  view: SettingsView,
  entryKind: string | null,
): string {
  if (view.entry && entryKind) {
    return `doc-kind-${helpFileSegment(entryKind)}.md`;
  }
  if (view.area) {
    return `settings-area-${helpFileSegment(view.area)}.md`;
  }
  return 'settings.md';
}
