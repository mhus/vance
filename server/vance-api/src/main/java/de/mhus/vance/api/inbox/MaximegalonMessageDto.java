package de.mhus.vance.api.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One contribution to a thread's clarification.
 *
 * <p>Flat, with {@code parentId} pointing at the message being replied to —
 * {@code null} means the root level, which is a reply to the thread's own
 * question. <b>The client assembles the tree</b>; a thread holds tens of
 * messages, not thousands, so nesting them on the wire would buy nothing and
 * cost the single update path the flat array gives the server.
 *
 * <p>{@code readBy} travels so the client can draw the "new from here" line and
 * decide what to mark read. Depth is capped at one level today (policy, not
 * schema — see {@code MaximegalonMessage}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("inbox")
public class MaximegalonMessageDto {

    private String id;
    private String authorUserId;
    private String body;
    private @Nullable Instant createdAt;

    /** The message replied to, or {@code null} for the root level. */
    private @Nullable String parentId;

    @Builder.Default
    private List<String> readBy = new ArrayList<>();

    @Builder.Default
    private List<MaximegalonReactionDto> reactions = new ArrayList<>();
}
