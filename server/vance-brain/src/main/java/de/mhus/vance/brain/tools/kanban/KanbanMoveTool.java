package de.mhus.vance.brain.tools.kanban;

import de.mhus.vance.brain.applications.KanbanApplication;
import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
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
    private final DocumentService documentService;
    private final KanbanFolderReader folderReader;
    private final KanbanApplication kanbanApplication;

    public KanbanMoveTool(EddieContext eddieContext,
                          DocumentService documentService,
                          KanbanFolderReader folderReader,
                          KanbanApplication kanbanApplication) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.folderReader = folderReader;
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
        String toColumn = sanitiseName(toColumnRaw);
        boolean rebuild = paramBoolean(params, "rebuild");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String tenantId = ctx.tenantId();
        String projectName = project.getName();
        String normalisedFolder = normaliseFolder(folder);

        KanbanFolderReader.Scan scan = folderReader.scan(tenantId, projectName, normalisedFolder);

        // Resolve the card document.
        DocumentDocument cardDoc = resolveCard(scan, normalisedFolder, cardRef);
        String fromColumn = KanbanFolderReader.columnFor(
                normalisedFolder, cardDoc.getPath());

        if (fromColumn.equals(toColumn)) {
            Map<String, Object> noop = new LinkedHashMap<>();
            noop.put("card", cardDoc.getPath());
            noop.put("fromColumn", fromColumn);
            noop.put("toColumn", toColumn);
            noop.put("skipped", true);
            noop.put("reason", "Card already in target column.");
            return noop;
        }

        // WIP-limit check.
        KanbanAppConfig.Column target = scan.kanbanConfig().columns().get(toColumn);
        Integer wipLimit = target != null ? target.wipLimit() : null;
        List<String> warnings = new java.util.ArrayList<>();
        if (wipLimit != null) {
            int currentCount = 0;
            for (KanbanFolderReader.CardFile cf : scan.cards()) {
                if (toColumn.equals(cf.column())) currentCount++;
            }
            if (currentCount >= wipLimit) {
                if (scan.kanbanConfig().wipEnforce() == KanbanAppConfig.WipEnforce.HARD) {
                    throw new ToolException(
                            "Column '" + toColumn + "' is at WIP limit ("
                                    + currentCount + "/" + wipLimit + "). "
                                    + "wipEnforce=hard blocks this move. "
                                    + "Move a card out of '" + toColumn
                                    + "' first.");
                }
                warnings.add("wip-exceeded:" + toColumn + ":" + (currentCount + 1)
                        + "/" + wipLimit);
            }
        }

        // Compute new path: same filename, new column folder.
        String oldPath = cardDoc.getPath();
        int slash = oldPath.lastIndexOf('/');
        String filename = slash < 0 ? oldPath : oldPath.substring(slash + 1);
        String newPath = normalisedFolder + "/" + toColumn + "/" + filename;

        Optional<DocumentDocument> collision = documentService.findByPath(
                tenantId, projectName, newPath);
        if (collision.isPresent() && !collision.get().getId().equals(cardDoc.getId())) {
            throw new ToolException(
                    "Target path '" + newPath + "' is already occupied "
                            + "by another card. Rename the card first.");
        }

        // Perform the move via DocumentService.update(newPath=…).
        DocumentDocument moved = documentService.update(
                cardDoc.getId(), null, null, null, newPath);

        log.info("KanbanMoveTool tenant='{}' folder='{}' card='{}' "
                        + "{}→{} warnings={}",
                tenantId, normalisedFolder, filename,
                fromColumn, toColumn, warnings);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("card", moved.getPath());
        result.put("fromColumn", fromColumn);
        result.put("toColumn", toColumn);
        if (!warnings.isEmpty()) result.put("warnings", warnings);

        if (rebuild) {
            VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                    tenantId, projectName, normalisedFolder,
                    ctx.userId(), ctx.processId());
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

    // ── Card resolution ───────────────────────────────────────────

    private DocumentDocument resolveCard(KanbanFolderReader.Scan scan,
                                         String folder, String ref) {
        // 1) Full path match.
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            if (cf.doc().getPath().equals(ref)) return cf.doc();
        }
        // 2) Filename (with or without .md).
        String wantedLeaf = ref.contains("/")
                ? ref.substring(ref.lastIndexOf('/') + 1)
                : ref;
        if (!wantedLeaf.endsWith(".md")) wantedLeaf = wantedLeaf + ".md";
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            String path = cf.doc().getPath();
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.equalsIgnoreCase(wantedLeaf)) return cf.doc();
        }
        // 3) Title match (sanitised) — convenience for "move 'Login' to doing".
        String titleSlug = sanitiseName(ref);
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            if (sanitiseName(cf.card().title()).equals(titleSlug)) return cf.doc();
        }
        throw new ToolException(
                "No card matching '" + ref + "' found in '" + folder
                        + "'. Use kanban_aggregate to list cards, or "
                        + "pass the full document path.");
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
}
