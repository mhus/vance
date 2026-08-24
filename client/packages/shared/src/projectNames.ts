/**
 * Reserved project names, mirrored from the server.
 *
 * <p>These are not conventions a client may choose — they are the names the
 * brain bootstraps and looks up by. Mirrored rather than fetched because they
 * are derived from a login the client already holds, and a round-trip to be
 * told `_user_` + the name we passed in buys nothing.
 *
 * <p>Server-side twin: {@code HomeBootstrapService} in {@code vance-shared}.
 * If the prefix ever changes there, it changes here.
 */

const HUB_PROJECT_NAME_PREFIX = '_user_';

/**
 * The per-user Hub project of {@code userLogin} — where that user's own
 * assistant, memory and personal documents live. The brain guarantees it
 * exists (created on first login, idempotently).
 */
export function hubProjectName(userLogin: string): string {
  return HUB_PROJECT_NAME_PREFIX + userLogin;
}

/** The tenant-wide defaults project — override layer for system documents. */
export const TENANT_PROJECT_NAME = '_tenant';
