package de.mhus.vance.shared.chat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Query parameters for {@link ChatMessageService#search(ChatMessageSearchQuery,
 *                                                       java.util.Set)}.
 *
 * <p>{@code tenantId} and {@code thinkProcessId} are required and identify
 * the calling process. The {@code processIds} parameter on the service
 * call expands the visible process set when the caller chose a wider
 * scope ({@code SESSION}, {@code CHILDREN}); when the set is empty or
 * contains only {@code thinkProcessId}, the search is process-local —
 * the same as v1 behaviour.
 *
 * <p>{@code tags} are matched with AND semantics (all listed tags must
 * be present). {@code text} triggers a Mongo text-index lookup against
 * {@link ChatMessageDocument#getContent()}. {@code since} is inclusive.
 *
 * <p>{@code limit} is clamped to {@code [1, 50]} on construction —
 * callers that pass {@code 0} or negative values get {@code 1}; anything
 * above {@code 50} is silently capped. The cap protects against runaway
 * recalls from the LLM.
 */
public record ChatMessageSearchQuery(
        String tenantId,
        String thinkProcessId,
        Set<String> tags,
        @Nullable String text,
        @Nullable Instant since,
        int limit) {

    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 10;

    public ChatMessageSearchQuery {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (thinkProcessId == null || thinkProcessId.isBlank()) {
            throw new IllegalArgumentException("thinkProcessId is required");
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (limit < 1) {
            limit = 1;
        } else if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }

    /** Builder-style convenience for the common case of "all defaults". */
    public static ChatMessageSearchQuery of(String tenantId, String thinkProcessId) {
        return new ChatMessageSearchQuery(
                tenantId, thinkProcessId, new LinkedHashSet<>(), null, null, DEFAULT_LIMIT);
    }
}
