package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.prak.audit.PrakRunRecord;
import de.mhus.vance.shared.prak.audit.PrakRunService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Runs the full Prak side-channel pipeline over a span of chat
 * messages — analyzer → sanitizer → strength-deriver → promotion →
 * audit-row. Decoupled from the trigger that fed the span: the
 * compaction-side-channel and the periodic-prak trigger both call
 * the same {@link #run} method with different {@code triggerLabel}s.
 *
 * <p>Bails early when:
 * <ul>
 *   <li>{@link PrakProperties#isSideChannelEnabled()} is false;</li>
 *   <li>{@code spanDocs} is null or empty;</li>
 *   <li>the cheap-path pre-filter judges the span too thin to be
 *       worth an analyzer call.</li>
 * </ul>
 *
 * <p>RuntimeExceptions during analysis / persistence are caught and
 * surfaced via the {@code vance.prak.sideChannel{outcome=error}}
 * counter + a warn log — the caller's main flow is never broken by
 * the side-channel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrakSideChannelRunner {

    private final PrakService prakService;
    private final CheapPathFilter cheapPathFilter;
    private final PrakSanitizer prakSanitizer;
    private final PrakProperties prakProperties;
    private final SpanStrengthDeriver spanStrengthDeriver;
    private final PrakPromotionService prakPromotionService;
    private final PrakRunService prakRunService;
    private final MetricService metricService;

    /**
     * Run the side-channel over {@code spanDocs}. Returns {@code true}
     * when the analyzer actually fired (regardless of items produced),
     * {@code false} when an early gate skipped the run. Failures are
     * caught — never throws.
     */
    public boolean run(
            ThinkProcessDocument process,
            @Nullable String projectId,
            List<ChatMessageDocument> spanDocs,
            String triggerLabel) {

        if (!prakProperties.isSideChannelEnabled()) {
            return false;
        }
        if (spanDocs == null || spanDocs.isEmpty()) {
            return false;
        }
        long startMs = System.currentTimeMillis();
        String runId = triggerLabelPrefix(triggerLabel) + "-"
                + process.getId() + "-" + Instant.now();
        try {
            List<SpanMessage> span = projectToSpan(spanDocs);
            SpanProfile profile = cheapPathFilter.profile(span);
            if (profile.isSkippable()) {
                log.debug("Side-channel skipped for process='{}' trigger='{}' reason='{}'",
                        process.getId(), triggerLabel, profile.skipReason());
                // Tag set must be consistent across all increments of the
                // same meter — Prometheus rejects mismatched key sets.
                // Always emit (outcome, reason); reason="n/a" for the
                // non-skip paths.
                metricService.counter("vance.prak.sideChannel",
                        "outcome", "skipped",
                        "reason", profile.skipReason() == null
                                ? "unknown" : profile.skipReason()).increment();
                return false;
            }

            EvaluationOutput raw = prakService.analyze(
                    process.getTenantId(),
                    projectId == null || projectId.isBlank() ? null : projectId,
                    process.getId(),
                    span,
                    triggerLabel,
                    profile.expectation());

            Set<String> existingTurnIds = new HashSet<>();
            for (ChatMessageDocument m : spanDocs) {
                if (m.getId() != null) {
                    existingTurnIds.add(m.getId());
                }
            }
            int substantialCount = profile.substantialUserTurnCount()
                    + profile.markerHits();
            SanitizeContext ctx = new SanitizeContext(
                    existingTurnIds, substantialCount, profile.expectation());

            SanitizeResult sanitized = prakSanitizer.sanitize(raw, ctx);
            log.info("Side-channel process='{}' trigger='{}' raw={} final={} dropped(noEv={}, lowConf={}, sup={}) merged={} hardCap={} coverage={}",
                    process.getId(), triggerLabel,
                    sanitized.metrics().rawItemCount(),
                    sanitized.metrics().finalItemCount(),
                    sanitized.metrics().droppedNoEvidence(),
                    sanitized.metrics().droppedLowConfidence(),
                    sanitized.metrics().droppedBySupersedeWithinBatch(),
                    sanitized.metrics().duplicatesMerged(),
                    sanitized.metrics().hardCapTriggered(),
                    String.format("%.2f", sanitized.metrics().evidenceCoverage()));

            metricService.counter("vance.prak.sideChannel",
                    "outcome", "success",
                    "reason", "n/a").increment();
            metricService.summary("vance.prak.items.final")
                    .record(sanitized.metrics().finalItemCount());

            StrengthDerivation derivation =
                    spanStrengthDeriver.derive(span, sanitized.output());
            long strengthModified = spanStrengthDeriver.persist(span, derivation);
            metricService.summary("vance.prak.strength.overrides")
                    .record(derivation.overrides().size());
            if (strengthModified > 0) {
                log.debug("Side-channel process='{}' trigger='{}' strength-tags-written: {} (overrides={})",
                        process.getId(), triggerLabel, strengthModified,
                        derivation.overrides().size());
            }

            PromotionContext promoteCtx = new PromotionContext(
                    process.getTenantId(),
                    projectId == null ? "" : projectId,
                    process.getSessionId(),
                    process.getId(),
                    runId);
            PromotionResult promotionResult =
                    prakPromotionService.promote(sanitized.output(), promoteCtx);
            metricService.summary("vance.prak.promotion.persisted")
                    .record(promotionResult.persistedMemoryIds().size());
            if (promotionResult.promoted() > 0 || promotionResult.inboxOffered() > 0) {
                log.info("Side-channel process='{}' trigger='{}' promoted={} inboxOffered={} skipped={} affectsDeferred={}",
                        process.getId(), triggerLabel,
                        promotionResult.promoted(),
                        promotionResult.inboxOffered(),
                        promotionResult.skipped(),
                        promotionResult.affectsDeferred());
            }

            persistRunRecord(
                    process, projectId, runId, triggerLabel,
                    raw.windowSpan(), span.size(),
                    sanitized.metrics(), derivation.overrides().size(),
                    strengthModified, promotionResult,
                    System.currentTimeMillis() - startMs);
            return true;
        } catch (RuntimeException e) {
            log.warn("Side-channel failed for process='{}' trigger='{}': {}",
                    process.getId(), triggerLabel, e.toString());
            metricService.counter("vance.prak.sideChannel",
                    "outcome", "error",
                    "reason", "n/a").increment();
            return false;
        }
    }

    // ─── helpers ───

    /** Trigger label → short prefix for the runId. */
    private static String triggerLabelPrefix(String trigger) {
        if (trigger == null || trigger.isBlank()) return "prak";
        if (trigger.startsWith("compaction")) return "compaction";
        if (trigger.startsWith("periodic")) return "periodic";
        if (trigger.startsWith("hot-path")) return "hotpath";
        if (trigger.startsWith("autodream")) return "autodream";
        return "prak";
    }

    private static List<SpanMessage> projectToSpan(List<ChatMessageDocument> docs) {
        List<SpanMessage> out = new ArrayList<>(docs.size());
        for (ChatMessageDocument doc : docs) {
            if (doc.getRole() == null) continue;
            String content = doc.getContent() == null ? "" : doc.getContent();
            out.add(new SpanMessage(doc.getId(), doc.getRole(), content));
        }
        return out;
    }

    private void persistRunRecord(
            ThinkProcessDocument process,
            @Nullable String projectId,
            String runId,
            String trigger,
            de.mhus.vance.shared.prak.WindowSpan window,
            int spanSize,
            SanitizeMetrics metrics,
            int strengthOverrides,
            long strengthTagsModified,
            PromotionResult promotionResult,
            long durationMs) {
        try {
            PrakRunRecord record = PrakRunRecord.builder()
                    .tenantId(process.getTenantId())
                    .projectId(projectId == null ? "" : projectId)
                    .sessionId(process.getSessionId())
                    .processId(process.getId())
                    .runId(runId)
                    .trigger(trigger)
                    .windowFromTurnId(window == null ? null : window.fromTurnId())
                    .windowToTurnId(window == null ? null : window.toTurnId())
                    .windowMessages(spanSize)
                    .rawItemCount(metrics.rawItemCount())
                    .finalItemCount(metrics.finalItemCount())
                    .droppedNoEvidence(metrics.droppedNoEvidence())
                    .droppedLowConfidence(metrics.droppedLowConfidence())
                    .droppedBySupersedeWithinBatch(metrics.droppedBySupersedeWithinBatch())
                    .duplicatesMerged(metrics.duplicatesMerged())
                    .confidencePenalised(metrics.confidencePenalised())
                    .hardCapTriggered(metrics.hardCapTriggered())
                    .evidenceCoverage(metrics.evidenceCoverage())
                    .lowCoverage(metrics.lowCoverage())
                    .strengthOverrides(strengthOverrides)
                    .strengthTagsModified(strengthTagsModified)
                    .promoted(promotionResult.promoted())
                    .inboxOffered(promotionResult.inboxOffered())
                    .skipped(promotionResult.skipped())
                    .refreshed(promotionResult.refreshed())
                    .affectsResolved(promotionResult.affectsResolved())
                    .affectsDeferred(promotionResult.affectsDeferred())
                    .persistedMemoryIds(new ArrayList<>(promotionResult.persistedMemoryIds()))
                    .durationMs(durationMs)
                    .build();
            prakRunService.save(record);
        } catch (RuntimeException e) {
            log.warn("PrakRun persist failed runId='{}': {}", runId, e.toString());
        }
    }
}
