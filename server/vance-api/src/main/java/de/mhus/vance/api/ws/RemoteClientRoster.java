package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Brain → watcher: the CLI clients of the requesting user, across all pods.
 *
 * <p>Read from the Redis roster hash, so it sees clients on peer pods too.
 * Without Redis the answer is pod-local — the same limitation
 * {@code documents}/{@code pointers}/{@code signals} already have.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class RemoteClientRoster {

    /** Clients of this user, newest heartbeat first. */
    private List<RemoteClientInfo> clients;

    /**
     * False when Redis is off and the roster therefore only covers clients
     * connected to this very pod. Surfaced so the UI can say so instead of
     * implying "you have no other clients".
     */
    private boolean crossPod;
}
