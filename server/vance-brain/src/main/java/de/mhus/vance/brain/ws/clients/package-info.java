/**
 * The {@code clients} Live-WS channel: remote control of running CLI clients.
 *
 * <p>Session- and project-independent — a foot is reachable from the moment its
 * WebSocket is up, whatever it is working on. Everything routes by a
 * process-stable {@code clientId} over Redis pub/sub, never by pod, which is
 * what makes a client reconnecting onto another pod invisible to its watchers.
 *
 * <p>Design and rejected alternatives: {@code planning/foot-remote-control.md}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.ws.clients;
