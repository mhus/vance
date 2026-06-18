package de.mhus.vance.brain.ws.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.ws.DocumentViewer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Pod-to-pod membership delta on the {@code documents.presence} Redis
 * topic ({@code vance:{tenantId}:documents.presence}). One message per
 * subscribe/unsubscribe; tells other pods how to update their
 * {@code remoteByPath} shadow of presence state.
 *
 * <p>Three actions:
 * <ul>
 *   <li>{@link Action#ADD} — viewer joined {@link #path} on
 *       {@link #podId}</li>
 *   <li>{@link Action#REMOVE} — viewer left {@link #path} on
 *       {@link #podId}</li>
 *   <li>{@link Action#CLEAR_POD} — the source pod is going away;
 *       receivers drop all entries originating from it. Sent at
 *       graceful shutdown and synthesised by recipients via the
 *       {@code ClusterService} liveness check.</li>
 * </ul>
 *
 * <p>Internal wire format only — never seen by external clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PresenceDelta {

    public enum Action {
        ADD,
        REMOVE,
        CLEAR_POD
    }

    private String podId;
    private String tenantId;
    private Action action;

    /** Document path the delta is about. Required for {@link Action#ADD} and {@link Action#REMOVE}. */
    private @Nullable String path;

    /** Viewer payload. Required for {@link Action#ADD} and {@link Action#REMOVE}. */
    private @Nullable DocumentViewer viewer;
}
