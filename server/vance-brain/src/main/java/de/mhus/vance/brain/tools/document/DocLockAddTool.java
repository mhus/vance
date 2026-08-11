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
 * Add a single role to the soft-lock {@code lockedFor} set. No-op when
 * the role is already in the set.
 *
 * <p>See {@code planning/document-lock-level.md} §3.3.
 */
@Component
@Slf4j
public class DocLockAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("role"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new java.util.LinkedHashMap<>(de.mhus.vance.brain.tools.kinds.KindToolSupport.documentSelectorPropertiesWithIdAlias());
        p.put("role", Map.of(
                "type", "string",
                "enum", List.of("AI", "USER", "KIT"),
                "description", "Writer role to block."));
        return p;
    }

    private final DocumentService documentService;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private final de.mhus.vance.brain.tools.kinds.KindToolSupport support;

    public DocLockAddTool(DocumentService documentService,
            de.mhus.vance.brain.permission.SecurityContextFactory contextFactory,
            de.mhus.vance.brain.tools.kinds.KindToolSupport support) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
        this.support = support;
    }

    @Override public String name() { return "doc_lock_add"; }

    @Override
    public String description() {
        return "Add a writer role to the soft document-lock. Use AI to "
                + "block LLM writes, USER for manual user writes, KIT for "
                + "Kit-Apply content updates. The three roles are "
                + "independent.";
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
        WriterRole role = parseRole(params == null ? null : params.get("role"));
        // Standard doc selector (path | id, plus the legacy documentId alias).
        // Resolution, tenant scoping and the READ check live in loadDocument.
        DocumentDocument doc = support.loadDocument(
                de.mhus.vance.brain.tools.kinds.KindToolSupport.withIdAlias(params), ctx);
        String documentId = doc.getId();

        EnumSet<WriterRole> next = doc.getLockedFor() == null
                ? EnumSet.noneOf(WriterRole.class)
                : EnumSet.copyOf(doc.getLockedFor());
        next.add(role);

        DocumentDocument saved = documentService.setLockedFor(documentId, next,
                contextFactory.writeActor(ctx.tenantId(), ctx.userId(), doc.getPath()));
        log.info("DocLockAddTool tenant='{}' id='{}' added={} now={}",
                ctx.tenantId(), documentId, role, next);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", documentId);
        out.put("path", saved.getPath());
        out.put("lockedFor", next.stream().sorted().map(Enum::name).toList());
        return out;
    }

    private static WriterRole parseRole(@Nullable Object raw) {
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return WriterRole.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ToolException("Unknown WriterRole '" + s
                        + "' — expected one of AI, USER, KIT");
            }
        }
        throw new ToolException("role is required (AI, USER, or KIT)");
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
