package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A single session-search result. The {@link #session} is the matched
 * session's summary; the optional {@link #snippet} carries a short
 * excerpt around the match position when the hit came from chat
 * content (Stufe 2).
 *
 * <p>{@link #matchedIn} tells the UI which surface the hit came from
 * so it can render metadata-matches and chat-matches differently
 * (e.g. metadata hits grouped at top, chat hits with snippet).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionSearchHitDto {

    private SessionSummaryRichDto session;

    /** Whether the hit came from the session's metadata or its chat content. */
    private SessionSearchScope matchedIn;

    /**
     * Snippet of the matching chat message — typically ~200 chars
     * centred on the match position. {@code null} for metadata hits.
     */
    private @Nullable String snippet;

    /** Role of the matched message (USER / ASSISTANT / SYSTEM). {@code null} for metadata hits. */
    private @Nullable String matchedRole;

    /** Mongo id of the matched chat message. {@code null} for metadata hits. */
    private @Nullable String matchedMessageId;

    /** Creation timestamp of the matched chat message. {@code null} for metadata hits. */
    private @Nullable Instant matchedAt;
}
