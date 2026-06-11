package de.mhus.vance.brain.tools.client;

import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.Optional;
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
 * this session right now?" — not "has Cortex ever been used here?",
 * which would falsely fire from the persisted
 * {@code SessionDocument.chatBoundDocumentId} after the user left.
 */
@Service
@RequiredArgsConstructor
public class CortexPromptResolver {

    private static final String CORTEX_LABEL = "cortex";

    private final ClientToolRegistry clientToolRegistry;
    private final SessionService sessionService;
    private final DocumentService documentService;

    public record CortexContext(
            boolean active,
            @Nullable String boundDocPath,
            @Nullable String boundDocMime) {

        public static CortexContext inactive() {
            return new CortexContext(false, null, null);
        }
    }

    public CortexContext resolve(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return CortexContext.inactive();

        List<ToolSpec> tools = clientToolRegistry.toolsFor(sessionId);
        boolean cortexClientConnected = tools.stream()
                .anyMatch(t -> t.getLabels() != null && t.getLabels().contains(CORTEX_LABEL));
        if (!cortexClientConnected) return CortexContext.inactive();

        Optional<SessionDocument> session = sessionService.findBySessionId(sessionId);
        if (session.isEmpty()) return CortexContext.inactive();

        String docId = session.get().getChatBoundDocumentId();
        if (docId == null || docId.isBlank()) {
            // Cortex is open but no document is chat-bound yet — the
            // agent should still know it has cortex_* tools available,
            // just no path to report.
            return new CortexContext(true, null, null);
        }

        Optional<DocumentDocument> doc = documentService.findById(docId);
        if (doc.isEmpty()) return new CortexContext(true, null, null);

        return new CortexContext(true, doc.get().getPath(), doc.get().getMimeType());
    }
}
