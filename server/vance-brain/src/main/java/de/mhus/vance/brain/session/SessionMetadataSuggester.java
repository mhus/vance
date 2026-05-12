package de.mhus.vance.brain.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.SessionColor;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One-shot LLM call that proposes a {@code title}, {@code icon} and
 * {@code color} for a session right after the first user/assistant
 * exchange. The output only fills empty fields — user-set values stay.
 *
 * <p>The call uses the same provider/model cascade as
 * {@link de.mhus.vance.brain.memory.MemoryCompactionService}: settings
 * {@code ai.default.provider} / {@code ai.default.model} with the same
 * API-key resolution. A small/fast model gets picked when the tenant
 * has configured a {@code small}-tier alias; otherwise the default
 * model is used (the response is short enough that the cost is
 * negligible).
 *
 * <p>See {@code specification/session-lifecycle.md} §14.1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMetadataSuggester {

    private static final String SETTING_AI_PROVIDER = "ai.default.provider";
    private static final String SETTING_AI_MODEL = "ai.default.model";
    private static final String SETTING_AI_MODEL_SMALL = "ai.alias.default.small";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private static final String DEFAULT_PROVIDER = "anthropic";
    private static final String DEFAULT_MODEL = "claude-haiku-4-5";

    private static final int MAX_OPENING_MESSAGES = 6;
    private static final int MAX_OPENING_CHARS = 4_000;

    private static final String SYSTEM_PROMPT = """
            You write very short labels for a chat session based on its
            first few messages. Reply with ONE single JSON object, no
            markdown fences, no preamble. Keys:
              "title"  – short (max 60 chars), specific, no quotes
              "icon"   – a SINGLE emoji codepoint (or ZWJ sequence)
              "color"  – ONE of: SLATE, RED, ORANGE, AMBER, GREEN, TEAL,
                         CYAN, BLUE, INDIGO, PURPLE, PINK, ROSE
            Match the color to the topic mood (debugging→AMBER,
            architecture→INDIGO, research→TEAL, refactor→BLUE,
            discussion→PURPLE, bugfix→RED). If unsure, pick a neutral
            color. Never leave a field empty.""";

    private final AiModelService aiModelService;
    private final SettingService settingService;
    private final ChatMessageService chatMessageService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @Value("${vance.session.metadata-suggester.enabled:true}")
    private boolean enabled;

    /**
     * Compute and persist auto-suggested metadata for {@code session}.
     * Skipped when the session already has user-set title / icon /
     * color, or when the suggester is disabled. Never throws —
     * suggestion is best-effort.
     */
    public void suggest(SessionDocument session) {
        if (!enabled || session == null) return;
        if (allMetadataFilled(session)) return;
        if (session.getUserTouchedAt() != null) {
            // userTouchedAt is set → user already engaged, do not override
            // (defensive — caller already checks; cheap to double-check).
            return;
        }
        try {
            List<ChatMessageDocument> opening = chatMessageService.openingWindow(
                    session.getTenantId(),
                    session.getSessionId(),
                    List.of(ChatRole.USER, ChatRole.ASSISTANT),
                    MAX_OPENING_MESSAGES);
            if (opening.isEmpty()) return;

            AiChatConfig config = resolveAiConfig(session);
            AiChat ai = aiModelService.createChat(
                    config,
                    AiChatOptions.builder()
                            .tenantId(session.getTenantId())
                            .projectId(session.getProjectId())
                            .build());

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            messages.add(UserMessage.from(renderOpening(opening)));
            ChatRequest request = ChatRequest.builder().messages(messages).build();
            ChatResponse response = ai.chatModel().chat(request);

            String text = response.aiMessage() == null ? null : response.aiMessage().text();
            if (text == null || text.isBlank()) return;

            Suggestion parsed = parse(text);
            if (parsed == null) return;

            sessionService.applyAutoSuggestedMetadata(
                    session.getSessionId(),
                    parsed.title(),
                    parsed.icon(),
                    parsed.color());
            log.info("Auto-suggested metadata session='{}' title='{}' icon='{}' color={}",
                    session.getSessionId(), parsed.title(), parsed.icon(), parsed.color());
        } catch (RuntimeException e) {
            log.warn("Metadata suggester failed for session='{}': {}",
                    session.getSessionId(), e.toString());
        }
    }

    private static boolean allMetadataFilled(SessionDocument session) {
        return session.getTitle() != null
                && session.getIcon() != null
                && session.getColor() != null;
    }

    private AiChatConfig resolveAiConfig(SessionDocument session) {
        String tenantId = session.getTenantId();
        String projectId = session.getProjectId();
        String providerCascade = settingService.getStringValueCascade(
                tenantId, projectId, /*processId*/ null, SETTING_AI_PROVIDER);
        String provider = (providerCascade == null || providerCascade.isBlank())
                ? DEFAULT_PROVIDER : providerCascade;
        // Prefer the small-tier alias when set; otherwise default model.
        String modelCascade = settingService.getStringValueCascade(
                tenantId, projectId, /*processId*/ null, SETTING_AI_MODEL_SMALL);
        if (modelCascade == null || modelCascade.isBlank()) {
            modelCascade = settingService.getStringValueCascade(
                    tenantId, projectId, /*processId*/ null, SETTING_AI_MODEL);
        }
        String model = (modelCascade == null || modelCascade.isBlank())
                ? DEFAULT_MODEL : modelCascade;
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, provider);
        String apiKey = settingService.getDecryptedPasswordCascade(
                tenantId, projectId, /*processId*/ null, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + provider
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(provider, model, apiKey);
    }

    private static String renderOpening(List<ChatMessageDocument> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageDocument m : messages) {
            String role = m.getRole() == null ? "?" : m.getRole().name().toLowerCase(Locale.ROOT);
            sb.append('[').append(role).append("] ");
            sb.append(m.getContent() == null ? "" : m.getContent());
            sb.append('\n');
            if (sb.length() > MAX_OPENING_CHARS) {
                sb.setLength(MAX_OPENING_CHARS);
                break;
            }
        }
        return sb.toString();
    }

    private @Nullable Suggestion parse(String raw) {
        String trimmed = raw.trim();
        // Strip optional markdown fences if the model ignored the rule.
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) trimmed = trimmed.substring(0, lastFence);
            trimmed = trimmed.trim();
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            String title = textOrNull(node, "title");
            String icon = textOrNull(node, "icon");
            String colorRaw = textOrNull(node, "color");
            SessionColor color = null;
            if (colorRaw != null) {
                try {
                    color = SessionColor.valueOf(colorRaw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Unknown color from LLM — drop and let other fields apply.
                }
            }
            if (title == null && icon == null && color == null) return null;
            return new Suggestion(title, icon, color);
        } catch (Exception e) {
            log.debug("Failed to parse suggester response as JSON: {}", e.toString());
            return null;
        }
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        String s = child.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private record Suggestion(
            @Nullable String title,
            @Nullable String icon,
            @Nullable SessionColor color) {
    }
}
