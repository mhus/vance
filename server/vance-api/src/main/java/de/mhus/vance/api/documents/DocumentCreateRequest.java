package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/documents?projectId=…} — creates a new
 * inline-text document. The path is the virtual address inside the project,
 * e.g. {@code "notes/thesis/ch1.md"}; it must be unique per project.
 *
 * <p>v1 supports text-only inline creation (Markdown, plain text). For
 * binary uploads or content above the inline threshold we'll add a
 * separate multipart endpoint — that is iteration B in the
 * "document import" plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("documents")
public class DocumentCreateRequest {

    @NotBlank
    private String path;

    private @Nullable String title;

    private @Nullable List<String> tags;

    /** {@code text/markdown}, {@code text/plain}, … defaults to {@code text/markdown} when null. */
    private @Nullable String mimeType;

    @NotBlank
    private String inlineText;
}
