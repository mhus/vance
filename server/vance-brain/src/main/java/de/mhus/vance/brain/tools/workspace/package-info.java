/**
 * Session-scoped workspace tools.
 *
 * <p>Each session gets a private directory under
 * {@code vance.workspace.base-dir/<sessionId>/} where the LLM can
 * create, read, edit, and delete files, and execute JavaScript from
 * disk. The workspace survives process lifecycle (start/resume/stop);
 * cleanup happens later when sessions are explicitly closed.
 *
 * <p>Path handling is <em>minimally</em> sandboxed: every relative
 * path is normalised and checked with {@code startsWith(root)} to
 * block {@code ..} and absolute-path escapes. Symlinks, quotas, and
 * anything resembling a real sandbox are deferred.
 */
@NullMarked
package de.mhus.vance.brain.tools.workspace;

import org.jspecify.annotations.NullMarked;
