package de.mhus.vance.api.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server → Client push payload on the {@code documents} channel:
 * "document at {@link #path} was written/deleted".
 *
 * <p>Pure invalidation signal — the wire frame carries no body and no
 * diff. Clients decide for themselves how to react: refetch via REST,
 * show an "outdated" banner, soft-merge, or ignore (e.g. when the local
 * tab is the one that just authored the change).
 *
 * <p>Wire frame:
 *
 * <pre>{@code
 * { "channel": "documents",
 *   "payload": { "type": "changed",
 *                "data": { "path": "documents/notes.md",
 *                          "kind": "upserted" } } }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("ws")
public class DocumentChangedNotification {

    /** Document path that changed. */
    private String path;

    /** {@code "upserted"} (create or content-replace) or {@code "deleted"}. */
    private String kind;

    /**
     * Writer identity — the editor instance that performed the write,
     * as captured by the REST {@code X-Editor-Id} header. Used by the
     * server to skip the writer's own connection during local fan-out
     * (so it never appears here). Recipients with multiple tabs on the
     * same WS that this event reached can still use it as a routing
     * signal; otherwise informational.
     */
    private @org.jspecify.annotations.Nullable String editorId;

    /** User id of the writer — for the {@code ⏺ name} awareness badge. */
    private @org.jspecify.annotations.Nullable String editorUserId;

    /** Display name of the writer — same as above; preferred for UI. */
    private @org.jspecify.annotations.Nullable String editorDisplayName;
}
