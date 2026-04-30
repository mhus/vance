package de.mhus.vance.brain.memory;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds a Markdown context block that conversation engines append to
 * their system message. Two layers contribute:
 *
 * <ol>
 *   <li>{@code memory.*} settings, resolved via the cascade
 *       tenant → project → think-process. Rendered as
 *       {@code key: value} lines under a {@code ## Project Memory}
 *       heading.</li>
 *   <li>An "agent" Markdown document, looked up via
 *       {@link DocumentService#lookupCascade(String, String, String)}
 *       (project → {@code _vance} → classpath default). Default path
 *       {@code agent.md}; the recipe param {@code agentDocument}
 *       overrides the path or disables the lookup entirely with
 *       {@code ""}.</li>
 * </ol>
 *
 * <p>Block returns {@code null} when both layers are empty — engines
 * skip the append in that case.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryContextLoader {

    /** Settings under this prefix are surfaced into the prompt context. */
    public static final String MEMORY_PREFIX = "memory.";

    /** Recipe param key for overriding the default agent-document path. */
    public static final String AGENT_DOC_PARAM = "agentDocument";

    /** Default agent-document path looked up via the document cascade. */
    public static final String DEFAULT_AGENT_DOC_PATH = "agent.md";

    /**
     * Recipe param flag (typically set in a profile-block) that opts in
     * to splicing the client-uploaded agent doc into the memory block.
     * Off by default — only foot-style profiles turn it on.
     */
    public static final String USE_CLIENT_AGENT_DOC_PARAM = "useClientAgentDoc";

    private static final String MEMORY_HEADING = "## Project Memory";

    private final SettingService settingService;
    private final SessionService sessionService;
    private final DocumentService documentService;

    /**
     * Returns the Markdown block to append to the system prompt, or
     * {@code null} when neither {@code memory.*} settings nor the agent
     * document yield any content.
     */
    public @Nullable String composeBlock(ThinkProcessDocument process) {
        if (process == null || process.getTenantId() == null
                || process.getTenantId().isBlank()) {
            return null;
        }
        String projectId = resolveProjectId(process);
        StringBuilder sb = new StringBuilder();

        appendMemorySettings(sb, process, projectId);
        appendAgentDocument(sb, process, projectId);
        appendClientAgentDoc(sb, process);

        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendMemorySettings(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String projectId) {
        Map<String, String> entries = settingService.findByPrefixCascade(
                process.getTenantId(),
                projectId,
                process.getId(),
                MEMORY_PREFIX);
        if (entries.isEmpty()) return;
        sb.append(MEMORY_HEADING).append('\n');
        entries.forEach((key, value) -> {
            String stripped = key.startsWith(MEMORY_PREFIX)
                    ? key.substring(MEMORY_PREFIX.length())
                    : key;
            if (stripped.isBlank()) return;
            sb.append(stripped).append(": ")
                    .append(value == null ? "" : value).append('\n');
        });
    }

    private void appendAgentDocument(
            StringBuilder sb,
            ThinkProcessDocument process,
            @Nullable String projectId) {
        if (projectId == null) return;
        @Nullable String path = resolveAgentPath(process);
        if (path == null) return;
        documentService.lookupCascade(process.getTenantId(), projectId, path)
                .ifPresent(result -> {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(agentHeading(result)).append('\n');
                    String content = result.content();
                    if (content != null && !content.isBlank()) {
                        sb.append(content.trim()).append('\n');
                    }
                });
    }

    /**
     * Splices the session's {@code clientAgentDoc} (uploaded by the
     * foot client right after bind) into the prompt — but only when
     * the active recipe's profile-block has opted in via
     * {@code params.useClientAgentDoc=true}. Quiet no-op otherwise:
     * for web/mobile the upload doesn't happen, and for foot recipes
     * that don't ask for it the flag is absent.
     */
    private void appendClientAgentDoc(StringBuilder sb, ThinkProcessDocument process) {
        if (!isClientAgentDocEnabled(process)) return;
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;
        sessionService.findBySessionId(sessionId).ifPresent(session -> {
            String content = session.getClientAgentDoc();
            if (content == null || content.isBlank()) return;
            if (sb.length() > 0) sb.append('\n');
            String path = session.getClientAgentDocPath();
            sb.append("## Agent Notes (from client: ")
                    .append(path == null || path.isBlank() ? "agent.md" : path)
                    .append(")\n")
                    .append(content.trim()).append('\n');
        });
    }

    private static boolean isClientAgentDocEnabled(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return false;
        Object raw = params.get(USE_CLIENT_AGENT_DOC_PARAM);
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase();
            return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
        }
        return false;
    }

    /**
     * Resolves the agent-document path:
     * <ul>
     *   <li>recipe param {@code agentDocument} not set → use the default</li>
     *   <li>set to a non-blank string → use that string verbatim</li>
     *   <li>set to {@code null} or {@code ""} → disabled, return {@code null}</li>
     * </ul>
     */
    private @Nullable String resolveAgentPath(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null || !params.containsKey(AGENT_DOC_PARAM)) {
            return DEFAULT_AGENT_DOC_PATH;
        }
        Object raw = params.get(AGENT_DOC_PARAM);
        if (raw == null) return null;
        String v = raw.toString().trim();
        return v.isEmpty() ? null : v;
    }

    private static String agentHeading(LookupResult result) {
        String path = result.path();
        return switch (result.source()) {
            case PROJECT -> "## Agent Notes (" + path + ")";
            case VANCE -> "## Agent Notes (from _vance: " + path + ")";
            case RESOURCE -> "## Agent Notes (system default: " + path + ")";
        };
    }

    private @Nullable String resolveProjectId(ThinkProcessDocument process) {
        String sessionId = process.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
    }
}
