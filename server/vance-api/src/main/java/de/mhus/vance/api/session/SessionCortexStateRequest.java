package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Replace-the-whole-thing update of a session's Cortex view state —
 * the open document tabs and the chat-bound document. Issued by the
 * Cortex client on tab open/close/reorder and on bind changes; the
 * server overwrites the persisted state in one PUT.
 *
 * <p>Replacement semantics (not patch): a missing or {@code null}
 * {@code openDocumentIds} means "clear all tabs"; the same for
 * {@code chatBoundDocumentId} means "no chat-bound document".
 *
 * <p>See planning/cortex.md §4.1.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionCortexStateRequest {

    /**
     * Document ids open as tabs, in tab order (leftmost first). When
     * {@code null} or empty, all tabs are cleared.
     */
    private @Nullable List<String> openDocumentIds;

    /**
     * Document the Cortex chat is bound to. Must appear in
     * {@link #openDocumentIds} when set. {@code null} clears the bind.
     */
    private @Nullable String chatBoundDocumentId;
}
