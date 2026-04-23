/**
 * Session persistence.
 *
 * <p>A session survives beyond the WebSocket connection that created it: the
 * client may disconnect and later resume, possibly on a different brain pod.
 * Only one connection (and therefore one pod) may hold the active binding at
 * any moment — enforced by an atomic {@code findAndModify} on
 * {@link de.mhus.vance.shared.session.SessionDocument#getBoundConnectionId()}.
 *
 * <p>Colocated: document + package-private repository + service.
 */
@NullMarked
package de.mhus.vance.shared.session;

import org.jspecify.annotations.NullMarked;
