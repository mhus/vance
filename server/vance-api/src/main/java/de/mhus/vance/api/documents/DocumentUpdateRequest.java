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
}
