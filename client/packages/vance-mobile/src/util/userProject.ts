import { getUsername } from '@vance/shared';

/**
 * The per-user "home" project name. Mirrors the server-side
 * convention from `HomeBootstrapService.HUB_PROJECT_NAME_PREFIX` —
 * each user gets a `_user_<username>` project on first signup that
 * holds personal documents, sessions, etc.
 *
 * Returns `null` when no user is signed in (boot-time edge case).
 */
const HUB_PROJECT_NAME_PREFIX = '_user_';

export function getUserProjectName(): string | null {
  const u = getUsername();
  if (u === null) return null;
  return HUB_PROJECT_NAME_PREFIX + u;
}

export function isUserProject(projectName: string): boolean {
  return projectName.startsWith(HUB_PROJECT_NAME_PREFIX);
}
