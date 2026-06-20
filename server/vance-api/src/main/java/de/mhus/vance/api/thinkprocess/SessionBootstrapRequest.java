package de.mhus.vance.api.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Compound command: session (create or resume) plus zero or more
 * think-processes, atomically bootstrapped in a single round-trip.
 *
 * <p>{@code sessionId} decides create vs. resume:
 * <ul>
 *   <li>{@code null} — create a new session on {@code projectId} (required).
 *   <li>non-{@code null} — resume the referenced session;
 *       {@code projectId} is ignored.
 * </ul>
 *
 * <p>{@link #processes} are created in list order. If a process with the
 * same {@code name} already exists in the session (e.g. after resume), it is
 * skipped — bootstrap is idempotent.
 *
 * <p>{@link #initialMessage}, if set, is steered to the <b>first</b> process
 * in the list after all creates. Useful for "start session + ask first
 * question" in one call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("thinkprocess")
public class SessionBootstrapRequest {

    /** Required when creating a new session; ignored on resume. */
    private @Nullable String projectId;

    /** If set, resume this session instead of creating a new one. */
    private @Nullable String sessionId;

    @Builder.Default
    private List<ProcessSpec> processes = new ArrayList<>();

    /**
     * Optional override for the session-chat recipe. When set on a
     * {@code create} bootstrap, the session-chat process is spawned
     * from this recipe (cascade-resolved) instead of the tenant
     * default ({@code session.defaultChatEngine}) — the engine is
     * derived from the recipe. Ignored when resuming a session: the
     * existing chat-process is reused as-is.
     */
    private @Nullable String chatRecipe;

    /** Optional chat message steered to the first process after creation. */
    private @Nullable String initialMessage;
}
