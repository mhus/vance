package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves per-turn "Is this chat session currently in the Cortex
 * editor view?" state for prompt injection. Mirrors the {@code voiceMode}
 * pattern: engines call {@link #resolve} once per turn and feed the
 * result into the {@link de.mhus.vance.brain.prompt.PromptContextBuilder}
 * so the system-prompt template (e.g. {@code arthur-prompt.md}) can
 * render a Cortex-aware block guarded by {@code {% if cortexMode %}}.
 *
 * <p>"In Cortex" is decided by the live client-tool registration —
 * the Cortex frontend pushes {@code cortex_*} tools tagged with the
 * {@code "cortex"} label on bind, and the registration drops when the
 * WebSocket closes (the user navigates back to plain chat or closes
 * the tab). So this check answers "is a Cortex client connected to
 * this session right now?".
 *
 * <p>The chat-bound document is <em>not</em> resolved here anymore. It
 * travels with each steer ({@code ProcessSteerRequest.boundDocumentId})
 * and is inlined per-turn by {@code ProcessSteerHandler} — per-turn LLM
 * context, not persisted session status.
 */
@Service
@RequiredArgsConstructor
public class CortexPromptResolver {

    private static final String CORTEX_LABEL = "cortex";

    private final ClientToolRegistry clientToolRegistry;

    public record CortexContext(boolean active) {

        public static CortexContext inactive() {
            return new CortexContext(false);
        }
    }

    public CortexContext resolve(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return CortexContext.inactive();

        List<ToolSpec> tools = clientToolRegistry.toolsFor(sessionId);
        boolean cortexClientConnected = tools.stream()
                .anyMatch(t -> t.getLabels() != null && t.getLabels().contains(CORTEX_LABEL));

        return new CortexContext(cortexClientConnected);
    }
}
