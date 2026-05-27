package de.mhus.vance.shared.memory.evaluation;

import org.jspecify.annotations.Nullable;

/**
 * Declaration that an {@link ExtractedItem} affects existing memory
 * entries (supersede / revoke / extend / refine).
 *
 * <p>Independent from the item's own {@link LongTermMemoryDecision}:
 * a single item can be both <em>promoted</em> (added to memory) and
 * <em>supersede</em> earlier entries; a pure revocation has
 * {@code skip} on its own decision and {@link AffectsAction#REVOKE}
 * here.
 */
public record AffectsExisting(
        AffectsAction action,
        TargetRef targetRef,
        @Nullable String rationale) {
}
