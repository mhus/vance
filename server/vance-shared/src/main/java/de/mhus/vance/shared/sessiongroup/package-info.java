/**
 * Session-Group domain — a per-user, per-project grouping of sessions that
 * exists purely for UI organisation. No scope, no rights, no cascade; it is
 * <em>not</em> a new level of the scope tree (Tenant → Project-Group →
 * Project → Session → Think-Process stays untouched).
 *
 * <p>The grouping is a per-user view fact and therefore does <em>not</em> sit
 * on the (potentially shared) {@code SessionDocument}: the group owns the list
 * of its member session ids. The same shared session may appear in different
 * users' groups without conflict.
 *
 * <p>Colocated: {@link de.mhus.vance.shared.sessiongroup.SessionGroupDocument},
 * package-private {@link de.mhus.vance.shared.sessiongroup.SessionGroupRepository}
 * and {@link de.mhus.vance.shared.sessiongroup.SessionGroupService} — only the
 * service is exposed. See {@code planning/session-groups.md}.
 */
@NullMarked
package de.mhus.vance.shared.sessiongroup;

import org.jspecify.annotations.NullMarked;
