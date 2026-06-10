package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /brain/{tenant}/documents/{id}}.
 *
 * <p>Each field is independently optional — fields left {@code null} are not
 * touched on the server. This lets the UI patch the title without re-sending
 * the entire content. Body edits flow through
 * {@code PUT /brain/{tenant}/documents/{id}/content} (raw streaming
 * endpoint); {@link #inlineText} on this DTO is the legacy small-text
 * convenience for callers that still POST JSON.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentUpdateRequest {

    private @Nullable String title;

    private @Nullable List<String> tags;

    /**
     * Legacy convenience for small text bodies. The web/mobile editor uses
     * the streaming {@code PUT /content} endpoint instead — but Foot,
     * brain-internal callers and the script-cortex flow still set this
     * field. Server-side it goes through {@link
     * de.mhus.vance.shared.document.DocumentService#update}'s
     * streaming-store path, so size is no concern.
     */
    @Deprecated
    private @Nullable String inlineText;

    /**
     * Change the document's MIME type. The original guess (from the
     * upload or initial create) is sometimes wrong — e.g. a plain
     * {@code text/plain} blob the user actually intended as Markdown —
     * and the editor offers the same picker as the create form so the
     * user can correct it. Server-side: validates that the value is
     * non-blank and applies it verbatim; subsequent inline-text edits
     * use the new type for the inline-vs-storage threshold check.
     * Pass {@code null} to leave the current value unchanged.
     */
    private @Nullable String mimeType;

    /**
     * New path (move/rename) for this document. Optional. The server
     * normalizes (trims, strips leading/trailing slashes) and rejects
     * with HTTP 409 if a sibling document already lives under the
     * resolved path. Pass the current path (or {@code null}) to leave
     * it unchanged. The document's {@code name} is re-derived from
     * the trailing segment.
     */
    private @Nullable String newPath;

    /**
     * Toggle the per-document auto-summary opt-in. {@code null} leaves
     * the current value untouched.
     */
    private @Nullable Boolean autoSummary;

    /**
     * Manually flip the summary-dirty flag. UI mainly uses
     * {@code true} to force the next scheduler tick to re-summarise a
     * document that wasn't edited; {@code false} can clear a stuck
     * marker. {@code null} leaves the current value untouched.
     */
    private @Nullable Boolean summaryDirty;

    /**
     * Project-RAG inclusion override — sent as a string so the wire can
     * carry three states (the JSON-Boolean alternative cannot say
     * "explicitly null" with {@code JsonInclude.NON_NULL}). Accepts:
     * <ul>
     *   <li>{@code "auto"} — clear the override, document falls back to
     *       the path/mime-based default (include if under
     *       {@code documents/} and mime is textual).</li>
     *   <li>{@code "on"} — always include the document in the project RAG.</li>
     *   <li>{@code "off"} — never include the document.</li>
     *   <li>{@code null} (field absent) — leave the current setting untouched.</li>
     * </ul>
     * See {@code specification/rag.md}.
     */
    private @Nullable String ragEnabled;
}
