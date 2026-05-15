package de.mhus.vance.brain.documents.summary;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Synchronously spawns a Jeltz process per dirty document and writes
 * the schema-validated {@code summary} + {@code tags} back through
 * {@link DocumentService#writeSummary}.
 *
 * <p>One driver per pod, called from {@link DocumentSummaryScheduler}.
 * Stateless — all coordination state lives on {@link DocumentDocument}
 * ({@code summaryDirty}, {@code claimedBy}, {@code claimedAt}).
 *
 * <p>Per project a dedicated system session {@value #SYSTEM_SESSION_NAME}
 * is reused across all summary runs — keeps the chat history of all
 * auto-summary processes co-located and avoids a session-create per
 * tick.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSummaryDriver {

    /** Display name of the per-project system session that owns all summary processes. */
    public static final String SYSTEM_SESSION_NAME = "_system_summary";

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "jeltz";

    /** Jeltz spawn-param keys (see {@code specification/jeltz-engine.md} §3.1). */
    public static final String PARAM_PROMPT = "prompt";
    public static final String PARAM_SCHEMA = "schema";

    /** {@code data}-field of the Jeltz wrapper, matches {@link #SUMMARY_SCHEMA}. */
    public static final String DATA_SUMMARY = "summary";
    public static final String DATA_TAGS = "tags";

    /** Caller-side schema enforced on the Jeltz reply. */
    public static final Map<String, Object> SUMMARY_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    DATA_SUMMARY, Map.of(
                            "type", "string",
                            "description",
                            "1-3 sentence summary of the document content."),
                    DATA_TAGS, Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                            "3-8 topical tags, lowercase, single-word or hyphenated.")),
            "required", List.of(DATA_SUMMARY, DATA_TAGS));

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;
    private final DocumentService documentService;
    private final ObjectMapper jsonMapper;

    @Value("${vance.autoSummary.maxContentBytes:200000}")
    private int maxContentBytes;

    /**
     * Process one claimed document. On success: persists summary + tags
     * and clears {@code summaryDirty}. On failure: throws — the
     * scheduler caller is expected to call
     * {@link DocumentService#releaseClaim} so the next tick re-tries.
     */
    public void run(ProjectDocument project, DocumentDocument doc) {
        SessionDocument session = resolveSystemSession(project);
        AppliedRecipe applied = applyRecipe(project, doc);

        ThinkProcessDocument child = createChildProcess(project, session, applied, doc);

        driveSynchronously(child);

        Result parsed = readResult(project.getTenantId(), session.getSessionId(), child.getId());
        documentService.writeSummary(doc.getId(), parsed.summary(), parsed.tags());
        // Re-mark dirty for the project-RAG indexer so the new summary
        // lands as a kind=summary chunk in the _documents RAG on the
        // next tick. No-op if the document is RAG-ineligible — the
        // indexer's filter re-check skips it cleanly.
        documentService.markRagDirty(doc.getId());
        log.info("Auto-summary written tenant='{}' project='{}' doc='{}' tags={}",
                project.getTenantId(), project.getName(), doc.getId(), parsed.tags().size());
    }

    // ──────────────────── Steps ────────────────────

    private SessionDocument resolveSystemSession(ProjectDocument project) {
        return sessionService.findSystemSession(
                        project.getTenantId(), project.getName(), SYSTEM_SESSION_NAME)
                .orElseGet(() -> createFreshSession(project));
    }

    private SessionDocument createFreshSession(ProjectDocument project) {
        SessionDocument created = sessionService.create(
                project.getTenantId(),
                /*userId*/ "system",
                project.getName(),
                SYSTEM_SESSION_NAME,
                Profiles.SCHEDULER,
                /*clientVersion*/ "auto-summary",
                /*clientName*/ null,
                /*system*/ true);
        sessionService.markBootstrapped(created.getSessionId());
        log.info("Auto-summary system-session created tenant='{}' project='{}' sessionId='{}'",
                project.getTenantId(), project.getName(), created.getSessionId());
        return created;
    }

    private AppliedRecipe applyRecipe(ProjectDocument project, DocumentDocument doc) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PARAM_PROMPT, buildPrompt(doc));
        params.put(PARAM_SCHEMA, SUMMARY_SCHEMA);
        return recipeResolver.apply(
                project.getTenantId(), project.getName(), RECIPE_NAME,
                /*connectionProfile*/ Profiles.SCHEDULER,
                params);
    }

    private ThinkProcessDocument createChildProcess(
            ProjectDocument project, SessionDocument session,
            AppliedRecipe applied, DocumentDocument doc) {
        String name = "summary-" + doc.getId() + "-" + Instant.now().toEpochMilli();
        return thinkProcessService.create(
                project.getTenantId(),
                project.getName(),
                session.getSessionId(),
                name,
                applied.engine(),
                /*thinkEngineVersion*/ null,
                /*title*/ "Auto-summary for " + doc.getPath(),
                /*goal*/ null,
                /*parentProcessId*/ null,
                applied.params(),
                applied.name(),
                applied.promptOverride(),
                applied.promptOverrideAppend(),
                applied.promptMode(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools(),
                applied.connectionProfile(),
                applied.defaultActiveSkills(),
                applied.allowedSkills() == null
                        ? null : new java.util.LinkedHashSet<>(applied.allowedSkills()));
    }

    private void driveSynchronously(ThinkProcessDocument child) {
        // Jeltz.start() runs the validator loop and closes DONE — no
        // separate steer/turn needed. The lane wrapper serialises
        // against any background ProcessEvent that might race for the
        // freshly-created process id.
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineService.start(child)).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Auto-summary interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Auto-summary turn failed child='" + child.getId()
                            + "': " + cause.getMessage(), cause);
        }
    }

    private Result readResult(String tenantId, String sessionId, String childId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, childId);
        ChatMessageDocument last = lastAssistant(history);
        if (last == null) {
            throw new RuntimeException("Jeltz produced no assistant message for child='" + childId + "'");
        }
        JsonNode wrapper;
        try {
            wrapper = jsonMapper.readTree(last.getContent());
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse Jeltz result JSON for child='" + childId + "': "
                            + e.getMessage(), e);
        }
        if (!wrapper.path("success").asBoolean(false)) {
            String error = wrapper.path("error").asText("unknown");
            String message = wrapper.path("message").asText("");
            throw new RuntimeException(
                    "Jeltz returned failure for child='" + childId + "': "
                            + error + (message.isBlank() ? "" : " — " + message));
        }
        JsonNode data = wrapper.path("data");
        String summary = data.path(DATA_SUMMARY).asText();
        List<String> tags = new ArrayList<>();
        JsonNode tagNode = data.path(DATA_TAGS);
        if (tagNode.isArray()) {
            for (JsonNode tag : tagNode) {
                String s = tag.asText();
                if (s != null && !s.isBlank()) tags.add(s);
            }
        }
        return new Result(summary, tags);
    }

    // ──────────────────── Helpers ────────────────────

    private String buildPrompt(DocumentDocument doc) {
        String content = readContent(doc);
        if (content.getBytes(StandardCharsets.UTF_8).length > maxContentBytes) {
            content = truncate(content, maxContentBytes);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Summarize the following document and propose topical tags.\n\n");
        sb.append("Path: ").append(doc.getPath()).append('\n');
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) {
            sb.append("Title: ").append(doc.getTitle()).append('\n');
        }
        sb.append("\n---\n\n").append(content);
        return sb.toString();
    }

    private String readContent(DocumentDocument doc) {
        if (doc.getInlineText() != null) {
            return doc.getInlineText();
        }
        // Storage-backed text — load through the service so the
        // inline-vs-storage branch stays in one place.
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read storage content for doc id='" + doc.getId()
                            + "' path='" + doc.getPath() + "'", e);
        }
    }

    private static String truncate(String content, int maxBytes) {
        // Keep the prompt cheap and well-defined — first half + last
        // half with a visible marker in between. Byte-based slicing on
        // a UTF-8 string risks splitting a code-point; convert to bytes
        // first, then back to string via the charset which silently
        // drops a trailing partial sequence.
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return content;
        int half = (maxBytes - 32) / 2;
        String head = new String(bytes, 0, half, StandardCharsets.UTF_8);
        String tail = new String(bytes, bytes.length - half, half, StandardCharsets.UTF_8);
        return head + "\n\n[... truncated ...]\n\n" + tail;
    }

    private static ChatMessageDocument lastAssistant(List<ChatMessageDocument> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT
                    && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m;
            }
        }
        return null;
    }

    /** Parsed Jeltz result payload. */
    public record Result(String summary, List<String> tags) {}
}
