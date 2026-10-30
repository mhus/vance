import type { WebUiLevel } from '@/platform';

/**
 * Folders at the root of a project that hold system configuration rather than
 * the reader's own documents. The standard surface leaves them out of folder
 * lists; the expert surface shows everything.
 *
 * A list filter only: the folder stays reachable by path (a link, the URL, a
 * search hit) and the brain applies no rule of its own to it.
 */
const SYSTEM_ROOT_FOLDERS: ReadonlySet<string> = new Set(['_vance']);

/**
 * Whether the folder `name` directly under `parentPath` is left out of a list
 * for a reader on {@code level}. `parentPath` is `''` (or slashes only) for
 * the project root; only that level is filtered — a nested folder that happens
 * to be called `_vance` is the reader's own.
 */
export function isHiddenFolder(parentPath: string, name: string, level: WebUiLevel): boolean {
  if (level === 'expert') return false;
  if (parentPath.replace(/\/+$/, '') !== '') return false;
  return SYSTEM_ROOT_FOLDERS.has(name);
}
