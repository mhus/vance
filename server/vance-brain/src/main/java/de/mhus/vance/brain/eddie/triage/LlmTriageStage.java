package de.mhus.vance.brain.eddie.triage;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * Optional second-stage classifier — invoked by
 * {@link OutputTriageService} when the heuristic returns
 * {@link TriageDecision#REFORMULATE} on a non-trivial input. The stage
 * may upgrade the decision (e.g. {@code REFORMULATE → INBOX} for
 * something the LLM thinks belongs in the user's inbox) or the
 * criticality (e.g. {@code NORMAL → CRITICAL} on a plan-approval),
 * and produces a better {@code memorySummary} /
 * {@code spokenAnnouncement} than the heuristic's first-line clamp.
 *
 * <p>Implementations call out to {@code default:fast} on a per-tenant
 * provider — single small JSON-structured turn. The hard-override
 * clamp ({@code applyHardOverrides}) runs <i>after</i> this stage, so
 * a CRITICAL+REFORMULATE pair raised here is automatically clamped
 * to INBOX by the surrounding service.
 *
 * <p>v1 ships without a default bean — Eddie runs heuristic-only
 * unless an implementation is registered. Step 2 of Eddie's roadmap
 * will provide one.
 */
public interface LlmTriageStage {

    /**
     * Refines a heuristic verdict using a fast LLM call. May return
     * the original {@code heuristic} unchanged if the model agrees.
     * Throwing is allowed — callers fall back to the heuristic.
     *
     * @param input     the original triage input
     * @param heuristic the heuristic verdict (decision = REFORMULATE)
     * @param context   the Eddie ThinkProcess driving the call —
     *                  carries tenant / project for settings cascade
     */
    TriageResult refine(TriageInput input, TriageResult heuristic, ThinkProcessDocument context);
}
