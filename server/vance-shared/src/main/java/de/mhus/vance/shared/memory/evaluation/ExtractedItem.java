package de.mhus.vance.shared.memory.evaluation;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A single classified insight emitted by the LLM analyzer.
 *
 * <p>Carries everything needed by downstream consumers (span-strength
 * via evidence, memory-promotion via the long-term decision,
 * supersede/revoke via affects-existing). See {@code
 * planning/memory-evaluation-pipeline.md} §4 for the wire format.
 *
 * <p>Constraints (validated Java-side by {@code
 * MemoryEvaluationSanitizer}):
 * <ul>
 *   <li>{@code importance} is 0..5; 0 forces {@code longTermMemory.action = SKIP}.</li>
 *   <li>{@code confidence} is 0.0..1.0; values below the configured floor
 *       cause the item to be dropped.</li>
 *   <li>{@code evidence} must reference real chat turns; halluzinated
 *       turn ids cause confidence penalty or item drop.</li>
 * </ul>
 *
 * <p>{@code why} mirrors Claude Code's {@code Why:} convention for
 * feedback memories — informational rationale, not consumed as data.
 */
public record ExtractedItem(
        String id,
        ItemType type,
        int importance,
        String content,
        Scope scope,
        double confidence,
        List<String> labels,
        List<Evidence> evidence,
        @Nullable String why,
        Decay decay,
        LongTermMemoryDecision longTermMemory,
        List<AffectsExisting> affectsExisting) {

    /** Importance value that forces SKIP and signals the item is irrelevant. */
    public static final int IMPORTANCE_SKIP = 0;

    /** Default importance when the analyzer is uncertain. */
    public static final int IMPORTANCE_DEFAULT = 3;

    /** Items at or above this threshold drive the span containing their evidence to STRONG. */
    public static final int IMPORTANCE_HIGH = 4;

    /** Critical importance. Combined with explicit pin policy this can map to PINNED span strength. */
    public static final int IMPORTANCE_CRITICAL = 5;

    /** Convenience accessor — returns a defensive copy of evidence with overridden confidence. */
    public ExtractedItem withConfidence(double newConfidence) {
        return new ExtractedItem(
                id, type, importance, content, scope, newConfidence,
                labels, evidence, why, decay, longTermMemory, affectsExisting);
    }

    /** Returns a copy with a reduced evidence list (used by Java-side validation). */
    public ExtractedItem withEvidence(List<Evidence> newEvidence) {
        return new ExtractedItem(
                id, type, importance, content, scope, confidence,
                labels, List.copyOf(newEvidence), why, decay,
                longTermMemory, affectsExisting);
    }

    /** Returns a copy with a different long-term-memory decision. */
    public ExtractedItem withLongTermMemory(LongTermMemoryDecision newDecision) {
        return new ExtractedItem(
                id, type, importance, content, scope, confidence,
                labels, evidence, why, decay, newDecision, affectsExisting);
    }
}
