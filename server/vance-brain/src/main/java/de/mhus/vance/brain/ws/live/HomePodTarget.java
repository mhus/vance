package de.mhus.vance.brain.ws.live;

import org.jspecify.annotations.Nullable;

/**
 * Routing target for a session-channel frame on the Face-Pod.
 *
 * <p>Two cases:
 * <ul>
 *   <li>{@link #LOCAL} — the project's home-pod is this pod (or could not be
 *       determined); the frame is processed via the local chat-handler
 *       pipeline.</li>
 *   <li>{@link #remote(String)} — the project lives on another brain pod; the
 *       frame must be forwarded through the pod-to-pod chat tunnel to the
 *       given {@code host:port} endpoint.</li>
 * </ul>
 */
public record HomePodTarget(boolean local, @Nullable String endpoint) {

    /** Singleton for the "process locally" decision. */
    public static final HomePodTarget LOCAL = new HomePodTarget(true, null);

    /** Constructs a remote target carrying the home-pod endpoint. */
    public static HomePodTarget remote(String endpoint) {
        return new HomePodTarget(false, endpoint);
    }

    /** Returns the endpoint or throws if the target is local. */
    public String requireEndpoint() {
        if (local || endpoint == null) {
            throw new IllegalStateException("Target is local — no endpoint");
        }
        return endpoint;
    }
}
