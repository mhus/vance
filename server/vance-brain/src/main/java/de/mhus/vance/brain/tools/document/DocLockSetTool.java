package de.mhus.vance.brain.tools.document;

import de.mhus.vance.api.documents.WriterRole;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Replace the soft-lock {@code lockedFor} set on a document outright.
 * Empty array clears the lock. The three writer-roles are independently
 * selectable — no auto-add, no implicit dependencies.
 *
 * <p>See {@code planning/document-lock-level.md} §3.3.
 */
@Component
@Slf4j
public class DocLockSetTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "documentId", Map.of(
                            "type", "string",
                            "description", "Document id (use the id from `doc_find` / `doc_list`)."),
                    "lockedFor", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string", "enum", List.of("AI", "USER", "KIT")),
                            "description",
                            "Writer roles to block. Empty array clears the lock. "
                                    + "Each role is independently selectable.")),
            "required", List.of("documentId", "lockedFor"));

    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public DocLockSetTool(DocumentService documentService,
            de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    @Override public String name() { return "document_lock_set"; }

    @Override
    public String description() {
        return "Set the soft document-lock on a document outright. lockedFor "
                + "lists the writer roles to block: AI (LLM writes), USER "
                + "(manual user writes), KIT (Kit-Apply content updates). "
                + "Empty array clears the lock. The three roles are "
                + "independently selectable — no auto-add, no implicit rules.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("write", "document", "lock");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String documentId = paramString(params, "documentId");
        if (documentId == null) throw new ToolException("documentId is required");
        Set<WriterRole> requested = parseRoles(params == null ? null : params.get("lockedFor"));

        DocumentDocument doc = documentService.findById(documentId)
                .orElseThrow(() -> new ToolException(
                        "Unknown document id '" + documentId + "'"));
        if (!ctx.tenantId().equals(doc.getTenantId())) {
            throw new ToolException("Unknown document id '" + documentId + "'");
        }

        DocumentDocument saved = documentService.setLockedFor(documentId, requested,
                contextFactory.writeActor(ctx.tenantId(), ctx.userId(), doc.getPath()));
        log.info("DocLockSetTool tenant='{}' id='{}' lockedFor={}",
                ctx.tenantId(), documentId, requested);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", documentId);
        out.put("path", saved.getPath());
        out.put("lockedFor", requested.stream().sorted().map(Enum::name).toList());
        return out;
    }

    private static Set<WriterRole> parseRoles(@Nullable Object raw) {
        EnumSet<WriterRole> out = EnumSet.noneOf(WriterRole.class);
        if (raw == null) return out;
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    try {
                        out.add(WriterRole.valueOf(s.trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        throw new ToolException("Unknown WriterRole '" + s
                                + "' — expected one of AI, USER, KIT");
                    }
                }
            }
            return out;
        }
        throw new ToolException("lockedFor must be an array of role names (AI/USER/KIT)");
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
