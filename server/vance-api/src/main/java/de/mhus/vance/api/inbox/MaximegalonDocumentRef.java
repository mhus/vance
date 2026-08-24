package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * The document a thread is <em>about</em> — its object.
 *
 * <p>This used to live inside {@code payload} as a plain map entry, written the
 * same way by four producers. It became a field the moment the inbox stopped
 * being only a filing place and became something you <b>query</b>: "which
 * threads are about this document" is a question across every
 * {@link MaximegalonType}, and {@code payload} is by contract
 * <em>type-specific</em>. A cross-type query hanging off a per-type map breaks
 * the day a fifth producer picks a different key — silently, by finding nothing.
 *
 * <p><b>Not</b> {@code de.mhus.vance.shared.document.DocumentRef}: that one is
 * an <em>authored</em> reference ({@code projectId}, {@code path}, {@code query})
 * resolved from a {@code vance:} URI, it carries no document id, and it lives in
 * a module {@code vance-api} must not depend on. Same word, different thing.
 *
 * <p>A resolved snapshot, not a live join: {@code title} and {@code mimeType}
 * are what the document was called when the thread was opened. The thread stays
 * readable after the document is renamed or deleted — the point of the thread is
 * the matter, and {@code documentId} remains the way to look up what is there
 * now.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class MaximegalonDocumentRef {

    /** Mongo id of the document. The queryable part. */
    @NotBlank
    private String documentId;

    /** Project the document lives in — needed to build a Cortex deep-link. */
    @NotBlank
    private String projectId;

    /** Path at the time the thread was opened. */
    @NotBlank
    private String path;

    /** Display title at the time the thread was opened, when it had one. */
    private @Nullable String title;

    private @Nullable String mimeType;
}
