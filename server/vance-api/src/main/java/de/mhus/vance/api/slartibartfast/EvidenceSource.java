package de.mhus.vance.api.slartibartfast;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One piece of source material the planner consulted — typically
 * a manual document, but also direct user statements or the
 * description block of an adjacent recipe. Persisted verbatim so
 * the audit chain in {@link ArchitectState} can be reconstructed
 * and inspected post-hoc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceSource {

    /** Stable id, used as the foreign key from {@link Claim#getSourceId()}.
     *  Conventional shape: {@code "ev1"}, {@code "ev2"}, … */
    private String id = "";

    @Builder.Default
    private EvidenceType type = EvidenceType.MANUAL;

    /** Document path for {@link EvidenceType#MANUAL} sources
     *  (e.g. {@code "manuals/essay/STYLE.md"}). For
     *  {@link EvidenceType#RECIPE_DESCRIPTION}: the recipe name.
     *  For {@link EvidenceType#USER} / {@link EvidenceType#DEFAULT}:
     *  may be {@code null}. */
    private @Nullable String path;

    /** Full text the planner saw — verbatim copy, no summarisation.
     *  Validators may refuse claims whose quoted text isn't a
     *  substring of this content. */
    private String content = "";

    /** Foreign key into {@link ArchitectState#getRationales()} —
     *  why was this source consulted? Set by GATHERING; helps a
     *  reviewer distinguish "engine read this manual because the
     *  goal mentioned 'essay'" from "engine read this manual by
     *  default because nothing else fit". */
    private @Nullable String gatheringRationaleId;
}
