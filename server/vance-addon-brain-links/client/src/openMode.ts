/**
 * How a link opens — the three modes a list or a single entry can ask for.
 *
 * Resolution is a coalesce the client owns: the entry's value, then the
 * list's, then `tab` (the behaviour the app shipped with). The server sends
 * the raw values (entry override nullable, list default always present) and
 * guarantees them valid — {@link asOpenMode} still refuses anything else so
 * a stale bundle or a hand-edited manifest degrades to `tab` instead of
 * throwing inside a click handler.
 */
export type OpenMode = 'tab' | 'same' | 'frame';

export const OPEN_MODES: readonly OpenMode[] = ['tab', 'same', 'frame'];

export const DEFAULT_OPEN: OpenMode = 'tab';

export function asOpenMode(value: string | null | undefined): OpenMode {
  return OPEN_MODES.includes(value as OpenMode) ? (value as OpenMode) : DEFAULT_OPEN;
}

/**
 * The effective mode for one entry: its own override, else the list default.
 */
export function resolveOpen(
  entryOpen: string | null | undefined,
  listOpen: string | null | undefined,
): OpenMode {
  if (entryOpen != null && entryOpen !== '') return asOpenMode(entryOpen);
  return asOpenMode(listOpen);
}
