package de.mhus.vance.brain.session;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.common.AccentColor;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>Backed by {@link LightLlmService} using the bundled
 * {@code session-metadata} recipe (config profile,
 * {@code internal: true}). Tenants override the recipe to change the
 * colour palette or swap the model without a Java change.
 *
 * <p>See {@code specification/session-lifecycle.md} §14.1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMetadataSuggester {

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "session-metadata";

    /** Reply field names — match the schema enforced below. */
    static final String FIELD_TITLE = "title";
    static final String FIELD_ICON = "icon";
    static final String FIELD_COLOR = "color";

    /** Schema enforced on the LightLlm reply. */
    static final Map<String, Object> METADATA_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    FIELD_TITLE, Map.of(
                            "type", "string",
                            "description", "Max 60 chars, specific, no quotes."),
                    FIELD_ICON, Map.of(
                            "type", "string",
                            "description", "SINGLE emoji codepoint or ZWJ sequence."),
                    FIELD_COLOR, Map.of(
                            "type", "string",
                            "enum", List.of(
                                    "SLATE", "RED", "ORANGE", "AMBER", "GREEN", "TEAL",
                                    "CYAN", "BLUE", "INDIGO", "PURPLE", "PINK", "ROSE"),
                            "description", "Mood-matched session colour.")),
            "required", List.of(FIELD_TITLE, FIELD_ICON, FIELD_COLOR));

    private static final int MAX_OPENING_MESSAGES = 6;
    private static final int MAX_OPENING_CHARS = 4_000;

    private final LightLlmService lightLlm;
    private final ChatMessageService chatMessageService;
    private final SessionService sessionService;

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

            Map<String, Object> raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt(session.getSessionId())
                    .pebbleVars(Map.of("opening", renderOpening(opening)))
                    .schema(METADATA_SCHEMA)
                    .tenantId(session.getTenantId())
                    .projectId(session.getProjectId())
                    .build());

            Suggestion parsed = parseReply(raw);
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

    static String renderOpening(List<ChatMessageDocument> messages) {
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

    static @Nullable Suggestion parseReply(Map<String, Object> raw) {
        String title = readString(raw, FIELD_TITLE);
        String icon = readString(raw, FIELD_ICON);
        AccentColor color = null;
        String colorRaw = readString(raw, FIELD_COLOR);
        if (colorRaw != null) {
            try {
                color = AccentColor.valueOf(colorRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Unknown color from LLM — drop and let other fields apply.
            }
        }
        if (title == null && icon == null && color == null) return null;
        return new Suggestion(title, icon, color);
    }

    private static @Nullable String readString(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (!(v instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    record Suggestion(
            @Nullable String title,
            @Nullable String icon,
            @Nullable AccentColor color) {
    }
}
