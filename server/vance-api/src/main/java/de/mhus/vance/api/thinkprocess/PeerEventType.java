package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The kind of cross-session notification a hub engine (e.g.
 * {@code vance}) emits to its peers — other hub processes of the same
 * user. Carried by {@code SteerMessage.PeerEvent} on the engine side
 * and the persistent {@code PendingMessageDocument} on the storage
 * side (with {@code type = PEER_EVENT}).
 *
 * <p>Peer events are hub-only: regular worker engines (Arthur, Marvin)
 * do not emit or consume them. See
 * {@code specification/vance-engine.md} §5.3.
 */
@GenerateTypeScript("thinkprocess")
public enum PeerEventType {
    /** A new project was created by a peer hub. */
    PROJECT_CREATED,
    /** A project was archived by a peer hub. */
    PROJECT_ARCHIVED,
    /** A peer hub spawned a worker process. */
    PROCESS_SPAWNED,
    /** A peer hub observed a state change on a worker it spawned. */
    PROCESS_STATUS_CHANGED,
    /** A peer hub captured a notable user statement. */
    USER_STATEMENT,
    /** Generic informational note worth surfacing to peers. */
    NOTE
}
