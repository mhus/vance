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

    @NotBlank
    private String body;

    /**
     * The message being replied to, or {@code null} for the root level. A
     * reply to a reply is refused — depth is capped at one.
     */
    private @Nullable String parentId;
}
