package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Create a single card in a kanban suite. Writes one
 * {@code kind: card} Markdown file under
 * {@code <folder>/<column>/<slug>.md}. Doesn't rebuild the board —
 * the caller batches multiple creates and runs {@code app_rebuild}
 * once at the end.
 */
@Component
@Slf4j
public class KanbanCardCreateTool implements Tool {

    private static final String MD_MIME = "text/markdown";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Kanban app folder (contains _app.yaml)."));
                put("column", Map.of("type", "string",
                        "description", "Target column name. Becomes the "
                                + "sub-folder under `folder`. Defaults "
                                + "to 'backlog' when omitted."));
                put("title", Map.of("type", "string",
                        "description", "Card title. Required."));
                put("priority", Map.of("type", "string"));
                put("assignee", Map.of("type", "string"));
                put("labels", Map.of("type", "array",
                        "items", Map.of("type", "string")));
                put("dueDate", Map.of("type", "string",
                        "description", "ISO date e.g. 2026-07-15."));
                put("estimate", Map.of("type", "number"));
                put("blocked", Map.of("type", "boolean"));
                put("body", Map.of("type", "string",
                        "description", "Markdown body."));
                put("filename", Map.of("type", "string",
                        "description", "Optional explicit filename "
                                + "(without extension). Defaults to "
                                + "slugged title."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Replace existing card at that path. "
                                + "Default false — fails if present."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "title"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public KanbanCardCreateTool(EddieContext eddieContext,
                                DocumentService documentService,
                                SecurityContextFactory contextFactory) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    @Override public String name() { return "kanban_card_create"; }

    @Override
    public String description() {
        return "Create a single card under <folder>/<column>/<slug>.md. "
                + "Use kanban_app_create for the initial board setup "
                + "(it accepts a `cards` array for the full plan at "
                + "once). Use this only when adding a card to an "
                + "existing board after-the-fact. Doesn't rebuild "
                + "automatically — call app_rebuild when done.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "kanban", "card");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String title = paramString(params, "title");
        if (title == null) throw new ToolException("title is required");
        String column = paramString(params, "column");
        if (column == null) column = KanbanFolderReader.DEFAULT_COLUMN;
        column = sanitiseName(column);

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String tenantId = ctx.tenantId();
        String projectName = project.getName();

        String slug = paramString(params, "filename");
        if (slug == null) slug = sanitiseName(title);
        String path = normaliseFolder(folder) + "/" + column + "/" + slug + ".md";

        boolean overwrite = paramBoolean(params, "overwrite");
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenantId, projectName, path);
        if (existing.isPresent() && !overwrite) {
            throw new ToolException(
                    "Card already exists at '" + path + "'. Pass "
                            + "overwrite=true or pick a different "
                            + "filename to replace it.");
        }

        CardDocument card = new CardDocument(
                "card", title,
                paramString(params, "priority"),
                paramString(params, "assignee"),
                paramStringList(params, "labels"),
                paramString(params, "dueDate"),
                paramDouble(params, "estimate"),
                paramBoolean(params, "blocked"),
                paramString(params, "body") != null ? paramString(params, "body") : "",
                new LinkedHashMap<>());
        String body = CardCodec.serialize(card, MD_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title, List.of("card"),
                    body, null, null, null, null, MD_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), path));
        } else {
            try (ByteArrayInputStream in = new ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        tenantId, projectName, path, title,
                        List.of("card"), MD_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), path));
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write card '" + path + "': " + e.getMessage());
            }
        }

        log.info("KanbanCardCreateTool tenant='{}' folder='{}' column='{}' path='{}'",
                tenantId, folder, column, stored.getPath());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", stored.getPath());
        result.put("column", column);
        result.put("title", title);
        result.put("nextStep", "Card created. Call "
                + "`app_rebuild('" + normaliseFolder(folder) + "')` "
                + "to refresh _board.md + _stats.yaml.");
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    private static String sanitiseName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "card" : sb.toString();
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private static @Nullable Double paramDouble(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static List<String> paramStringList(@Nullable Map<String, Object> params, String key) {
        if (params == null) return List.of();
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        return List.of();
    }
}
