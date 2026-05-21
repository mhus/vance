package de.mhus.vance.api.magrathea;

/**
 * Categories used by a workflow's {@code catch:} block to route failure
 * outcomes to recovery states. See plan §5 (Error-Kinds).
 */
public enum MagratheaErrorKind {
    /** Tool/API/Shell infra broken (IOException, 5xx, …). */
    TECHNICAL_ERROR,
    /** Expected domain failure (script exit !=0, validation-fail). */
    BUSINESS_ERROR,
    /** LLM produced invalid output (Jeltz {@code schema_violation}). */
    AGENT_ERROR,
    /** Task-level timeout exceeded. */
    TIMEOUT,
    /** Tool not permitted for this workflow/caller (tool-cascade §7.1). */
    PERMISSION_ERROR,
    /** Gate explicitly rejected by user (alternative routing: {@code on: rejected}). */
    HUMAN_REJECTED,
    /** Workflow stopped via {@code MagratheaWorkflowService.cancel} or bounds-exhaustion. */
    CANCELLED
}
