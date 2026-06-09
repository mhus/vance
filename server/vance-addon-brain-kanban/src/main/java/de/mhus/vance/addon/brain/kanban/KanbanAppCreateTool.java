package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplication.ArtefactResult;
import de.mhus.vance.brain.applications.VanceApplication.CreateContext;
import de.mhus.vance.brain.applications.VanceApplication.CreateResult;
import de.mhus.vance.brain.applications.VanceApplication.RefreshContext;
import de.mhus.vance.brain.applications.VanceApplication.RefreshResult;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * One-shot bootstrap for a kanban-app folder. Writes the {@code _app.yaml}
 * manifest with the correct schema, persists every supplied card to its
 * target column folder, and auto-rebuilds {@code _board.md} +
 * {@code _stats.yaml} so the result already carries the artefact links.
 *
 * <p>Always preferred over hand-writing {@code _app.yaml} via
 * {@code doc_create} — the schema tripwires (kind, app, column
 * shape, sub-folder convention) trip up LLMs reliably.
 */
@Component
@Slf4j
public class KanbanAppCreateTool implements Tool {

    private static final Map<String, Object> COLUMN_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("name", Map.of("type", "string",
                        "description", "Column id — short, filesystem-safe "
                                + "(lowercase, alphanumeric, dashes). "
                                + "Becomes the sub-folder name."));
                put("title", Map.of("type", "string",
                        "description", "Display label. Defaults to the name."));
                put("color", Map.of("type", "string",
                        "description", "Palette name or CSS color."));
                put("order", Map.of("type", "integer",
                        "description", "Sort position on the board. "
                                + "Auto-assigned when missing."));
                put("wipLimit", Map.of("type", "integer",
                        "description", "Optional WIP limit. Exceeding it "
                                + "flags the column in _stats.yaml; "
                                + "wipEnforce=hard also blocks moves."));
            }},
            "required", List.of("name"));

    private static final Map<String, Object> CARD_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("title", Map.of("type", "string",
                        "description", "Card title. Required."));
                put("column", Map.of("type", "string",
                        "description", "Column name this card belongs to. "
                                + "Auto-creates the column if missing. "
                                + "Defaults to 'backlog'."));
                put("priority", Map.of("type", "string",
                        "description", "Free-form (low/med/high/critical). "
                                + "high/critical render as standouts."));
                put("assignee", Map.of("type", "string"));
                put("labels", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Tags. Use 'blocked' to flag a "
                                + "blocked card (or set blocked:true)."));
                put("dueDate", Map.of("type", "string",
                        "description", "ISO date, e.g. 2026-07-15."));
                put("estimate", Map.of("type", "number",
                        "description", "Story-point / hour estimate."));
                put("blocked", Map.of("type", "boolean"));
                put("body", Map.of("type", "string",
                        "description", "Markdown body — description, "
                                + "acceptance criteria (GFM checkboxes), "
                                + "notes. GFM checkboxes feed the "
                                + "subtasks progress stat."));
            }},
            "required", List.of("title"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Folder for the kanban app. "
                                + "Manifest lives at <folder>/_app.yaml."));
                put("title", Map.of("type", "string"));
                put("description", Map.of("type", "string"));
                put("columns", Map.of("type", "array",
                        "items", COLUMN_ITEM_SCHEMA,
                        "description", "Columns for the board. Each "
                                + "becomes a sub-folder. Order = render "
                                + "order. Columns referenced by cards "
                                + "but not listed are auto-added. "
                                + "ACCEPTS SHORTHAND: entries may be "
                                + "strings (column-name only) or objects "
                                + "({name, title?, color?, order?, wipLimit?})."));
                put("cards", Map.of("type", "array",
                        "items", CARD_ITEM_SCHEMA,
                        "description", "ONE-SHOT FORM. Pass cards here "
                                + "and the tool writes the manifest, "
                                + "creates one .md file per card in its "
                                + "column folder, AND auto-runs "
                                + "app_rebuild — single call. The "
                                + "result's `artefacts` array carries "
                                + "the board + stats paths to embed in "
                                + "chat."));
                put("boardStyle", Map.of("type", "string",
                        "description", "'mermaid' (default — Kanban diagram) "
                                + "or 'table' (Markdown table)."));
                put("wipEnforce", Map.of("type", "string",
                        "description", "'soft' (default — only warns) or "
                                + "'hard' (kanban_move blocks moves that "
                                + "would exceed wipLimit)."));
                put("overwrite", Map.of("type", "boolean",
                        "description", "Allow replacing an existing "
                                + "_app.yaml. Default false."));
                put("projectId", Map.of("type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final KanbanApplication kanbanApplication;

    public KanbanAppCreateTool(EddieContext eddieContext,
                               KanbanApplication kanbanApplication) {
        this.eddieContext = eddieContext;
        this.kanbanApplication = kanbanApplication;
    }

    @Override public String name() { return "kanban_app_create"; }

    @Override
    public String description() {
        return "ONE-SHOT bootstrap for a Kanban board. Pass `folder` + "
                + "`columns` + `cards` (each with `column:` hint), and "
                + "this tool writes the manifest, creates one .md file "
                + "per card in its column folder, and auto-rebuilds "
                + "_board.md + _stats.yaml — single call. ALWAYS use "
                + "this for new boards / sprint planning. Do NOT "
                + "hand-write _app.yaml via doc_create, do NOT chain "
                + "N x doc_create + app_rebuild when cards are known "
                + "up-front.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "kanban", "application");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);

        Map<String, Object> createParams = new LinkedHashMap<>();
        copyIfPresent(params, createParams, "title");
        copyIfPresent(params, createParams, "description");
        copyIfPresent(params, createParams, "columns");
        copyIfPresent(params, createParams, "cards");
        copyIfPresent(params, createParams, "boardStyle");
        copyIfPresent(params, createParams, "wipEnforce");

        VanceApplication.CreateContext cc = new VanceApplication.CreateContext(
                ctx.tenantId(), project.getName(), normaliseFolder(folder),
                ctx.userId(), ctx.processId(),
                paramBoolean(params, "overwrite"),
                createParams);

        VanceApplication.CreateResult result = kanbanApplication.create(cc);

        log.info("KanbanAppCreateTool tenant='{}' folder='{}' "
                        + "columns={} manifestPath='{}'",
                ctx.tenantId(), folder, result.lanes().size(),
                result.manifestPath());

        return result.toMap();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
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

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        Object v = src == null ? null : src.get(key);
        if (v != null) dst.put(key, v);
    }
}
