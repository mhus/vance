package de.mhus.vance.toolpack;

import org.jspecify.annotations.Nullable;

/**
 * Scope handed to a tool on invocation. Tools should treat it as
 * read-only — it is built fresh per call by the dispatcher.
 *
 * <p>{@code projectId}, {@code sessionId} and {@code processId} may be
 * {@code null} for tools invoked outside a think-process (e.g. admin
 * flows). Tools that need them must validate.
 *
 * <p>{@code projectId} is the natural scope for project-affinitive
 * resources (workspace files, exec jobs, future memory indexes); a
 * tool typically prefers it over {@code sessionId}.
 *
 * <p>{@code workingProjectId} is the Eddie-only "spot" pointer — the
 * foreign project Eddie currently coordinates. Always {@code null} for
 * non-Eddie processes. Spot-bound tools (e.g. {@code STEER_PROJECT},
 * {@code project_chat_send}) read this; home-bound tools
 * ({@code doc_*}, {@code scratch_*}) keep using {@link #projectId}.
 *
 * <p>On the foot client side, where there is one user and one session
 * per process, the foot adapter passes a context whose
 * {@code tenantId} reflects the bound tenant and the rest can be
 * empty/null. Tool implementations must therefore treat the optional
 * fields defensively.
 */
public record ToolInvocationContext(
        String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        @Nullable String userId,
        @Nullable String workingProjectId) {

    /**
     * Backwards-compatible 5-arg constructor — workingProjectId
     * defaults to {@code null}. Used by call-sites that don't know
     * about Eddie's spot pointer (foot client, admin tools, legacy
     * dispatch paths).
     */
    public ToolInvocationContext(
            String tenantId,
            @Nullable String projectId,
            @Nullable String sessionId,
            @Nullable String processId,
            @Nullable String userId) {
        this(tenantId, projectId, sessionId, processId, userId, null);
    }

    /**
     * Tool's authoritative project for home-bound operations
     * ({@code doc_*}, {@code scratch_*}, {@code rag_*}, …). Returns the
     * context-bound {@link #projectId()} regardless of whatever the LLM
     * may have written into a tool param — the trust boundary is here,
     * not in the LLM-facing schema.
     *
     * <p>Throws {@link IllegalStateException} when the context carries
     * no projectId (process spawned outside a project scope) — that's a
     * programmer error, not a runtime condition the LLM can recover
     * from.
     */
    public String resolveLocalProjectId() {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException(
                    "Tool requires a projectId-bound context but none was provided");
        }
        return projectId;
    }

    /**
     * Spot-bound resolution for Eddie's cross-project tools. Returns
     * {@link #workingProjectId()}; throws when Eddie has not picked a
     * working project yet — the LLM must {@code SWITCH_PROJECT} first.
     *
     * <p>The error message is intentionally LLM-friendly: it surfaces
     * back into the tool-call error block and the engine's next prompt
     * cycle, so a hallucinated cross-project call self-corrects
     * instead of operating on the wrong project.
     */
    public String requireWorkingProjectId() {
        if (workingProjectId == null || workingProjectId.isBlank()) {
            throw new IllegalStateException(
                    "No working project selected — emit SWITCH_PROJECT before using this tool");
        }
        return workingProjectId;
    }
}
