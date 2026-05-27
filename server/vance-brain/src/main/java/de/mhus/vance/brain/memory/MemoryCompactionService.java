package de.mhus.vance.brain.memory;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.prak.CheapPathFilter;
import de.mhus.vance.brain.prak.PrakService;
import de.mhus.vance.brain.prak.PrakProperties;
import de.mhus.vance.brain.prak.PrakSanitizer;
import de.mhus.vance.brain.prak.SanitizeContext;
import de.mhus.vance.brain.prak.SanitizeResult;
import de.mhus.vance.brain.prak.SpanMessage;
import de.mhus.vance.brain.prak.SpanProfile;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.prak.EvaluationOutput;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Compacts a think-process's older active chat history into a single
 * {@link MemoryKind#ARCHIVED_CHAT} memory entry.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load active history for the process. If it's at most
 *       {@code keepRecent} messages, no-op.</li>
 *   <li>Split off the oldest portion to compact; keep the trailing
 *       {@code keepRecent} verbatim.</li>
 *   <li>If a previous {@code ARCHIVED_CHAT} memory is active, include
 *       its content as prior context for the summarizer (recursive
 *       compaction stays coherent).</li>
 *   <li>Call the summarizer LLM with the same provider/model the
 *       engine itself uses (re-using {@link AiChatConfig}).</li>
 *   <li>Persist a new {@link MemoryDocument} with the summary,
 *       {@code sourceRefs} pointing at the archived chat-message IDs.</li>
 *   <li>Atomically mark the source messages as
 *       {@code archivedInMemoryId = newMemory.id}.</li>
 *   <li>Supersede the previous active {@code ARCHIVED_CHAT} memory if
 *       any, so the chain is auditable.</li>
 * </ol>
 *
 * <p>Failures of the LLM call are caught and reported via
 * {@link CompactionResult#noop(String)} — the caller decides whether
 * to surface that as a warning or just continue with the un-compacted
 * history. Nothing in the chat log is mutated until the summary is
 * safely persisted, so a crashed call leaves the system in its prior
 * state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryCompactionService {

    private static final String SUMMARIZER_SYSTEM_PROMPT = """
            You are a conversation summarizer. Compact the chat history below
            into a concise note that preserves: names of people and things,
            decisions made, open questions, ongoing tasks, the current state.
            Use neutral past tense, third person. Be terse — aim for ~30% of
            the original length. Output only the summary text, no preamble or
            closing remarks.
            """;

    private static final String SETTING_AI_PROVIDER = "ai.default.provider";
    private static final String SETTING_AI_MODEL = "ai.default.model";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";
    private static final ProviderType DEFAULT_PROVIDER = ProviderType.ANTHROPIC;
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final AiModelService aiModelService;
    private final SessionService sessionService;
    private final SettingService settingService;
    private final FordProperties properties;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;
    private final MetricService metricService;
    private final PrakService prakService;
    private final CheapPathFilter cheapPathFilter;
    private final PrakSanitizer prakSanitizer;
    private final PrakProperties prakProperties;
    private final de.mhus.vance.brain.prak.SpanStrengthDeriver spanStrengthDeriver;
    private final de.mhus.vance.brain.prak.PrakPromotionService prakPromotionService;
    private final de.mhus.vance.shared.prak.audit.PrakRunService prakRunService;

    /**
     * Compacts older history of {@code process}. Resolves the
     * summarizer model from tenant settings (same provider/model the
     * engine itself uses). Idempotent on a too-short history (returns
     * {@link CompactionResult#noop}).
     */
    public CompactionResult compact(ThinkProcessDocument process) {
        AiChatConfig config = resolveAiConfig(process);
        return compact(process, config);
    }

    /**
     * Same as {@link #compact(ThinkProcessDocument)} but with a
     * pre-resolved {@link AiChatConfig}. Useful when the caller has
     * already resolved the config for another LLM call this turn.
     */
    public CompactionResult compact(ThinkProcessDocument process, AiChatConfig config) {
        String tenantId = process.getTenantId();
        String sessionId = process.getSessionId();
        String processId = process.getId();

        List<ChatMessageDocument> active = chatMessageService.activeHistory(
                tenantId, sessionId, processId);
        int keepRecent = Math.max(1, properties.getCompactionKeepRecent());
        if (active.size() <= keepRecent) {
            return CompactionResult.noop(
                    "history has " + active.size()
                            + " active messages, keepRecent=" + keepRecent
                            + " — nothing to compact");
        }

        int splitAt = active.size() - keepRecent;
        List<ChatMessageDocument> older = active.subList(0, splitAt);

        List<MemoryDocument> priorActive = memoryService.activeByProcessAndKind(
                tenantId, processId, MemoryKind.ARCHIVED_CHAT);
        MemoryDocument priorSummary = priorActive.isEmpty()
                ? null : priorActive.get(priorActive.size() - 1);

        String summary;
        try {
            summary = callSummarizer(process, config, priorSummary, older);
        } catch (RuntimeException e) {
            log.warn("Compaction summarizer failed for process='{}': {}",
                    processId, e.toString());
            return CompactionResult.noop("summarizer failed: " + e.getMessage());
        }
        if (summary.isBlank()) {
            return CompactionResult.noop("summarizer returned empty text");
        }

        // Persist the new memory first so chat-message archival points
        // at a stored id; if archival fails afterwards the rows simply
        // stay un-archived and the next attempt re-tries cleanly.
        List<String> olderIds = older.stream()
                .map(ChatMessageDocument::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("compactedMessages", olderIds.size());
        metadata.put("provider", config.provider());
        metadata.put("model", config.modelName());
        if (priorSummary != null) {
            metadata.put("supersededMemoryId", priorSummary.getId());
        }

        String projectId = sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .orElse("");
        MemoryDocument fresh = MemoryDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .sessionId(sessionId)
                .thinkProcessId(processId)
                .kind(MemoryKind.ARCHIVED_CHAT)
                .title("Compaction " + java.time.Instant.now())
                .content(summary)
                .sourceRefs(new ArrayList<>(olderIds))
                .metadata(metadata)
                .build();
        MemoryDocument saved = memoryService.save(fresh);

        long archived = chatMessageService.markArchived(olderIds, saved.getId());
        @Nullable String supersededId = null;
        if (priorSummary != null && priorSummary.getId() != null && saved.getId() != null) {
            memoryService.supersede(priorSummary.getId(), saved.getId());
            supersededId = priorSummary.getId();
        }
        log.info("Compaction process='{}' compacted={} archived={} memoryId='{}' superseded='{}' summaryChars={}",
                processId, olderIds.size(), archived, saved.getId(), supersededId, summary.length());

        metricService.counter("vance.memory.compaction", "mode", "sliding").increment();
        metricService.summary("vance.memory.compaction.messages", "mode", "sliding")
                .record(olderIds.size());

        runSideChannel(process, older, projectId, "compaction-side-channel: sliding");

        return CompactionResult.success(
                olderIds.size(), summary.length(), saved.getId(), supersededId);
    }

    /**
     * Range-based recompaction — folds a specific time-window of the
     * active history (typically a sub-topic plan from
     * {@code planning/topic-recompaction.md}) into one
     * {@link MemoryKind#ARCHIVED_CHAT} memory and replaces the originals
     * with a {@code SYSTEM} marker carrying the summary.
     *
     * <p>Same archival semantics as {@link #compact}: rows get an
     * {@code archivedInMemoryId} set, so they drop out of
     * {@code activeHistory(...)} but remain audit-readable via
     * {@code history(...)}. The marker {@code ChatMessageDocument}
     * inserted at the end of the range carries the tag
     * {@code RECOMPACTION:<topicLabel>} so {@code history_search} can
     * find it later.
     *
     * <p>The summarizer call uses the same provider/model that the
     * sliding-window path uses; no prior-summary chaining (a sub-topic
     * is by definition its own thing — chaining would dilute it).
     * Idempotent on an empty range — already-archived rows are skipped
     * by the finder.
     */
    public CompactionResult compactRange(
            ThinkProcessDocument process,
            @Nullable Instant fromCreatedAtInclusive,
            @Nullable Instant toCreatedAtInclusive,
            String topicLabel) {
        AiChatConfig config = resolveAiConfig(process);
        return compactRange(process, fromCreatedAtInclusive, toCreatedAtInclusive,
                topicLabel, config);
    }

    /** Same as {@link #compactRange(ThinkProcessDocument, java.time.Instant,
     *  java.time.Instant, String)} but with a pre-resolved
     *  {@link AiChatConfig}. */
    public CompactionResult compactRange(
            ThinkProcessDocument process,
            @Nullable Instant fromCreatedAtInclusive,
            @Nullable Instant toCreatedAtInclusive,
            String topicLabel,
            AiChatConfig config) {
        String tenantId = process.getTenantId();
        String sessionId = process.getSessionId();
        String processId = process.getId();

        List<ChatMessageDocument> range = chatMessageService.findActiveInRange(
                tenantId, processId, fromCreatedAtInclusive, toCreatedAtInclusive);
        if (range.isEmpty()) {
            return CompactionResult.noop("empty range — nothing to recompact");
        }

        String summary;
        try {
            summary = callSummarizer(process, config, /*priorSummary*/ null, range);
        } catch (RuntimeException e) {
            log.warn("Range-compaction summarizer failed for process='{}' topic='{}': {}",
                    processId, topicLabel, e.toString());
            return CompactionResult.noop("summarizer failed: " + e.getMessage());
        }
        if (summary.isBlank()) {
            return CompactionResult.noop("summarizer returned empty text");
        }

        List<String> rangeIds = range.stream()
                .map(ChatMessageDocument::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("compactedMessages", rangeIds.size());
        metadata.put("provider", config.provider());
        metadata.put("model", config.modelName());
        metadata.put("topicLabel", topicLabel);
        metadata.put("recompaction", true);
        if (fromCreatedAtInclusive != null) {
            metadata.put("rangeFromAt", fromCreatedAtInclusive.toString());
        }
        if (toCreatedAtInclusive != null) {
            metadata.put("rangeToAt", toCreatedAtInclusive.toString());
        }

        String projectId = sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .orElse("");
        MemoryDocument fresh = MemoryDocument.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .sessionId(sessionId)
                .thinkProcessId(processId)
                .kind(MemoryKind.ARCHIVED_CHAT)
                .title("Recompaction " + topicLabel)
                .content(summary)
                .sourceRefs(new ArrayList<>(rangeIds))
                .metadata(metadata)
                .build();
        MemoryDocument saved = memoryService.save(fresh);

        long archived = chatMessageService.markArchived(rangeIds, saved.getId());

        // Drop a SYSTEM-role marker carrying the summary so the LLM-replay
        // sees one stitch in place of the archived range. createdAt is
        // pinned one millisecond after the last range row so chronology
        // is preserved across both archived + active reads.
        Instant markerAt = range.getLast().getCreatedAt() == null
                ? Instant.now()
                : range.getLast().getCreatedAt().plusMillis(1);
        ChatMessageDocument marker = ChatMessageDocument.builder()
                .tenantId(tenantId)
                .sessionId(sessionId)
                .thinkProcessId(processId)
                .role(ChatRole.SYSTEM)
                .content(summary)
                .tags(new java.util.LinkedHashSet<>(
                        java.util.Set.of("RECOMPACTION:" + topicLabel)))
                .createdAt(markerAt)
                .build();
        chatMessageService.append(marker);

        log.info("Recompaction process='{}' topic='{}' range={} archived={} memoryId='{}' summaryChars={}",
                processId, topicLabel, rangeIds.size(), archived, saved.getId(), summary.length());

        metricService.counter("vance.memory.compaction", "mode", "range").increment();
        metricService.summary("vance.memory.compaction.messages", "mode", "range")
                .record(rangeIds.size());

        runSideChannel(process, range, projectId,
                "compaction-side-channel: range " + topicLabel);

        return CompactionResult.success(
                rangeIds.size(), summary.length(), saved.getId(), /*supersededMemoryId*/ null);
    }

    private String callSummarizer(
            ThinkProcessDocument process,
            AiChatConfig config,
            @Nullable MemoryDocument priorSummary,
            List<ChatMessageDocument> older) {
        AiChat ai = aiModelService.createChat(
                config,
                AiChatOptions.builder()
                        .userNotifier(msg -> progressEmitter.emitStatus(
                                process,
                                de.mhus.vance.api.progress.StatusTag.PROVIDER,
                                msg))
                        .build());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SUMMARIZER_SYSTEM_PROMPT));
        StringBuilder body = new StringBuilder();
        if (priorSummary != null && priorSummary.getContent() != null
                && !priorSummary.getContent().isBlank()) {
            body.append("EXISTING SUMMARY (compact this further along with the new turns):\n");
            body.append(priorSummary.getContent()).append("\n\n");
        }
        body.append("OLDER CONVERSATION TO COMPACT:\n");
        for (ChatMessageDocument m : older) {
            String role = m.getRole() == null ? "?" : m.getRole().name().toLowerCase();
            body.append('[').append(role).append("] ");
            body.append(m.getContent() == null ? "" : m.getContent());
            body.append('\n');
        }
        int cap = Math.max(1_000, properties.getCompactionMaxSourceChars());
        if (body.length() > cap) {
            int keep = body.length() - cap;
            body.delete(0, keep);
            log.warn("Compaction source over cap — dropped oldest {} chars", keep);
        }
        messages.add(UserMessage.from(body.toString()));

        ChatRequest request = ChatRequest.builder().messages(messages).build();
        String modelAlias = config.provider() + ":" + config.modelName();
        long startMs = System.currentTimeMillis();
        ChatResponse response = ai.chatModel().chat(request);
        llmCallTracker.record(
                process, request, response, System.currentTimeMillis() - startMs, modelAlias);
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        return text == null ? "" : text.trim();
    }

    /**
     * Side-channel pass: hands the same span the summarizer just
     * compacted to the {@link PrakService}, then routes the sanitised
     * output through three deterministic consumers:
     *
     * <ol>
     *   <li>{@link PrakSanitizer} — drops items with bad evidence /
     *       low confidence / duplicates; downgrades item-floods to
     *       inbox-offers.</li>
     *   <li>{@link de.mhus.vance.brain.prak.SpanStrengthDeriver} —
     *       writes {@code STRENGTH:*} tags onto the source chat
     *       messages so the context-assembler can drop weak rows
     *       later.</li>
     *   <li>{@link de.mhus.vance.brain.prak.PrakPromotionService} —
     *       persists {@code promote} items as {@code INSIGHT}
     *       memories; surfaces {@code inboxOffer} items as telemetry
     *       (Inbox-subsystem wiring lands in a later phase).</li>
     * </ol>
     *
     * <p>Bails early when {@link PrakProperties#isSideChannelEnabled()}
     * is false (the current default) or when the cheap-path pre-filter
     * judges the span too thin to be worth an analyzer call. Any
     * RuntimeException is caught and warn-logged — compaction itself
     * has already succeeded by the time we reach here.
     */
    private void runSideChannel(
            ThinkProcessDocument process,
            List<ChatMessageDocument> spanDocs,
            String projectId,
            String windowHint) {
        if (!prakProperties.isSideChannelEnabled()) {
            return;
        }
        if (spanDocs == null || spanDocs.isEmpty()) {
            return;
        }
        long startMs = System.currentTimeMillis();
        String runId = "compaction-" + process.getId() + "-" + Instant.now();
        try {
            List<SpanMessage> span = projectToSpan(spanDocs);
            SpanProfile profile = cheapPathFilter.profile(span);
            if (profile.isSkippable()) {
                log.debug("Side-channel skipped for process='{}' reason='{}'",
                        process.getId(), profile.skipReason());
                metricService.counter("vance.prak.sideChannel",
                        "outcome", "skipped",
                        "reason", profile.skipReason() == null
                                ? "unknown" : profile.skipReason()).increment();
                return;
            }

            EvaluationOutput raw = prakService.analyze(
                    process.getTenantId(),
                    projectId == null || projectId.isBlank() ? null : projectId,
                    process.getId(),
                    span,
                    windowHint,
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
            log.info("Side-channel process='{}' raw={} final={} dropped(noEvidence={}, lowConf={}, supersede={}) merged={} hardCap={} coverage={}",
                    process.getId(),
                    sanitized.metrics().rawItemCount(),
                    sanitized.metrics().finalItemCount(),
                    sanitized.metrics().droppedNoEvidence(),
                    sanitized.metrics().droppedLowConfidence(),
                    sanitized.metrics().droppedBySupersedeWithinBatch(),
                    sanitized.metrics().duplicatesMerged(),
                    sanitized.metrics().hardCapTriggered(),
                    String.format("%.2f", sanitized.metrics().evidenceCoverage()));

            metricService.counter("vance.prak.sideChannel",
                    "outcome", "success").increment();
            metricService.summary("vance.prak.items.final")
                    .record(sanitized.metrics().finalItemCount());

            // Derive + persist span-strength tags from the sanitised output.
            var derivation = spanStrengthDeriver.derive(span, sanitized.output());
            long strengthModified = spanStrengthDeriver.persist(span, derivation);
            metricService.summary("vance.prak.strength.overrides")
                    .record(derivation.overrides().size());
            if (strengthModified > 0) {
                log.debug("Side-channel process='{}' strength-tags-written: {} (overrides={})",
                        process.getId(), strengthModified, derivation.overrides().size());
            }

            // Final consumer: persist promotable items as INSIGHT memories,
            // surface instructions through inbox-offer telemetry. runId
            // matches the audit record so operators can join the two.
            de.mhus.vance.brain.prak.PromotionContext promoteCtx =
                    new de.mhus.vance.brain.prak.PromotionContext(
                            process.getTenantId(),
                            projectId == null ? "" : projectId,
                            process.getSessionId(),
                            process.getId(),
                            runId);
            de.mhus.vance.brain.prak.PromotionResult promotionResult =
                    prakPromotionService.promote(sanitized.output(), promoteCtx);
            metricService.summary("vance.prak.promotion.persisted")
                    .record(promotionResult.persistedMemoryIds().size());
            if (promotionResult.promoted() > 0 || promotionResult.inboxOffered() > 0) {
                log.info("Side-channel process='{}' promoted={} inboxOffered={} skipped={} affectsDeferred={}",
                        process.getId(),
                        promotionResult.promoted(),
                        promotionResult.inboxOffered(),
                        promotionResult.skipped(),
                        promotionResult.affectsDeferred());
            }

            // Audit row — one PrakRunRecord per successful pass. Failures
            // before this point only emit the {outcome=error} counter.
            persistRunRecord(
                    process, projectId, runId, windowHint,
                    raw.windowSpan(), span.size(),
                    sanitized.metrics(), derivation.overrides().size(),
                    strengthModified, promotionResult,
                    System.currentTimeMillis() - startMs);
        } catch (RuntimeException e) {
            log.warn("Side-channel failed for process='{}': {}",
                    process.getId(), e.toString());
            metricService.counter("vance.prak.sideChannel",
                    "outcome", "error").increment();
        }
    }

    private void persistRunRecord(
            ThinkProcessDocument process,
            String projectId,
            String runId,
            String trigger,
            de.mhus.vance.shared.prak.WindowSpan window,
            int spanSize,
            de.mhus.vance.brain.prak.SanitizeMetrics metrics,
            int strengthOverrides,
            long strengthTagsModified,
            de.mhus.vance.brain.prak.PromotionResult promotionResult,
            long durationMs) {
        try {
            de.mhus.vance.shared.prak.audit.PrakRunRecord record =
                    de.mhus.vance.shared.prak.audit.PrakRunRecord.builder()
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
            // Audit-write failure must not poison the pipeline.
            log.warn("PrakRun persist failed runId='{}': {}", runId, e.toString());
        }
    }

    private static List<SpanMessage> projectToSpan(List<ChatMessageDocument> docs) {
        List<SpanMessage> out = new ArrayList<>(docs.size());
        for (ChatMessageDocument doc : docs) {
            if (doc.getRole() == null) {
                continue;
            }
            String content = doc.getContent() == null ? "" : doc.getContent();
            out.add(new SpanMessage(doc.getId(), doc.getRole(), content));
        }
        return out;
    }

    private AiChatConfig resolveAiConfig(ThinkProcessDocument process) {
        String tenantId = process.getTenantId();
        String processId = process.getId();
        String providerCascade = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, processId, SETTING_AI_PROVIDER);
        String provider = (providerCascade == null || providerCascade.isBlank())
                ? DEFAULT_PROVIDER.wireName() : providerCascade;
        String modelCascade = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, processId, SETTING_AI_MODEL);
        String model = (modelCascade == null || modelCascade.isBlank())
                ? DEFAULT_MODEL : modelCascade;
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, provider);
        String apiKey = settingService.getDecryptedPasswordCascade(
                tenantId, /*projectId*/ null, processId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + provider
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(provider, model, apiKey);
    }
}
