package de.mhus.vance.addon.brain.finance.tool;

import de.mhus.vance.addon.brain.finance.FinanceTreeCodec;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Shared helpers for the {@code finance_*} tool family. */
final class FinanceToolSupport {

    private FinanceToolSupport() {}

    record Resolved(String tenantId, String projectName, DocumentDocument doc) {}

    static Resolved resolveByPath(EddieContext eddieContext,
                                  DocumentService documentService,
                                  Map<String, Object> params,
                                  ToolInvocationContext ctx) {
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), project.getName(), path);
        DocumentDocument doc = existing.orElseThrow(() ->
                new ToolException("No finance-tree at '" + path + "'."));
        if (!FinanceTreeCodec.KIND.equals(doc.getKind())) {
            throw new ToolException(
                    "Document '" + path + "' is not a finance-tree (kind=" + doc.getKind() + ").");
        }
        return new Resolved(ctx.tenantId(), project.getName(), doc);
    }

    static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    static Map<String, Object> paramMap(@Nullable Map<String, Object> params, String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (params == null) return out;
        if (params.get(key) instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    static List<Map<String, Object>> paramMapList(@Nullable Map<String, Object> params, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (params == null) return out;
        if (params.get(key) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) item.put(e.getKey().toString(), e.getValue());
                    }
                    out.add(item);
                }
            }
        }
        return out;
    }
}
