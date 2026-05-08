package de.mhus.vance.brain.eddie.triage;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Default {@link LlmTriageStage} — calls {@code default:fast} on a
 * per-tenant provider with a tight JSON-output prompt. Engaged from
 * {@link OutputTriageService#classifyWithContext} only when the
 * heuristic returns {@link TriageDecision#REFORMULATE} (the
 * mid-length / non-trivial slice the heuristic can't decide alone).
 *
 * <p>Failure modes degrade gracefully: any thrown exception (no API
 * key, JSON parse error, model timeout) is logged at {@code debug}
 * and the heuristic verdict propagates upward unchanged. The triage
 * pipeline must never block worker frame processing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultLlmTriageStage implements LlmTriageStage {

    private static final String FAST_MODEL_ALIAS = "default:fast";

    private static final String SYSTEM_PROMPT = """
            You classify a worker chat reply for a hub assistant called Eddie.

            Eddie speaks aloud (TTS) by default and routes worker output to the user.
            Your job: pick the right routing decision, gauge how loud the reply
            should surface, and write two short summaries Eddie will keep around.

            Output format — strict JSON, no prose around it, no markdown:
            {
              "decision": "VERBATIM" | "REFORMULATE" | "INBOX",
              "criticality": "LOW" | "NORMAL" | "CRITICAL",
              "spokenAnnouncement": "1-2 short sentences Eddie will say (no markdown, voice-friendly)",
              "memorySummary": "single line (≤120 chars) summarising the reply for Eddie's memory"
            }

            Rules:
            - VERBATIM: short voice-friendly prose with no structure. Eddie reads it as-is.
            - REFORMULATE: needs a bit of rewording but is still spoken inline.
            - INBOX: long / structured / has code or markdown / better read at leisure.
            - CRITICAL: plan-approval, delete-confirmation, anything where paraphrasing
              could lose user-relevant specificity. CRITICAL forbids REFORMULATE.
            - NORMAL: typical worker reply.
            - LOW: trivial ack / status ping.
            """;

    private final AiModelResolver aiModelResolver;
    private final AiModelService aiModelService;
    private final SettingService settingService;
    private final ObjectMapper objectMapper;

    @Override
    public TriageResult refine(
            TriageInput input, TriageResult heuristic, ThinkProcessDocument context) {
        AiChatConfig config;
        try {
            config = ChatBehaviorBuilder.resolveOne(
                    FAST_MODEL_ALIAS,
                    context.getTenantId(),
                    context.getProjectId(),
                    context.getId(),
                    settingService,
                    aiModelResolver);
        } catch (RuntimeException e) {
            // Tenant has no API key for the resolved fast-provider.
            // Heuristic is the only realistic answer — propagate it.
            log.debug("LlmTriageStage skipped (no fast model resolvable): {}", e.toString());
            return heuristic;
        }

        AiChat ai = aiModelService.createChat(config, AiChatOptions.defaults());
        StringBuilder body = new StringBuilder();
        body.append("WORKER: ").append(input.workerEngine() == null ? "?" : input.workerEngine())
                .append("\nVOICE_MODE: ").append(input.voiceMode())
                .append("\nHEURISTIC_HINT: ").append(heuristic.decision().name()).append('\n')
                .append("--- WORKER REPLY ---\n")
                .append(input.text() == null ? "" : input.text())
                .append("\n--- END ---");
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(body.toString()));
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        ChatResponse response;
        try {
            response = ai.chatModel().chat(request);
        } catch (RuntimeException e) {
            log.debug("LlmTriageStage chat failed: {}", e.toString());
            return heuristic;
        }
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank()) return heuristic;

        return parseResponse(text, heuristic);
    }

    /**
     * Parses the JSON envelope. Tolerant: missing fields fall back to
     * the heuristic's value; malformed JSON / unknown enum values fall
     * back wholesale.
     */
    private TriageResult parseResponse(String json, TriageResult fallback) {
        String trimmed = stripCodeFences(json.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (RuntimeException e) {
            log.debug("LlmTriageStage: malformed JSON, fallback to heuristic: {}", e.toString());
            return fallback;
        }
        TriageDecision decision = parseDecision(root.path("decision").asText(null), fallback.decision());
        Criticality criticality = parseCriticality(
                root.path("criticality").asText(null), fallback.criticality());
        String spoken = textOrNull(root.path("spokenAnnouncement"));
        String summary = textOrNull(root.path("memorySummary"));
        if (summary == null || summary.isBlank()) summary = fallback.memorySummary();
        if (spoken == null || spoken.isBlank()) spoken = fallback.spokenAnnouncement();

        return new TriageResult(decision, criticality, spoken, summary);
    }

    private static TriageDecision parseDecision(String s, TriageDecision fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return TriageDecision.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Criticality parseCriticality(String s, Criticality fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Criticality.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String t = node.asText(null);
        return t == null || t.isBlank() ? null : t.trim();
    }

    /**
     * Strips a fenced code block (```json … ```), if the model wrapped
     * its JSON despite the instruction.
     */
    static String stripCodeFences(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl < 0) return s;
            String body = s.substring(firstNl + 1);
            int lastFence = body.lastIndexOf("```");
            return lastFence >= 0 ? body.substring(0, lastFence).trim() : body.trim();
        }
        return s;
    }
}
