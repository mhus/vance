/**
 * Remote control of this running CLI client from another device, over the
 * {@code clients} channel of the existing brain WebSocket.
 *
 * <p>Session- and project-independent: the WS exists before anything is bound,
 * so a foot is reachable while it works, whatever it is working on. Routing is
 * keyed by a process-stable {@code clientId} and never by pod, which is what
 * makes a reconnect onto a different brain pod a non-event.
 *
 * <p>Design and rejected alternatives: {@code planning/foot-remote-control.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot.remote;
