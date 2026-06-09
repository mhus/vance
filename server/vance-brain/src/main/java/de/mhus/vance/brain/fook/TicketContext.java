package de.mhus.vance.brain.fook;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Origin context attached to a ticket — where in Vance the report
 * came from. All fields nullable: a {@code user_direct} submission
 * from the index page has nothing to fill in, while an
 * engine-driven {@code support()} call from an Arthur process
 * carries the full chain.
 *
 * <p>Persisted as the top-level {@code context:} map in the YAML
 * document (not inside {@code $meta} — these aren't search keys).
 */
@Value
@Builder
public class TicketContext {

    @Nullable String projectId;
    @Nullable String sessionId;
    @Nullable String processId;
    @Nullable String recipe;
    @Nullable String engine;
}
