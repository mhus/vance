package de.mhus.vance.brain.memory;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ford.FordProperties;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
    private static final String DEFAULT_PROVIDER = "anthropic";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final ChatMessageService chatMessageService;
    private final MemoryService memoryService;
    private final AiModelService aiModelService;
    private final SessionService sessionService;
    private final SettingService settingService;
    private final FordProperties properties;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;

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

        return CompactionResult.success(
                olderIds.size(), summary.length(), saved.getId(), supersededId);
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
                process, response, System.currentTimeMillis() - startMs, modelAlias);
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        return text == null ? "" : text.trim();
    }

    private AiChatConfig resolveAiConfig(ThinkProcessDocument process) {
        String tenantId = process.getTenantId();
        String processId = process.getId();
        String providerCascade = settingService.getStringValueCascade(
                tenantId, /*projectId*/ null, processId, SETTING_AI_PROVIDER);
        String provider = (providerCascade == null || providerCascade.isBlank())
                ? DEFAULT_PROVIDER : providerCascade;
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
