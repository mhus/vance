package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Server → Client push payload for
 * {@link de.mhus.vance.api.ws.MessageType#DOCUMENT_INVALIDATE}.
 *
 * <p>Tells the receiving session that a document was mutated by a
 * server-side tool on its behalf. The receiver decides how to react —
 * Cortex tabs refresh the body (with 3-way-merge when dirty), other
 * clients can ignore.
 *
 * <p>The frame is intentionally minimal: id + path + a coarse
 * {@code kind} signal ({@code body} / {@code notes}) so the client can
 * potentially do narrower work (e.g. only re-fetch notes), but is also
 * free to just reload the whole doc.
 *
 * <p>See {@code planning/cortex-document-invalidation.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentInvalidateNotification {

    /** Document MongoDB id. */
    private String documentId;

    /** Virtual project-path of the document, e.g. {@code "notes/foo.md"}. */
    private @Nullable String path;

    /**
     * {@code "body"} for content writes, {@code "notes"} for sticky-note
     * mutations. Lower-case enum-style string for forward-compat with
     * additional kinds (e.g. {@code "tags"}, {@code "summary"}).
     */
    private String kind;
}
