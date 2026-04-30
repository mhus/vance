package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Lightweight document representation used in {@link DocumentListResponse}.
 * Excludes the inline content — fetch {@code GET /brain/{tenant}/documents/{id}}
 * to load that.
 *
 * <p>{@link #inline} indicates whether the document is small enough to be
 * stored on the record itself (and therefore editable through
 * {@link DocumentUpdateRequest#getInlineText()}). Documents backed by the
 * {@code StorageService} have {@code inline = false} and need a separate
 * upload flow that the v1 Web-UI does not expose.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentSummary {

    private String id;

    private String projectId;

    /** Virtual path inside the project, e.g. {@code "notes/thesis/ch1.md"}. */
    private String path;

    /** File-name portion of {@link #path}. */
    private String name;

    private @Nullable String title;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable Long createdAtMs;

    private @Nullable String createdBy;

    /** {@code true} when the content lives inline on the record (editable in v1 UI). */
    private boolean inline;

    /**
     * {@code kind:} value parsed from markdown front matter, mirrored on
     * the document record so the list view can render a badge / filter
     * by kind without loading the body. {@code null} for non-markdown
     * documents and markdown documents without a front matter.
     */
    private @Nullable String kind;
}
