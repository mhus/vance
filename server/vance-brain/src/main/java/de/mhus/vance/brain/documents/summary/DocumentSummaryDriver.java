package de.mhus.vance.brain.documents.summary;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Generates a schema-validated {@code summary} + {@code tags} for one
 * dirty document via {@link LightLlmService} and writes the result
 * back through {@link DocumentService#writeSummary}.
 *
 * <p>One driver per pod, called from {@link DocumentSummaryScheduler}.
 * Stateless — all coordination state lives on {@link DocumentDocument}
 * ({@code summaryDirty}, {@code claimedBy}, {@code claimedAt}).
 *
 * <p>Single-shot LightLlm call via the bundled
 * {@code document-summary} recipe (config profile,
 * {@code internal: true}). Tenants override the recipe to swap the
 * model or change the tag policy without touching Java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSummaryDriver {

    /** Recipe name resolved out of the bundled cascade. */
    public static final String RECIPE_NAME = "document-summary";

    /** Reply field names — match the {@link #SUMMARY_SCHEMA}. */
    static final String FIELD_SUMMARY = "summary";
    static final String FIELD_TAGS = "tags";

    /** Caller-side schema enforced on the LightLlm reply. */
    static final Map<String, Object> SUMMARY_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    FIELD_SUMMARY, Map.of(
                            "type", "string",
                            "description",
                            "1-3 sentence summary of the document content."),
                    FIELD_TAGS, Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                            "3-8 topical tags, lowercase, single-word or hyphenated.")),
            "required", List.of(FIELD_SUMMARY, FIELD_TAGS));

    private final LightLlmService lightLlm;
    private final DocumentService documentService;

    @Value("${vance.autoSummary.maxContentBytes:200000}")
    private int maxContentBytes;

    /**
     * Process one claimed document. On success: persists summary + tags
     * and clears {@code summaryDirty}. On failure: throws — the
     * scheduler caller is expected to call
     * {@link DocumentService#releaseClaim} so the next tick re-tries.
     */
    public void run(ProjectDocument project, DocumentDocument doc) {
        Map<String, Object> raw;
        try {
            raw = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(RECIPE_NAME)
                    .userPrompt(doc.getPath())
                    .pebbleVars(buildPebbleVars(doc))
                    .schema(SUMMARY_SCHEMA)
                    .tenantId(project.getTenantId())
                    .projectId(project.getName())
                    .build());
        } catch (SchemaValidationException e) {
            throw new RuntimeException(
                    "Auto-summary schema budget exhausted for doc='"
                            + doc.getId() + "' path='" + doc.getPath()
                            + "' attempts=" + e.getAttempts(), e);
        } catch (LightLlmException e) {
            throw new RuntimeException(
                    "Auto-summary LightLlm call failed for doc='"
                            + doc.getId() + "' path='" + doc.getPath()
                            + "': " + e.getMessage(), e);
        }

        Result parsed = parseReply(raw, doc);
        documentService.writeSummary(doc.getId(), parsed.summary(), parsed.tags());
        // Re-mark dirty for the project-RAG indexer so the new summary
        // lands as a kind=summary chunk in the _documents RAG on the
        // next tick. No-op if the document is RAG-ineligible — the
        // indexer's filter re-check skips it cleanly.
        documentService.markRagDirty(doc.getId());
        log.info("Auto-summary written tenant='{}' project='{}' doc='{}' tags={}",
                project.getTenantId(), project.getName(), doc.getId(), parsed.tags().size());
    }

    // ──────────────────── Pebble vars ────────────────────

    Map<String, Object> buildPebbleVars(DocumentDocument doc) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("path", doc.getPath() == null ? "" : doc.getPath());
        if (doc.getTitle() != null && !doc.getTitle().isBlank()) {
            ctx.put("title", doc.getTitle());
        }
        ctx.put("content", readContentClipped(doc));
        return ctx;
    }

    private String readContentClipped(DocumentDocument doc) {
        String content = readContent(doc);
        if (content.getBytes(StandardCharsets.UTF_8).length > maxContentBytes) {
            return truncate(content, maxContentBytes);
        }
        return content;
    }

    private String readContent(DocumentDocument doc) {
        if (documentService.readContent(doc) != null) {
            return documentService.readContent(doc);
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

    static String truncate(String content, int maxBytes) {
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

    // ──────────────────── Reply parsing ────────────────────

    static Result parseReply(Map<String, Object> raw, DocumentDocument doc) {
        Object summaryRaw = raw.get(FIELD_SUMMARY);
        if (!(summaryRaw instanceof String summary) || summary.isBlank()) {
            throw new RuntimeException(
                    "Auto-summary reply for doc='" + doc.getId()
                            + "' missing or blank 'summary'");
        }
        List<String> tags = new ArrayList<>();
        Object tagsRaw = raw.get(FIELD_TAGS);
        if (tagsRaw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    tags.add(s);
                }
            }
        }
        return new Result(summary, tags);
    }

    /** Parsed LightLlm result payload. */
    public record Result(String summary, List<String> tags) {}
}
