package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /brain/{tenant}/inbox/{id}/read}.
 *
 * <p>Empty or absent {@code messageIds} means "the whole thread" — the common
 * case, and one write. Naming individual messages is for the deep-link
 * situation, where someone lands on message five without having seen three and
 * four; marking the lot would tick those off silently.
 *
 * <p><b>The client decides when to send this, the server decides what it
 * means.</b> Whether reading happens on open, on scroll or after a delay is
 * client policy; that it happened has to reach the server, or a second device
 * shows a badge that is already answered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class InboxReadRequest {
    private @Nullable List<String> messageIds;
}
