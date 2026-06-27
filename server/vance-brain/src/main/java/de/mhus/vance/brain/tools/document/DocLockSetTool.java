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
 * Empty array clears the lock. Normalised server-side: when {@code USER}
 * or {@code KIT} is in the input, {@code AI} is auto-added.
 *
 * <p>When the call <em>removes</em> {@code KIT} (i.e. the previous set
 * contained {@code KIT} but the new one does not), a {@code reason} is
 * required — KIT-removal kills the user's protection against
 * auto-updates and should be a deliberate verbalisation, not a reflex.
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
                                    + "Plausibility rule: if USER or KIT is set, AI is auto-added."),
                    "reason", Map.of(
                            "type", "string",
                            "description",
                            "Required when removing KIT from the existing set. Free-text "
                                    + "justification of why the document should now be "
                                    + "subject to Kit-Apply auto-updates again.")),
            "required", List.of("documentId", "lockedFor"));

    private final DocumentService documentService;

    public DocLockSetTool(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override public String name() { return "document_lock_set"; }

    @Override
    public String description() {
        return "Set the soft document-lock on a document outright. lockedFor "
                + "lists the writer roles to block: AI (LLM writes), USER "
                + "(manual user writes), KIT (Kit-Apply content updates). "
                + "Empty array clears the lock. Removing KIT from an existing "
                + "lock requires a reason.";
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

        Set<WriterRole> existing = doc.getLockedFor() == null
                ? EnumSet.noneOf(WriterRole.class)
                : EnumSet.copyOf(doc.getLockedFor());
        Set<WriterRole> normalized = DocumentService.normalizeLockedFor(requested);

        boolean removingKit = existing.contains(WriterRole.KIT)
                && !normalized.contains(WriterRole.KIT);
        String reason = paramString(params, "reason");
        if (removingKit && reason == null) {
            throw new ToolException(
                    "Removing KIT from the lock requires a 'reason' — the "
                            + "document will be eligible for Kit-Apply auto-updates again.");
        }

        DocumentDocument saved = documentService.setLockedFor(documentId, normalized);
        log.info("DocLockSetTool tenant='{}' id='{}' before={} after={} reason='{}'",
                ctx.tenantId(), documentId, existing, normalized, reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", documentId);
        out.put("path", saved.getPath());
        out.put("lockedFor", normalized.stream().sorted().map(Enum::name).toList());
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
