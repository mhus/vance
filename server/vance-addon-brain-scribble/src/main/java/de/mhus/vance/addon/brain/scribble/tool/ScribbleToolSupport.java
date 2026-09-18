package de.mhus.vance.addon.brain.scribble.tool;

import de.mhus.vance.addon.brain.scribble.ScribbleService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Shared helpers for the {@code scribble_*} tool family — project
 * resolution, document lookup by path, param coercion.
 */
final class ScribbleToolSupport {

    private ScribbleToolSupport() {}

    record Resolved(String tenantId, String projectName, DocumentDocument doc) {}

    static Resolved resolveByPath(
            EddieContext eddieContext,
            DocumentService documentService,
            Map<String, Object> params,
            ToolInvocationContext ctx) {
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String path = paramString(params, "path");
        if (path == null) throw new ToolException("path is required");
        Optional<DocumentDocument> existing = documentService.findByPath(ctx.tenantId(), project.getName(), path);
        DocumentDocument doc = existing.orElseThrow(() -> new ToolException("No scribble at '" + path + "'."));
        if (!ScribbleService.KIND.equals(doc.getKind())) {
            throw new ToolException("Document '" + path + "' is not a scribble (kind=" + doc.getKind() + ").");
        }
        return new Resolved(ctx.tenantId(), project.getName(), doc);
    }

    static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
