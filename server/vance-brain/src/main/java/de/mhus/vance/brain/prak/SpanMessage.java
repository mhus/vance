package de.mhus.vance.brain.prak;

import de.mhus.vance.api.chat.ChatRole;
import org.jspecify.annotations.Nullable;

/**
 * Trimmed view of a single chat turn for cheap-path classification.
 *
 * <p>Callers project their {@code ChatMessageDocument} (or whatever
 * the persistence source is) into this record before feeding it to
 * {@link CheapPathFilter}, so the filter remains decoupled from the
 * Mongo layer and can be unit-tested with synthetic spans.
 *
 * <p>{@link #messageId()} flows through as the candidate {@code turnId}
 * for evidence pointers later in the pipeline.
 */
public record SpanMessage(
        @Nullable String messageId,
        ChatRole role,
        String content) {
}
