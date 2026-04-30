package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full document representation returned by
 * {@code GET /brain/{tenant}/documents/{id}}.
 *
 * <p>{@link #inlineText} carries the content for inline-stored documents;
 * documents backed by storage have {@code inlineText == null} and an
 * {@code inline = false} flag. The v1 UI does not download storage-backed
 * content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentDto {

    private String id;

    private String projectId;

    private String path;

    private String name;

    private @Nullable String title;

    private @Nullable String mimeType;

    private long size;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private @Nullable Long createdAtMs;

    private @Nullable String createdBy;

    private boolean inline;

    /** Content for inline-stored documents. {@code null} when {@link #inline} is {@code false}. */
    private @Nullable String inlineText;

    /**
     * {@code kind:} value parsed from markdown front matter — e.g. {@code "list"},
     * {@code "tree"}, {@code "mindmap"}. {@code null} when the document is not
     * markdown or carries no front matter.
     */
    private @Nullable String kind;

    /**
     * Every parsed front-matter line, in source order. Empty when the document
     * has no front matter. Keys are normalised: lower-cased, with dots replaced
     * by underscores so MongoDB does not interpret them as path separators.
     */
    @Builder.Default
    private Map<String, String> headers = new LinkedHashMap<>();
}
