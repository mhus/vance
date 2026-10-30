import type { WebUiLevel } from '@/platform';

/**
 * The tenant's configuration project. Every project cascades from it, so a
 * reader with rights on it sees it in the project lists — but it holds
 * tenant-wide defaults, not the reader's work.
 */
export const TENANT_PROJECT = '_tenant';

/**
 * Whether the project `name` is left out of a project list for a reader on
 * {@code level}. The standard surface does not list the tenant project; the
 * expert surface shows everything.
 *
 * A list filter only: the project stays reachable by name and the brain
 * applies no rule of its own to it.
 */
export function isHiddenProject(name: string, level: WebUiLevel): boolean {
  return level !== 'expert' && name === TENANT_PROJECT;
}
