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
 * A person opening a discussion about a document.
 *
 * <p><b>Not a second share dialog.</b> Pointing somebody at a document already
 * exists ([Milliways](../../../../../../../../specification/public/milliways-system.md)
 * {@code inbox} handler), and it produces a thread in the same place. What that
 * cannot do is the two cases this request is for: a thread addressed to
 * <em>yourself</em> — a place to collect a thought about a document that
 * colleagues can be invited into, which the share path refuses — and one where
 * the point is a question rather than a delivery.
 *
 * <p><b>Never an ask.</b> The created thread carries
 * {@code requiresAction=false}: an ask is what a <em>process</em> waits on, and
 * nothing waits on a discussion a human opened. If the recipient is meant to
 * answer, they answer in the clarification, which is what it is for. Making this
 * an ask would put a permanently-open item on somebody's badge with no process
 * behind it to unblock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxDiscussionOpenRequest {

    /** The document the discussion is about. Becomes the thread's object. */
    @NotBlank
    private String documentId;

    /**
     * Whose desk it lands on. {@code null} means the caller themselves — the
     * case the share path cannot serve, and the reason this endpoint exists.
     */
    private @Nullable String assignedToUserId;

    @NotBlank
    @Size(max = 200)
    private String title;

    /** The question or the observation. Markdown. */
    @Size(max = 16_384)
    private @Nullable String body;
}
