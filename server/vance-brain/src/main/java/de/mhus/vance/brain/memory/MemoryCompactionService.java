package de.mhus.vance.brain.memory;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.brain.prak.PrakSideChannelRunner;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final PrakSideChannelRunner prakSideChannelRunner;
    private final StrengthAwareSelector strengthAwareSelector;
    private final CompactionTriggerService compactionTriggerService;
    private final de.mhus.vance.brain.prak.PrakProperties prakProperties;
    private final de.mhus.vance.brain.prak.PrakPeriodicTrigger prakPeriodicTrigger;
    private final de.mhus.vance.shared.settings.LanguageResolver languageResolver;

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
     * pre-resolved {@link AiChatConfig}. Default mode {@link
     * CompactionMode#SOFT}.
     */
    public CompactionResult compact(ThinkProcessDocument process, AiChatConfig config) {
        return compact(process, config, CompactionMode.SOFT);
    }

    /**
     * Mode-aware compaction entry point. Uses {@link
     * StrengthAwareSelector} to pick which messages get folded into
     * the {@code ARCHIVED_CHAT} summary based on their
     * {@code STRENGTH:*} tags + the active mode. When
     * {@code vance.prak.inlineOnCompaction} is true, Prak runs ad-hoc
     * over any still-unrated messages first.
     */
    public CompactionResult compact(
            ThinkProcessDocument process, AiChatConfig config, CompactionMode mode) {
        if (mode == CompactionMode.NONE) {
            return CompactionResult.noop("mode=NONE — no compaction requested");
        }
        String tenantId = process.getTenantId();
        String sessionId = process.getSessionId();
        String processId = process.getId();

        List<ChatMessageDocument> active = chatMessageService.activeHistory(
                tenantId, sessionId, processId);
        // Gate the early no-op on the MODE anchor, not a flat keepRecent(10):
        // EMERGENCY (anchor 3) must be able to compact a short-but-huge history
        // (e.g. 8 giant messages at 95% context) — the flat floor refused
        // compaction exactly when it was needed most. The selector below applies
        // the same anchor per-message.
        int anchor = strengthAwareSelector.anchorForMode(mode);
        if (active.size() <= anchor) {
            return CompactionResult.noop(
                    "history has " + active.size()
                            + " active messages, mode=" + mode + " anchor=" + anchor
                            + " — nothing to compact");
        }

        // Optional inline Prak: pay the Prak-call latency to get all
        // messages strength-tagged before the selector picks who to
        // compact. Without this, unrated messages get the optimistic-
        // fallback heuristic (TrivialPatterns) in the selector.
        if (prakProperties.isInlineOnCompaction() && prakProperties.isSideChannelEnabled()) {
            try {
                String projectIdForPrak = sessionService.findBySessionId(sessionId)
                        .map(SessionDocument::getProjectId).orElse("");
                prakPeriodicTrigger.maybeFire(process, projectIdForPrak);
                // Re-read tags — periodic trigger wrote STRENGTH:* on
                // the chat-message documents.
                active = chatMessageService.activeHistory(tenantId, sessionId, processId);
            } catch (RuntimeException e) {
                log.warn("Inline Prak on compaction failed for process='{}': {}",
                        processId, e.toString());
            }
        }

        String chatLang = resolveChatLanguage(process);
        List<ChatMessageDocument> older =
                strengthAwareSelector.selectForCompaction(active, mode, chatLang);
        if (older.isEmpty()) {
            return CompactionResult.noop(
                    "mode=" + mode + " — no messages eligible for compaction "
                            + "(everything in anchor or PINNED/STRONG)");
        }

        List<MemoryDocument> priorActive = memoryService.activeByProcessAndKind(
                tenantId, processId, MemoryKind.ARCHIVED_CHAT);
        // Chain/supersede the newest NON-recompaction summary, not simply the
        // newest active row: a range-recompaction row (from a RECOMPACTION_OFFER)
        // does not supersede the bulk sliding summary, so picking the newest row
        // would chain from the narrow recompaction topic and orphan the bulk
        // summary forever (its gist dropped from all later prompts). Mirrors
        // MemoryContextLoader's read-side isRecompaction skip.
        MemoryDocument priorSummary = null;
        for (int i = priorActive.size() - 1; i >= 0; i--) {
            if (!isRecompaction(priorActive.get(i))) {
                priorSummary = priorActive.get(i);
                break;
            }
        }

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
        log.info("Compaction process='{}' mode={} compacted={} archived={} memoryId='{}' superseded='{}' summaryChars={}",
                processId, mode, olderIds.size(), archived, saved.getId(),
                supersededId, summary.length());

        String modeLabel = mode.name().toLowerCase(java.util.Locale.ROOT);
        metricService.counter("vance.memory.compaction", "mode", modeLabel).increment();
        metricService.summary("vance.memory.compaction.messages", "mode", modeLabel)
                .record(olderIds.size());

        // Client side-channel: surface the compaction event in the chat
        // panel as a [compaction] status ping so the user sees that the
        // history just got rebased onto an ARCHIVED_CHAT summary. Same
        // rendering path as [tool_start] etc. — engine-agnostic.
        progressEmitter.emitStatus(
                process,
                de.mhus.vance.api.progress.StatusTag.COMPACTION,
                mode.name() + " · " + olderIds.size()
                        + " msgs → " + summary.length() + " chars summary");

        runSideChannel(process, older, projectId,
                "compaction-side-channel:" + modeLabel);

        return CompactionResult.success(
                olderIds.size(), summary.length(), saved.getId(), supersededId);
    }

    /**
     * One-shot helper for engines: evaluate the trigger against the
     * outgoing prompt + model context-window, and compact if needed.
     * Returns the {@link CompactionResult} so the caller can rebuild
     * its prompt when {@code compacted()} is true.
     *
     * <p>Mode selection comes from
     * {@link CompactionTriggerService#evaluate(List, de.mhus.vance.brain.ai.ModelInfo)};
     * mode {@code NONE} short-circuits to a no-op. Failures are
     * caught — the engine's turn isn't broken by a compaction error,
     * just logged.
     */
    public CompactionResult compactIfNeeded(
            ThinkProcessDocument process,
            AiChatConfig config,
            List<dev.langchain4j.data.message.ChatMessage> outgoingPrompt,
            de.mhus.vance.brain.ai.ModelInfo modelInfo) {
        try {
            CompactionMode mode = compactionTriggerService.evaluate(outgoingPrompt, modelInfo);
            if (mode == CompactionMode.NONE) {
                return CompactionResult.noop("trigger=NONE");
            }
            log.info("Compaction trigger fired process='{}' mode={} est={} ctx={}",
                    process.getId(), mode,
                    compactionTriggerService.estimateTokens(outgoingPrompt),
                    modelInfo.contextWindowTokens());
            return compact(process, config, mode);
        } catch (RuntimeException e) {
            log.warn("compactIfNeeded failed process='{}': {}",
                    process.getId(), e.toString());
            return CompactionResult.noop("compactIfNeeded threw: " + e.getMessage());
        }
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
        // insertCopies (not append) so the pinned createdAt survives — the
        // @CreatedDate auditing on append() would re-stamp the marker to
        // "now", stitching the summary in at the wrong chronological spot
        // (code-review Phase 2).
        chatMessageService.insertCopies(java.util.List.of(marker));

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
        // Prefix = date stamp + carried-forward priorSummary. This is the
        // accumulated gist and MUST survive the source cap — trimming it (the old
        // delete-from-front bug) erased the coherence the recursive-compaction
        // contract depends on. Only the raw-messages block is capped.
        StringBuilder prefix = new StringBuilder();
        // Anchor the summary in time. We pass the date only (no clock time,
        // no timezone): compaction runs server-side asynchronously without
        // any client context, so we can't honestly emit a wall-clock that
        // matches the tenant's locale. The day is what matters for
        // memory-anchoring anyway. If a tenant ever wants timestamps in
        // their timezone, add a `display.timezone` setting and feed it
        // through here.
        prefix.append("Today's date (UTC): ")
                .append(java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                .append("\n\n");
        if (priorSummary != null && priorSummary.getContent() != null
                && !priorSummary.getContent().isBlank()) {
            prefix.append("EXISTING SUMMARY (compact this further along with the new turns):\n");
            prefix.append(priorSummary.getContent()).append("\n\n");
        }
        String messagesHeader = "OLDER CONVERSATION TO COMPACT:\n";
        StringBuilder rawMessages = new StringBuilder();
        for (ChatMessageDocument m : older) {
            String role = m.getRole() == null ? "?" : m.getRole().name().toLowerCase();
            rawMessages.append('[').append(role).append("] ");
            rawMessages.append(m.getContent() == null ? "" : m.getContent());
            rawMessages.append('\n');
        }
        int cap = Math.max(1_000, properties.getCompactionMaxSourceChars());
        int messagesBudget = Math.max(0, cap - prefix.length() - messagesHeader.length());
        if (rawMessages.length() > messagesBudget) {
            int drop = rawMessages.length() - messagesBudget;
            // Drop the OLDEST raw-message chars (front of the messages block),
            // never the date + carried priorSummary above.
            rawMessages.delete(0, drop);
            log.warn("Compaction source over cap — dropped oldest {} chars of raw messages", drop);
        }
        messages.add(UserMessage.from(prefix + messagesHeader + rawMessages));

        ChatRequest request = ChatRequest.builder().messages(messages).build();
        String modelAlias = config.provider() + ":" + config.modelName();
        long startMs = System.currentTimeMillis();
        ChatResponse response = ai.chatModel().chat(request);
        llmCallTracker.record(
                process, request, response, System.currentTimeMillis() - startMs, modelAlias);
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return "";
        // Prepend a deterministic date-stamp so the persisted memory always
        // carries the day the compaction happened — without depending on
        // whether the LLM chose to mention it in its prose. UTC date only;
        // see body-builder comment above for why we don't emit clock time.
        // Re-compactions inherit this stamp via the priorSummary body;
        // only the newest run's stamp lives at the top of the freshest doc.
        String stamp = "[" + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + "] ";
        return stamp + trimmed;
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
        // The runner already catches internally — this is belt-and-suspenders
        // so a bug in the runner can never poison a successful compaction.
        try {
            prakSideChannelRunner.run(process, projectId, spanDocs, windowHint);
        } catch (RuntimeException e) {
            log.warn("Prak side-channel from compaction failed process='{}': {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * Resolves the active {@code chat.language} for the process so the
     * {@link StrengthAwareSelector} optimistic-fallback matches the
     * user's trivial-turn phrases on top of the English baseline. Uses
     * the <b>defaulting</b> resolver variant — a {@code de}-defaulted
     * install ({@code vance.language.default}) matches the German
     * ack/self-narration phrases without any per-user setting. Mirrors
     * {@code PrakSideChannelRunner.resolveChatLanguage(...)}.
     */
    private String resolveChatLanguage(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        SessionDocument session =
                (sessionId == null || sessionId.isBlank())
                        ? null
                        : sessionService.findBySessionId(sessionId).orElse(null);
        String userId = session == null ? null : session.getUserId();
        String projectId = session == null ? null : session.getProjectId();
        return languageResolver.chatLanguage(
                process.getTenantId(), userId, projectId, process.getId());
    }

    private AiChatConfig resolveAiConfig(ThinkProcessDocument process) {
        String tenantId = process.getTenantId();
        String processId = process.getId();
        // Include the project layer of the settings cascade (derived from the
        // session, as done for the memory doc): a tenant that configures
        // provider/model/apiKey/baseUrl only at PROJECT scope would otherwise get
        // tenant defaults / a blank apiKey, and the recompaction-offer summarizer
        // call would silently fail → the accepted offer does nothing.
        String projectId = sessionService.findBySessionId(process.getSessionId())
                .map(SessionDocument::getProjectId)
                .orElse(null);
        String providerCascade = settingService.getStringValueCascade(
                tenantId, projectId, processId, SETTING_AI_PROVIDER);
        String provider = (providerCascade == null || providerCascade.isBlank())
                ? DEFAULT_PROVIDER.wireName() : providerCascade;
        String modelCascade = settingService.getStringValueCascade(
                tenantId, projectId, processId, SETTING_AI_MODEL);
        String model = (modelCascade == null || modelCascade.isBlank())
                ? DEFAULT_MODEL : modelCascade;
        // Compaction reads provider/model from settings directly (no recipe
        // alias), so instance defaults to the protocol wire-name. Named
        // instances aren't reachable here without going through the resolver.
        String apiKey = ChatBehaviorBuilder.resolveApiKey(
                provider, provider, tenantId, projectId, processId, settingService);
        String baseUrl = ChatBehaviorBuilder.resolveBaseUrl(
                provider, tenantId, projectId, processId, settingService);
        return new AiChatConfig(provider, model, apiKey, baseUrl);
    }

    /**
     * Whether {@code m} is a range-recompaction row (from a RECOMPACTION_OFFER)
     * rather than a bulk sliding-window summary. Mirrors MemoryContextLoader's
     * read-side check so the write side (priorSummary selection) agrees.
     */
    private static boolean isRecompaction(MemoryDocument m) {
        return m.getMetadata() != null
                && Boolean.TRUE.equals(m.getMetadata().get("recompaction"));
    }
}
