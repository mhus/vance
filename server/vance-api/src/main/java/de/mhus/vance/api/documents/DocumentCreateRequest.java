package de.mhus.vance.api.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /** Empty inline content is allowed — users may create a placeholder
     *  document and fill it later in the editor. Null is still rejected
     *  (forces the client to send the field explicitly). */
    @NotNull
    private String inlineText;

    /**
     * Per-document auto-summary opt-in. {@code null} (field absent) keeps
     * the server-side default which enables summarisation for eligible
     * text mime-types. Pass {@code false} when the document is an
     * auto-generated artefact (chat export, snapshot) whose content the
     * user already has summarised elsewhere — avoids re-spending LLM
     * tokens on duplicate material.
     */
    private @Nullable Boolean autoSummary;

    /**
     * Project-RAG inclusion override at creation time — same semantics as
     * {@link DocumentUpdateRequest#getRagEnabled()}. Sent as a string so the
     * wire can carry three states. Accepts:
     * <ul>
     *   <li>{@code "auto"} or {@code null} — fall back to the path/mime-based
     *       default (include if under {@code documents/} and mime is textual).</li>
     *   <li>{@code "on"} — always include the document in the project RAG.</li>
     *   <li>{@code "off"} — never include; also skips the initial dirty-flag
     *       so the RAG scheduler does not enqueue an embed run.</li>
     * </ul>
     * See {@code specification/rag.md}.
     */
    private @Nullable String ragEnabled;
}
