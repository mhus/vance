package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.CrossItemRelation;
import de.mhus.vance.shared.prak.CrossItemRelationType;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.LongTermMemoryDecision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Deterministic post-processor that runs over an LLM analyzer's raw
 * output and applies the §4c sanity checks: evidence validation,
 * confidence floor, duplicate merge, hard-cap downgrade, cross-item
 * supersede resolution, coverage telemetry.
 *
 * <p>Pure Java; no LLM call, no Mongo access. Stateless except for
 * configuration via {@link PrakProperties}. The intent is
 * that each trigger pipeline (hot-path, compaction-side-channel,
 * autodream, background-consistency) routes its analyzer output
 * through {@link #sanitize(EvaluationOutput, SanitizeContext)} before
 * handing it to consumers.
 *
 * <p>Operation order matters and is fixed:
 * <ol>
 *   <li>Cross-item supersede resolution (drops items the analyzer
 *       itself marked as superseded by a later item in the same batch).</li>
 *   <li>Importance-0 → {@link LongTermMemoryAction#SKIP} enforcement
 *       (belt-and-suspenders against analyzer drift).</li>
 *   <li>Evidence validation against existing turn ids — full halluzination
 *       drops the item, partial halluzination penalises confidence.</li>
 *   <li>Confidence floor — drops items below the configured threshold.</li>
 *   <li>Pairwise dedup using label-overlap AND content similarity.</li>
 *   <li>Hard-cap — if remaining count exceeds the cap derived from the
 *       expected range, downgrade every {@code PROMOTE} / {@code REFRESH}
 *       to {@code INBOX_OFFER}. No items are dropped.</li>
 *   <li>Coverage check (read-only, telemetry).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakSanitizer {

    private final PrakProperties props;

    public SanitizeResult sanitize(EvaluationOutput raw, SanitizeContext ctx) {
        int rawCount = raw.items().size();

        // 1. Cross-item supersede — drop items pointed at by a SUPERSEDES_WITHIN_BATCH relation.
        Set<String> supersededIds = collectSupersededIds(raw.crossItemRelations());
        List<ExtractedItem> step1 = new ArrayList<>();
        for (ExtractedItem item : raw.items()) {
            if (!supersededIds.contains(item.id())) {
                step1.add(item);
            }
        }
        int droppedBySupersede = rawCount - step1.size();

        // 2. Importance-0 must SKIP.
        List<ExtractedItem> step2 = new ArrayList<>(step1.size());
        for (ExtractedItem item : step1) {
            if (item.importance() == ExtractedItem.IMPORTANCE_SKIP
                    && item.longTermMemory().action() != LongTermMemoryAction.SKIP) {
                step2.add(item.withLongTermMemory(
                        LongTermMemoryDecision.skip(
                                "Importance 0 — forced to SKIP by sanitizer")));
            } else {
                step2.add(item);
            }
        }

        // 3. Evidence validation.
        List<ExtractedItem> step3 = new ArrayList<>();
        int droppedNoEvidence = 0;
        int confidencePenalised = 0;
        boolean skipEvidenceCheck = ctx.existingTurnIds().isEmpty();
        for (ExtractedItem item : step2) {
            if (skipEvidenceCheck) {
                step3.add(item);
                continue;
            }
            List<Evidence> valid = new ArrayList<>();
            for (Evidence e : item.evidence()) {
                if (ctx.existingTurnIds().contains(e.turnId())) {
                    valid.add(e);
                }
            }
            if (valid.isEmpty() && !item.evidence().isEmpty()) {
                droppedNoEvidence++;
                continue;
            }
            if (valid.size() < item.evidence().size()) {
                confidencePenalised++;
                step3.add(item
                        .withEvidence(valid)
                        .withConfidence(item.confidence()
                                * props.getPartialEvidenceConfidencePenalty()));
            } else {
                step3.add(item);
            }
        }

        // 4. Confidence floor.
        List<ExtractedItem> step4 = new ArrayList<>();
        int droppedLowConfidence = 0;
        for (ExtractedItem item : step3) {
            if (item.confidence() >= props.getConfidenceFloor()) {
                step4.add(item);
            } else {
                droppedLowConfidence++;
            }
        }

        // 5. Dedup.
        DedupResult dedup = dedupPairwise(step4);

        // 6. Hard-cap downgrade.
        int hardCap = computeHardCap(ctx.expectedRange());
        boolean hardCapTriggered = dedup.items().size() > hardCap;
        List<ExtractedItem> step6;
        if (hardCapTriggered) {
            log.warn("Item-flood: {} items after dedup for range max {} → hard cap {} — downgrading PROMOTE/REFRESH to INBOX_OFFER",
                    dedup.items().size(), ctx.expectedRange().max(), hardCap);
            step6 = downgradePromoteToInboxOffer(dedup.items(),
                    "Hard cap exceeded — auto-downgrade by sanitizer");
        } else {
            step6 = dedup.items();
        }

        // 7. Coverage.
        double coverage = computeCoverage(step6, ctx.substantialMessageCount());
        boolean lowCoverage =
                ctx.substantialMessageCount() >= props.getCoverageMinWindowSize()
                && coverage < props.getCoverageLowThreshold();

        // Resolved cross-item relations stop being meaningful after step 1.
        EvaluationOutput cleaned = new EvaluationOutput(
                raw.windowSpan(),
                List.copyOf(step6),
                List.of());

        SanitizeMetrics metrics = new SanitizeMetrics(
                rawCount,
                step6.size(),
                droppedNoEvidence,
                droppedLowConfidence,
                droppedBySupersede,
                dedup.duplicatesMerged(),
                confidencePenalised,
                hardCapTriggered,
                coverage,
                lowCoverage);

        return new SanitizeResult(cleaned, metrics);
    }

    private static Set<String> collectSupersededIds(List<CrossItemRelation> relations) {
        Set<String> superseded = new HashSet<>();
        for (CrossItemRelation r : relations) {
            if (r.relation() == CrossItemRelationType.SUPERSEDES_WITHIN_BATCH) {
                superseded.add(r.fromItemId());
            }
        }
        return superseded;
    }

    private int computeHardCap(de.mhus.vance.shared.prak.ItemCountExpectation expected) {
        int derived = (int) Math.ceil(expected.max() * props.getHardCapMultiplier());
        return Math.max(props.getHardCapAbsoluteFloor(), derived);
    }

    private static List<ExtractedItem> downgradePromoteToInboxOffer(
            List<ExtractedItem> items, String rationale) {
        List<ExtractedItem> out = new ArrayList<>(items.size());
        for (ExtractedItem item : items) {
            LongTermMemoryAction action = item.longTermMemory().action();
            if (action == LongTermMemoryAction.PROMOTE
                    || action == LongTermMemoryAction.REFRESH) {
                out.add(item.withLongTermMemory(
                        LongTermMemoryDecision.inboxOffer(rationale)));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    private static double computeCoverage(List<ExtractedItem> items, int substantialMessageCount) {
        if (substantialMessageCount <= 0) {
            return 0.0;
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (ExtractedItem item : items) {
            for (Evidence e : item.evidence()) {
                referenced.add(e.turnId());
            }
        }
        return (double) referenced.size() / substantialMessageCount;
    }

    // ---- Dedup ----

    private record DedupResult(List<ExtractedItem> items, int duplicatesMerged) {
    }

    private DedupResult dedupPairwise(List<ExtractedItem> items) {
        if (items.size() < 2) {
            return new DedupResult(List.copyOf(items), 0);
        }
        // Sort by confidence descending — the high-confidence item wins
        // when two are deemed duplicates. Tiebreak on item id for stability.
        List<ExtractedItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparingDouble(ExtractedItem::confidence).reversed()
                .thenComparing(ExtractedItem::id));

        List<ExtractedItem> kept = new ArrayList<>();
        int merged = 0;
        for (ExtractedItem candidate : sorted) {
            boolean duplicate = false;
            for (ExtractedItem keeper : kept) {
                if (isDuplicate(candidate, keeper)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                merged++;
            } else {
                kept.add(candidate);
            }
        }
        return new DedupResult(kept, merged);
    }

    private boolean isDuplicate(ExtractedItem a, ExtractedItem b) {
        double labelOverlap = labelOverlap(a.labels(), b.labels());
        if (labelOverlap < props.getDedupMinLabelOverlap()) {
            return false;
        }
        double contentSim = contentSimilarity(a.content(), b.content());
        return contentSim >= props.getDedupMinContentSimilarity();
    }

    static double labelOverlap(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        int intersection = 0;
        for (String s : setA) {
            if (setB.contains(s)) {
                intersection++;
            }
        }
        return (double) intersection / Math.max(setA.size(), setB.size());
    }

    static double contentSimilarity(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return (double) intersection.size() / union.size();
    }

    static Set<String> tokenize(String s) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : s.toLowerCase().split("\\W+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }
}
