package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One atomic statement extracted from an {@link EvidenceSource}
 * during the CLASSIFYING phase — short enough that its
 * {@link #getClassification()} is unambiguous. Subgoals cite claims
 * (not raw sources), so the granularity matters: one paragraph that
 * mixes a fact and an opinion gets split into two claims.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Claim {

    /** Stable id, referenced from {@link Subgoal#getEvidenceRefs()}.
     *  Conventional shape: {@code "cl1"}, {@code "cl2"}, … */
    private String id = "";

    /** Foreign key to {@link EvidenceSource#getId()} — the source
     *  this claim was extracted from. Validator rejects dangling
     *  refs. */
    private String sourceId = "";

    /** The atomic statement, paraphrased for clarity but
     *  semantically equivalent to a span in the source's
     *  {@link EvidenceSource#getContent()}. */
    private String text = "";

    @Builder.Default
    private ClassificationKind classification = ClassificationKind.OPINION;

    /** Optional verbatim quote from the source supporting the
     *  paraphrase — useful for human review. {@code null} when
     *  the claim is a paraphrase only. */
    private @Nullable String quote;

    /** Foreign key into {@link ArchitectState#getRationales()} —
     *  why this classification, especially when not
     *  {@link ClassificationKind#FACT}. Helps a re-prompt
     *  challenge ("you marked this OPINION but it's a
     *  measurable claim — re-classify"). */
    private @Nullable String classificationRationaleId;
}
