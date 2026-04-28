package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drops a new item into a target user's inbox. Used by engines as a
 * side-effect of their normal work — "Worker erstellt Analyse, legt
 * sie road-runner in die Inbox" out of the box.
 *
 * <p>The {@code originatorUserId} is the calling session's user;
 * {@code originProcessId} is the calling process. Both populate the
 * audit trail and (for asks) the answer-routing target.
 *
 * <p>v1 caveats:
 * <ul>
 *   <li>4 item types implemented: {@code APPROVAL}, {@code DECISION},
 *       {@code FEEDBACK}, {@code OUTPUT_TEXT}. Others accepted by the
 *       schema but UI/validator support follows in later iterations.</li>
 *   <li>{@code targetUserId} is taken at face value — no permission
 *       check that the caller may post to that user. v2: tenant-level
 *       authorization rules.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxPostTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "targetUserId", Map.of(
                            "type", "string",
                            "description", "Recipient user-id (assignedToUserId)."),
                    "type", Map.of(
                            "type", "string",
                            "enum", List.of(
                                    "APPROVAL", "DECISION", "FEEDBACK",
                                    "ORDERING", "STRUCTURE_EDIT",
                                    "OUTPUT_TEXT", "OUTPUT_IMAGE", "OUTPUT_DOCUMENT"),
                            "description", "Item type. Asks (APPROVAL/DECISION/...) wait "
                                    + "for an answer; Outputs (OUTPUT_*) are informational."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Headline for the inbox list."),
                    "body", Map.of(
                            "type", "string",
                            "description", "Optional Markdown long-form description."),
                    "criticality", Map.of(
                            "type", "string",
                            "enum", List.of("LOW", "NORMAL", "CRITICAL"),
                            "description", "Drives auto-answer + notification routing. "
                                    + "Default NORMAL. LOW with payload.default = "
                                    + "auto-answered immediately."),
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "Free-form tags for filtering."),
                    "payload", Map.of(
                            "type", "object",
                            "description", "Type-specific structured payload "
                                    + "(options for DECISION, schema for STRUCTURE_EDIT, "
                                    + "url for OUTPUT_IMAGE, default for LOW auto-answer, ...).",
                            "additionalProperties", true),
                    "documentRef", Map.of(
                            "type", "object",
                            "description", "Optional reference to a document the "
                                    + "item is about. Validated against DocumentService; "
                                    + "the resolved ref lands in payload.documentRef "
                                    + "as {projectId, documentId, path, title}. "
                                    + "Identify the doc by id or by (projectId, path).",
                            "properties", Map.of(
                                    "id", Map.of("type", "string"),
                                    "projectId", Map.of("type", "string"),
                                    "path", Map.of("type", "string")))),
            "required", List.of("targetUserId", "type", "title"));

    private final InboxItemService inboxItemService;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "inbox_post";
    }

    @Override
    public String description() {
        return "Post an item to a user's inbox — analyses, "
                + "decision-asks, feedback-asks, etc. Asks block "
                + "the calling process if the caller waits for an answer.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        if (tenantId == null) {
            throw new ToolException("inbox_post requires a tenant scope");
        }
        String targetUserId = stringOrThrow(params, "targetUserId");
        InboxItemType type = parseType(stringOrThrow(params, "type"));
        String title = stringOrThrow(params, "title");
        String body = optString(params, "body");
        Criticality criticality = parseCriticality(optString(params, "criticality"));
        List<String> tags = optStringList(params, "tags");
        Map<String, Object> payload = optMap(params, "payload");
        Map<String, Object> resolvedDocRef = resolveDocumentRef(params, ctx);
        if (resolvedDocRef != null) {
            if (payload == null) payload = new LinkedHashMap<>();
            payload.put("documentRef", resolvedDocRef);
        }

        InboxItemDocument toCreate = InboxItemDocument.builder()
                .tenantId(tenantId)
                .originatorUserId(ctx.userId() == null ? "system" : ctx.userId())
                .assignedToUserId(targetUserId)
                .originProcessId(ctx.processId())
                .originSessionId(ctx.sessionId())
                .type(type)
                .criticality(criticality)
                .tags(tags)
                .title(title)
                .body(body)
                .payload(payload == null ? new LinkedHashMap<>() : payload)
                .requiresAction(isAsk(type))
                .build();

        InboxItemDocument saved = inboxItemService.create(toCreate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemId", saved.getId());
        out.put("status", saved.getStatus().name());
        out.put("type", saved.getType().name());
        out.put("criticality", saved.getCriticality().name());
        out.put("requiresAction", saved.isRequiresAction());
        if (saved.getAnswer() != null) {
            // Auto-answered (LOW with default) — surface the verdict.
            out.put("autoAnswered", true);
            out.put("resolvedBy", saved.getResolvedBy() == null
                    ? null : saved.getResolvedBy().name());
        }
        return out;
    }

    private static boolean isAsk(InboxItemType t) {
        return switch (t) {
            case APPROVAL, DECISION, FEEDBACK, ORDERING, STRUCTURE_EDIT -> true;
            case OUTPUT_TEXT, OUTPUT_IMAGE, OUTPUT_DOCUMENT -> false;
        };
    }

    /**
     * Looks up the document referenced by the {@code documentRef} param
     * and returns a normalized ref map suitable for {@code payload.documentRef}.
     * Returns {@code null} when no documentRef was passed.
     */
    private @org.jspecify.annotations.Nullable Map<String, Object> resolveDocumentRef(
            Map<String, Object> params, ToolInvocationContext ctx) {
        if (params == null) return null;
        Object rawRef = params.get("documentRef");
        if (!(rawRef instanceof Map<?, ?> refMap) || refMap.isEmpty()) {
            return null;
        }
        Object rawId = refMap.get("id");
        Object rawProjectId = refMap.get("projectId");
        Object rawPath = refMap.get("path");
        String id = rawId instanceof String s && !s.isBlank() ? s.trim() : null;
        String projectId = rawProjectId instanceof String s && !s.isBlank() ? s.trim() : null;
        String path = rawPath instanceof String s && !s.isBlank() ? s.trim() : null;

        DocumentDocument doc;
        if (id != null) {
            doc = documentService.findById(id)
                    .orElseThrow(() -> new ToolException(
                            "documentRef.id '" + id + "' not found"));
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException(
                        "documentRef.id '" + id + "' not in your tenant");
            }
        } else if (projectId != null && path != null) {
            doc = documentService.findByPath(ctx.tenantId(), projectId, path)
                    .orElseThrow(() -> new ToolException(
                            "documentRef '" + projectId + "/" + path
                                    + "' not found"));
        } else {
            throw new ToolException(
                    "documentRef requires either 'id' or both "
                            + "'projectId' and 'path'");
        }

        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("documentId", doc.getId());
        ref.put("projectId", doc.getProjectId());
        ref.put("path", doc.getPath());
        if (doc.getTitle() != null) ref.put("title", doc.getTitle());
        if (doc.getMimeType() != null) ref.put("mimeType", doc.getMimeType());
        return ref;
    }

    private static InboxItemType parseType(String raw) {
        try {
            return InboxItemType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown inbox item type '" + raw + "'");
        }
    }

    private static Criticality parseCriticality(String raw) {
        if (raw == null) return Criticality.NORMAL;
        try {
            return Criticality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown criticality '" + raw + "'");
        }
    }

    // ──────────────────── helpers ────────────────────

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> optStringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof List<?> list) {
            List<String> out = new java.util.ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s);
            }
            return out;
        }
        return new java.util.ArrayList<>();
    }

    private static Map<String, Object> optMap(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }
}
