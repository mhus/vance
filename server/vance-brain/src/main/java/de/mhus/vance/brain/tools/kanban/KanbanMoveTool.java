package de.mhus.vance.brain.tools.kanban;

import de.mhus.vance.brain.applications.KanbanApplication;
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
 * Move a card between columns. The on-disk effect is a path rename
 * ({@code <folder>/<oldCol>/<file>.md} →
 * {@code <folder>/<newCol>/<file>.md}); the card body is unchanged.
 *
 * <p>WIP-limit semantics:
 * <ul>
 *   <li>{@code wipEnforce: soft} (default) — over-limit moves succeed
 *       and the result carries {@code warnings: ["wip-exceeded"]}.</li>
 *   <li>{@code wipEnforce: hard} — over-limit moves are rejected with
 *       a {@link ToolException}.</li>
 * </ul>
 *
 * <p>Optionally rebuilds the board after the move ({@code rebuild=true},
 * default {@code false} so batching stays cheap).
 */
@Component
@Slf4j
public class KanbanMoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Kanban app folder."));
                put("card", Map.of("type", "string",
                        "description", "Card to move. Either the full "
                                + "document path or just the filename "
                                + "(slug) — the tool resolves the rest."));
                put("toColumn", Map.of("type", "string",
                        "description", "Target column. Must exist as a "
                                + "declared column in _app.yaml OR be "
                                + "the leaf of an existing sub-folder."));
                put("rebuild", Map.of("type", "boolean",
                        "description", "Run app_rebuild after the move. "
                                + "Default false — caller batches moves "
                                + "and rebuilds once."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder", "card", "toColumn"));

    private final EddieContext eddieContext;
    private final KanbanApplication kanbanApplication;

    public KanbanMoveTool(EddieContext eddieContext,
                          KanbanApplication kanbanApplication) {
        this.eddieContext = eddieContext;
        this.kanbanApplication = kanbanApplication;
    }

    @Override public String name() { return "kanban_move"; }

    @Override
    public String description() {
        return "Move a card between columns on a kanban board. "
                + "Resolves the card by full path or filename. Respects "
                + "WIP limits (soft warns; hard blocks). Use this for "
                + "any card-state-change — never edit the column folder "
                + "by hand via doc_edit / doc_move.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "kanban", "move");
    }

    @Override
    public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String cardRef = paramString(params, "card");
        if (cardRef == null) throw new ToolException("card is required");
        String toColumnRaw = paramString(params, "toColumn");
        if (toColumnRaw == null) throw new ToolException("toColumn is required");
        boolean rebuild = paramBoolean(params, "rebuild");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String tenantId = ctx.tenantId();
        String projectName = project.getName();
        String normalisedFolder = normaliseFolder(folder);

        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenantId, projectName, normalisedFolder,
                ctx.userId(), ctx.processId());

        de.mhus.vance.brain.applications.KanbanApplication.MoveResult mv =
                kanbanApplication.moveCard(rc, normalisedFolder, cardRef, toColumnRaw);

        log.info("KanbanMoveTool tenant='{}' folder='{}' card='{}' {}→{}",
                tenantId, normalisedFolder, mv.cardPath(),
                mv.fromColumn(), mv.toColumn());

        Map<String, Object> result = new LinkedHashMap<>(mv.toMap());
        if (rebuild) {
            VanceApplication.RefreshResult refresh = kanbanApplication.refresh(rc);
            List<Map<String, Object>> arts = new java.util.ArrayList<>();
            for (VanceApplication.ArtefactResult a : refresh.artefacts()) arts.add(a.toMap());
            result.put("artefacts", arts);
        } else {
            result.put("nextStep",
                    "Call `app_rebuild('" + normalisedFolder + "')` "
                            + "when done moving cards to refresh "
                            + "_board.md + _stats.yaml.");
        }
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
}
