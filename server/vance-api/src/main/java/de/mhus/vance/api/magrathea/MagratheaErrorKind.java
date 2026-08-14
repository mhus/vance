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
    CANCELLED,
    /**
     * A state needs a {@link RunCapability} this run was not started with —
     * typically a question for a person in a run that belongs to nobody.
     *
     * <p>Unlike the other kinds this one is normally raised <em>before</em>
     * the run begins: the start refuses a plan whose states cannot all be
     * reached. Declaring {@code catch: { capability_missing: … }} on such a
     * state opts out of that refusal and turns it into an ordinary outcome —
     * whoever handles the failure is allowed to have it.
     */
    CAPABILITY_MISSING
}
