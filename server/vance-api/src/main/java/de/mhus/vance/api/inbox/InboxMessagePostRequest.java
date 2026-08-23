package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/messages} — one contribution
 * to a thread's clarification.
 *
 * <p>No author field: the writer is the authenticated caller. Accepting one
 * would let a client post under someone else's name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxMessagePostRequest {

    /**
     * Markdown, capped at {@value #MAX_BODY_CHARS} characters.
     *
     * <p>The cap is the other half of {@code MaximegalonService.MAX_MESSAGES}.
     * That bound is justified by Mongo's 16 MB document limit — the discussion
     * is embedded — but a count without a size does not enforce it: 500
     * unbounded bodies reach 16 MB easily, and a document that has burst it can
     * be neither read nor repaired through the API. 500 × 16 KB stays an order
     * of magnitude clear of the limit.
     */
    @NotBlank
    @Size(max = MAX_BODY_CHARS)
    private String body;

    /** Upper bound on one contribution. See {@link #getBody()}. */
    public static final int MAX_BODY_CHARS = 16_384;

    /**
     * The message being replied to, or {@code null} for the root level. A
     * reply to a reply is refused — depth is capped at one.
     */
    @Size(max = 64)
    private @Nullable String parentId;
}
