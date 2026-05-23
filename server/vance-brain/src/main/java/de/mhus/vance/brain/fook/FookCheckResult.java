package de.mhus.vance.brain.fook;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of one {@link FookChecker#handle} call. Side-effects (cooldown
 * set, health-doc updated) have already happened — this is what the
 * caller (typically {@code ToolDispatcher}) needs to know about the
 * decision for logging / future LLM-enrichment.
 */
public record FookCheckResult(
        String patternId,
        ToolHealthClassification classification,
        String signature,
        boolean cooldownAlreadyActive,
        boolean wroteHealth,
        @Nullable String note) {

    public static FookCheckResult unmatched() {
        return new FookCheckResult(
                "unmatched", ToolHealthClassification.UNCLEAR, "unclassified",
                false, false, null);
    }
}
