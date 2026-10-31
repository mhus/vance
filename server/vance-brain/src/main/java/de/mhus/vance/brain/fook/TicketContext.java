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

    @Nullable
    String projectId;

    @Nullable
    String sessionId;

    @Nullable
    String processId;

    @Nullable
    String recipe;

    @Nullable
    String engine;

    /**
     * Whether Fook may read the reporter's session (analysis report,
     * session reference on the ticket). Granted per report: explicitly
     * by the user in the report dialog, implicitly for engine and
     * service-account reports, which originate inside the session
     * itself. When {@code false}, {@link #sessionId} is never set and
     * the session-analysis step is hard-skipped.
     */
    @Builder.Default
    boolean sessionAccess = false;
}
