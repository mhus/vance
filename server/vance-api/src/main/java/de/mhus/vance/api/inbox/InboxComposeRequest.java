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
 * A person writing a message into somebody's inbox — the compose path the
 * Web-UI offers on {@code /inbox}.
 *
 * <p><b>What this is next to its two siblings.</b> Pointing somebody at a
 * document is a Milliways share, and opening a discussion requires one — both
 * deliver a thread into an inbox, but both need an <em>object</em> to be about.
 * This request is the free case: title and body, nothing attached. A note to
 * oneself lands here too, because the share path refuses self-delivery.
 *
 * <p><b>Exactly one address.</b> {@code assignedToUserId} delivers to one
 * desk; {@code teamName} fans out to one thread per member of a team the
 * caller belongs to. Both set at once is refused — the two are different
 * shapes of send, not additive. Both unset means the caller themselves.
 *
 * <p><b>Never an ask.</b> The created threads carry
 * {@code requiresAction=false}, for the same reason
 * {@link InboxDiscussionOpenRequest} documents at length: an ask is what a
 * <em>process</em> waits on, and nothing waits on a message a human wrote by
 * hand. Making this an ask would put a permanently-open item on somebody's
 * badge with no process behind it to resolve. If a reply is wanted, it happens
 * in the thread's clarification — which is what it is for.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxComposeRequest {

    /**
     * Whose desk it lands on. {@code null} means the caller themselves — the
     * self-note the share path cannot serve.
     */
    private @Nullable String assignedToUserId;

    /**
     * The team to fan out to — one thread per member, sender excluded. The
     * caller must be a member; the same line the team-inbox view draws.
     */
    private @Nullable String teamName;

    @NotBlank
    @Size(max = 200)
    private String title;

    /** The message. Markdown. */
    @Size(max = 16_384)
    private @Nullable String body;
}
