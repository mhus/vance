package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.prak.AffectsExisting;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.Evidence;
import de.mhus.vance.shared.prak.ExtractedItem;
import de.mhus.vance.shared.prak.ItemType;
import de.mhus.vance.shared.prak.LongTermMemoryAction;
import de.mhus.vance.shared.prak.Scope;
import de.mhus.vance.shared.prak.ScopeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Final consumer of a sanitised {@link EvaluationOutput}. Implements
 * §6.2 / §12.8 of {@code planning/memory-evaluation-pipeline.md}:
 *
 * <ul>
 *   <li>{@code promote} → new {@link MemoryDocument} with
 *       {@link MemoryKind#INSIGHT} and the item's scope.</li>
 *   <li>{@code inboxOffer} → telemetry + log (Inbox-Subsystem wiring
 *       arrives in a later phase).</li>
 *   <li>{@code refresh} → telemetry + log (needs label-lookup judge,
 *       §12.9).</li>
 *   <li>{@code skip} → no-op + counter.</li>
 *   <li>{@code affectsExisting[*]} → telemetry + log (deferred).</li>
 * </ul>
 *
 * <p>Default-action resolution (§6.2): {@code importance == 0} forces
 * {@code SKIP}; {@code INSTRUCTION} items can never PROMOTE silently
 * — the closest the analyzer can request is {@code INBOX_OFFER}. The
 * service downgrades a stray {@code INSTRUCTION + PROMOTE} on the
 * spot, logs the override, and counts it in {@link
 * PromotionResult#inboxOffered()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakPromotionService {

    /** Metadata key on the persisted memory pointing at the analyzer item id. */
    public static final String META_PRAK_ITEM_ID = "prakItemId";

    /** Metadata key for the run-correlation token. */
    public static final String META_PRAK_RUN_ID = "prakRunId";

    /** Metadata key for the analyzer's item type (fact/instruction/preference). */
    public static final String META_ITEM_TYPE = "prakType";

    /** Metadata key for the analyzer's importance score (0..5). */
    public static final String META_IMPORTANCE = "prakImportance";

    /** Metadata key for the analyzer's confidence (0..1). */
    public static final String META_CONFIDENCE = "prakConfidence";

    /** Metadata key for the analyzer's labels — stored as {@code List<String>}. */
    public static final String META_LABELS = "prakLabels";

    /** Metadata key for the analyzer's decay hint. */
    public static final String META_DECAY = "prakDecay";

    /** Metadata key for the analyzer's "why" rationale. */
    public static final String META_WHY = "prakWhy";

    /** Metadata flag: {@code true} for memories produced by Prak. */
    public static final String META_GENERATED_BY = "generatedBy";

    /** Value for {@link #META_GENERATED_BY} on Prak-produced memories. */
    public static final String GENERATED_BY_PRAK = "prak";

    /** Max length of an auto-generated memory title. */
    static final int TITLE_MAX_LEN = 80;

    private final MemoryService memoryService;
    private final MetricService metricService;

    /**
     * Apply the items in {@code evaluation} according to their
     * effective {@link LongTermMemoryAction} (analyzer choice +
     * default-action overrides). Idempotent on an empty output.
     */
    public PromotionResult promote(EvaluationOutput evaluation, PromotionContext ctx) {
        if (evaluation == null || evaluation.items().isEmpty()) {
            return PromotionResult.empty();
        }
        int promoted = 0;
        int inboxOffered = 0;
        int skipped = 0;
        int refreshed = 0;
        int affectsResolved = 0;
        int affectsDeferred = 0;
        List<String> persistedIds = new ArrayList<>();

        for (ExtractedItem item : evaluation.items()) {
            LongTermMemoryAction action = resolveAction(item);
            switch (action) {
                case PROMOTE -> {
                    MemoryDocument saved = persistInsight(item, ctx);
                    if (saved != null && saved.getId() != null) {
                        persistedIds.add(saved.getId());
                        promoted++;
                    } else {
                        // Defensive — save() returns the persisted doc
                        // with the assigned id; null here means a Mongo
                        // glitch. Treat as skipped for the counters.
                        skipped++;
                    }
                }
                case INBOX_OFFER -> {
                    inboxOffered++;
                    log.info("Prak inboxOffer (deferred) run='{}' item='{}' type={} content='{}'",
                            ctx.runId(), item.id(), item.type(), brief(item.content()));
                }
                case REFRESH -> {
                    refreshed++;
                    log.info("Prak refresh (deferred — needs label-lookup) run='{}' item='{}'",
                            ctx.runId(), item.id());
                }
                case SKIP -> skipped++;
            }

            // affectsExisting deferred to the label-lookup phase.
            affectsDeferred += item.affectsExisting().size();
            for (AffectsExisting a : item.affectsExisting()) {
                log.debug("Prak affectsExisting (deferred) run='{}' item='{}' action={} target={}",
                        ctx.runId(), item.id(), a.action(), a.targetRef().kind());
            }
        }

        // Emit Micrometer counters partitioned by outcome.
        metricService.counter("vance.prak.promotion",
                "outcome", "promote").increment(promoted);
        metricService.counter("vance.prak.promotion",
                "outcome", "inboxOffer").increment(inboxOffered);
        metricService.counter("vance.prak.promotion",
                "outcome", "skip").increment(skipped);
        metricService.counter("vance.prak.promotion",
                "outcome", "refresh").increment(refreshed);

        return new PromotionResult(
                promoted, inboxOffered, skipped, refreshed,
                affectsResolved, affectsDeferred, List.copyOf(persistedIds));
    }

    /**
     * Default-action resolver: {@code importance == 0} → SKIP;
     * {@code INSTRUCTION + PROMOTE} → INBOX_OFFER (safety override);
     * otherwise the analyzer's choice stands.
     *
     * <p>Package-private + static for testing.
     */
    static LongTermMemoryAction resolveAction(ExtractedItem item) {
        if (item.importance() == ExtractedItem.IMPORTANCE_SKIP) {
            return LongTermMemoryAction.SKIP;
        }
        LongTermMemoryAction proposed = item.longTermMemory().action();
        if (item.type() == ItemType.INSTRUCTION
                && proposed == LongTermMemoryAction.PROMOTE) {
            // Never silently promote instructions. The user must confirm.
            return LongTermMemoryAction.INBOX_OFFER;
        }
        return proposed;
    }

    private @Nullable MemoryDocument persistInsight(ExtractedItem item, PromotionContext ctx) {
        try {
            MemoryDocument fresh = MemoryDocument.builder()
                    .tenantId(ctx.tenantId())
                    .projectId(resolveProjectId(item.scope(), ctx))
                    .sessionId(resolveSessionId(item.scope(), ctx))
                    .thinkProcessId(resolveProcessId(item.scope(), ctx))
                    .kind(MemoryKind.INSIGHT)
                    .title(makeTitle(item))
                    .content(item.content())
                    .sourceRefs(extractSourceRefs(item))
                    .metadata(buildMetadata(item, ctx))
                    .build();
            return memoryService.save(fresh);
        } catch (RuntimeException e) {
            log.warn("Prak.persistInsight failed for run='{}' item='{}': {}",
                    ctx.runId(), item.id(), e.toString());
            return null;
        }
    }

    private static String resolveProjectId(Scope scope, PromotionContext ctx) {
        if (scope == null || scope.kind() == ScopeKind.GLOBAL) {
            return "";
        }
        if (scope.kind() == ScopeKind.PROJECT && scope.id() != null && !scope.id().isBlank()) {
            return scope.id();
        }
        // SESSION/TASK scopes inherit the project from the caller context.
        return ctx.projectId() == null ? "" : ctx.projectId();
    }

    private static @Nullable String resolveSessionId(Scope scope, PromotionContext ctx) {
        if (scope == null) return null;
        if (scope.kind() == ScopeKind.SESSION && scope.id() != null && !scope.id().isBlank()) {
            return scope.id();
        }
        if (scope.kind() == ScopeKind.TASK) {
            return ctx.sessionId();
        }
        // GLOBAL/PROJECT — no session pinning.
        return null;
    }

    private static @Nullable String resolveProcessId(Scope scope, PromotionContext ctx) {
        if (scope == null) return null;
        if (scope.kind() == ScopeKind.TASK && scope.id() != null && !scope.id().isBlank()) {
            return scope.id();
        }
        return null;
    }

    private static String makeTitle(ExtractedItem item) {
        String prefix = item.type().name().toLowerCase(Locale.ROOT);
        String content = item.content() == null ? "" : item.content().trim();
        if (content.isEmpty()) {
            return prefix;
        }
        // Reserve "<prefix>: " plus a possible "…" suffix.
        int prefixLen = prefix.length() + 2;
        int budget = TITLE_MAX_LEN - prefixLen;
        if (budget <= 0 || content.length() <= budget) {
            return prefix + ": " + content;
        }
        return prefix + ": " + content.substring(0, budget - 1).trim() + "…";
    }

    private static List<String> extractSourceRefs(ExtractedItem item) {
        List<String> refs = new ArrayList<>();
        for (Evidence e : item.evidence()) {
            if (!StringUtils.isBlank(e.turnId())) {
                refs.add(e.turnId());
            }
        }
        return refs;
    }

    private static Map<String, Object> buildMetadata(ExtractedItem item, PromotionContext ctx) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(META_GENERATED_BY, GENERATED_BY_PRAK);
        meta.put(META_PRAK_ITEM_ID, item.id());
        meta.put(META_PRAK_RUN_ID, ctx.runId());
        meta.put(META_ITEM_TYPE, item.type().name().toLowerCase(Locale.ROOT));
        meta.put(META_IMPORTANCE, item.importance());
        meta.put(META_CONFIDENCE, item.confidence());
        if (!item.labels().isEmpty()) {
            meta.put(META_LABELS, List.copyOf(item.labels()));
        }
        meta.put(META_DECAY, item.decay().name().toLowerCase(Locale.ROOT));
        if (!StringUtils.isBlank(item.why())) {
            meta.put(META_WHY, item.why());
        }
        return meta;
    }

    private static String brief(@Nullable String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.length() <= 60) return trimmed;
        return trimmed.substring(0, 59) + "…";
    }
}
