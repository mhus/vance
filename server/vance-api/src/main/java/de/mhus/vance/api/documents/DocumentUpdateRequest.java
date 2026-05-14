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
 * the entire content. Only inline-stored documents accept {@link #inlineText}
 * updates in v1.
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

    private @Nullable String inlineText;

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
}
