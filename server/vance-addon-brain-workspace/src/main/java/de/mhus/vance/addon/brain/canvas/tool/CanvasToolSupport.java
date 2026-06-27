package de.mhus.vance.addon.brain.canvas.tool;

import de.mhus.vance.addon.brain.canvas.CanvasService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Shared helpers for the {@code canvas_*} tool family — project
 * resolution, document lookup by path, param coercion.
 */
final class CanvasToolSupport {

    private CanvasToolSupport() {}

    static record Resolved(String tenantId, String projectName,
                           DocumentDocument doc) {}

    static Resolved resolveByPath(EddieContext eddieContext,
                                  DocumentService documentService,
                                  Map<String, Object> params,
                                  ToolInvocationContext ctx) {
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), project.getName(), path);
        DocumentDocument doc = existing.orElseThrow(() ->
                new ToolException("No canvas at '" + path + "'."));
        if (!CanvasService.KIND.equals(doc.getKind())) {
            throw new ToolException(
                    "Document '" + path + "' is not a canvas (kind="
                            + doc.getKind() + ").");
        }
        return new Resolved(ctx.tenantId(), project.getName(), doc);
    }

    static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    @SuppressWarnings("unchecked")
    static @Nullable Map<String, Object> paramMap(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
            }
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> paramMapList(@Nullable Map<String, Object> params, String key) {
        if (params == null) return List.of();
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) mm.put(e.getKey().toString(), e.getValue());
                    }
                    out.add(mm);
                }
            }
            return out;
        }
        return List.of();
    }

    static int paramInt(@Nullable Map<String, Object> params, String key, int fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* skipped */ }
        }
        return fallback;
    }
}
