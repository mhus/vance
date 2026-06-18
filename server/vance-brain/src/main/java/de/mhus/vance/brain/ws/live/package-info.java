/**
 * Face-Pod proxy implementation for the {@code /brain/{tenant}/ws}
 * multi-channel endpoint.
 *
 * <p>When a session-channel frame arrives on a Face-Pod, the routing chain
 * in this package decides whether the project's home-pod is local (dispatch
 * via the existing chat handlers) or remote (open or reuse a
 * {@code /internal/{tenant}/ws/chat} tunnel and pipe frames bidirectionally).
 *
 * <p>See {@code planning/live-ws.md} §7 for the proxy contract.
 */
@NullMarked
package de.mhus.vance.brain.ws.live;

import org.jspecify.annotations.NullMarked;
