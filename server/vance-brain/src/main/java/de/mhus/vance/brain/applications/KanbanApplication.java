package de.mhus.vance.brain.applications;

import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.brain.tools.kanban.KanbanBoardRenderer;
import de.mhus.vance.brain.tools.kanban.KanbanFolderReader;
import de.mhus.vance.brain.tools.kanban.KanbanStatsBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.document.kind.DataCodec;
import de.mhus.vance.shared.document.kind.DataDocument;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Concrete {@link VanceApplication} for {@code app: kanban} folders.
 * Owns the orchestration of every derived artefact: the board
 * ({@code _board.md} — Mermaid or Markdown-table) and the stats
 * ({@code _stats.yaml}).
 *
 * <p>Domain helpers ({@link KanbanFolderReader},
 * {@link KanbanBoardRenderer}, {@link KanbanStatsBuilder}) stay
 * stateless — this service wires them into one idempotent refresh
 * pipeline against {@link DocumentService}.
 */
@Service
@Slf4j
public class KanbanApplication implements VanceApplication {

    public static final String APP_NAME = KanbanAppConfig.APP_NAME;

    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private final KanbanFolderReader folderReader;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public KanbanApplication(KanbanFolderReader folderReader,
                             DocumentService documentService,
                             DocumentLinkBuilder linkBuilder) {
        this.folderReader = folderReader;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
    }

    @Override public String appName() { return APP_NAME; }

    /**
     * One-shot bootstrap for a new kanban suite.
     *
     * <p>Writes the {@code _app.yaml} manifest with the correct schema.
     * If {@code cards} are present in the params, also writes one
     * {@code kind: card} file per entry to its target column folder
     * ({@code <folder>/<column>/<name>.md}) and runs an immediate
     * {@link #refresh} so {@code _board.md} and {@code _stats.yaml} are
     * available in the same call.
     *
     * <p>Expected {@code params} keys:
     * <ul>
     *   <li>{@code title} (string, optional)</li>
     *   <li>{@code description} (string, optional)</li>
     *   <li>{@code columns} (List of {@code {name, title?, color?, order?, wipLimit?}}
     *       — empty list = columns inferred from cards / default set)</li>
     *   <li>{@code cards} (List of card maps, optional). Each card carries
     *       {@code title} (required), {@code column} (defaults to
     *       {@value KanbanFolderReader#DEFAULT_COLUMN}),
     *       {@code priority}, {@code assignee}, {@code labels},
     *       {@code dueDate}, {@code estimate}, {@code blocked},
     *       {@code body}.</li>
     *   <li>{@code wipEnforce} (string, optional — "soft" or "hard")</li>
     *   <li>{@code boardStyle} (string, optional — "mermaid" or "table")</li>
     * </ul>
     */
    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = ctx.folder();
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + KanbanFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        List<Map<String, Object>> columnInputs = asColumnInputList(params.get("columns"));
        List<Map<String, Object>> cardInputs = asMapList(params.get("cards"));
        String wipEnforce = asString(params.get("wipEnforce"));
        String boardStyle = asString(params.get("boardStyle"));

        Map<String, Object> columns = new LinkedHashMap<>();
        List<CreateLane> columnResults = new ArrayList<>();
        int autoOrder = 1;
        for (Map<String, Object> raw : columnInputs) {
            String name = asString(raw.get("name"));
            if (name == null || name.isBlank()) continue;
            name = sanitiseName(name);
            String colTitle = asString(raw.get("title"));
            String colColor = asString(raw.get("color"));
            Integer order = (raw.get("order") instanceof Number n) ? n.intValue() : autoOrder;
            Integer wipLimit = (raw.get("wipLimit") instanceof Number n2) ? n2.intValue() : null;

            Map<String, Object> body = new LinkedHashMap<>();
            if (colTitle != null) body.put("title", colTitle);
            if (colColor != null) body.put("color", colColor);
            body.put("order", order);
            if (wipLimit != null) body.put("wipLimit", wipLimit);
            columns.put(name, body);

            columnResults.add(new CreateLane(
                    name, colTitle, colColor,
                    folder + "/" + name + "/"));
            autoOrder = order + 1;
        }

        // Group cards by column. Auto-extend the columns map with any
        // column referenced by a card but missing from `columnInputs`.
        Map<String, List<Map<String, Object>>> cardsByColumn = new LinkedHashMap<>();
        for (Map<String, Object> raw : cardInputs) {
            String colRaw = asString(raw.get("column"));
            String column = (colRaw == null || colRaw.isBlank())
                    ? KanbanFolderReader.DEFAULT_COLUMN
                    : sanitiseName(colRaw);
            Map<String, Object> stripped = new LinkedHashMap<>(raw);
            stripped.remove("column");
            cardsByColumn.computeIfAbsent(column, k -> new ArrayList<>())
                    .add(stripped);
        }
        for (String col : cardsByColumn.keySet()) {
            if (columns.containsKey(col)) continue;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("order", autoOrder);
            columns.put(col, body);
            columnResults.add(new CreateLane(
                    col, null, null, folder + "/" + col + "/"));
            autoOrder++;
        }

        // Build the kanban config block.
        Map<String, Object> kanbanBlock = new LinkedHashMap<>();
        if (!columns.isEmpty()) kanbanBlock.put("columns", columns);
        Map<String, Object> boardSection = new LinkedHashMap<>();
        boardSection.put("outputPath", "_board.md");
        boardSection.put("style", boardStyle != null ? boardStyle.toLowerCase(Locale.ROOT) : "mermaid");
        kanbanBlock.put("board", boardSection);
        kanbanBlock.put("stats", Map.of(
                "outputPath", "_stats.yaml",
                "blockedLabel", "blocked",
                "staleThresholdDays", 14));
        if (wipEnforce != null) {
            kanbanBlock.put("wipEnforce", wipEnforce.toLowerCase(Locale.ROOT));
        }

        // Assemble + persist manifest.
        Map<String, Object> appConfig = new LinkedHashMap<>();
        appConfig.put(KanbanAppConfig.APP_NAME, kanbanBlock);
        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description,
                appConfig, new LinkedHashMap<>());
        String manifestBody = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Kanban app",
                    List.of("application", "kanban"),
                    manifestBody, null, null, null, null, YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Kanban app",
                        List.of("application", "kanban"),
                        YAML_MIME, in, ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath
                                + "': " + e.getMessage());
            }
        }

        // Dispatch inline cards to per-column files. Filename = card
        // title slugged. Collisions get -2, -3, … suffixes.
        int cardCountWritten = 0;
        java.util.Set<String> usedPaths = new java.util.HashSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : cardsByColumn.entrySet()) {
            String column = e.getKey();
            for (Map<String, Object> raw : e.getValue()) {
                CardDocument card = buildCardDocument(raw);
                String slug = slugify(card.title());
                String path = uniquePath(folder + "/" + column, slug, ".md", usedPaths);
                usedPaths.add(path);
                String cardBody = CardCodec.serialize(card, MD_MIME);
                writeOrUpdateCard(ctx, path, cardBody, card.title());
                cardCountWritten++;
            }
        }

        log.info("KanbanApplication.create tenant='{}' folder='{}' "
                        + "columns={} cards={} manifestPath='{}'",
                ctx.tenantId(), folder, columnResults.size(),
                cardCountWritten, manifestPath);

        List<ArtefactResult> artefacts;
        if (cardCountWritten > 0) {
            RefreshContext rc = new RefreshContext(
                    ctx.tenantId(), ctx.projectName(), folder,
                    ctx.userId(), ctx.processId());
            RefreshResult refresh = refresh(rc);
            artefacts = refresh.artefacts();
        } else {
            artefacts = List.of();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("columnCount", columnResults.size());
        stats.put("manifestColumnCount", columns.size());
        if (cardCountWritten > 0) stats.put("cardCount", cardCountWritten);
        if (title != null) stats.put("title", title);

        String nextStep;
        if (cardCountWritten > 0) {
            nextStep = "Board ready — _board.md + _stats.yaml are in "
                    + "the `artefacts` list. Embed both `markdownLink`s "
                    + "in your chat reply.";
        } else {
            nextStep = "Add cards with "
                    + "`kanban_card_create(folder, column, title, ...)` "
                    + "or pass `cards=[...]` to `kanban_app_create` "
                    + "directly for a one-shot setup, then "
                    + "`app_rebuild('" + folder + "')` to refresh "
                    + "the board.";
        }

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                columnResults, artefacts, nextStep, stats);
    }

    /**
     * Outcome of {@link #moveCard}. {@code fromColumn} reflects the
     * card's location before the move; {@code warnings} carries
     * WIP-overflow notices (only populated with {@code wipEnforce=soft}).
     */
    public record MoveResult(
            String cardPath,
            String fromColumn,
            String toColumn,
            List<String> warnings,
            boolean skipped,
            @Nullable String skipReason) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("card", cardPath);
            m.put("fromColumn", fromColumn);
            m.put("toColumn", toColumn);
            if (skipped) {
                m.put("skipped", true);
                if (skipReason != null) m.put("reason", skipReason);
            }
            if (warnings != null && !warnings.isEmpty()) {
                m.put("warnings", warnings);
            }
            return m;
        }
    }

    /**
     * Move a card between columns. Resolves the card by full path,
     * filename, or sanitised title — all three forms work. Enforces
     * {@code wipEnforce=hard} by throwing, surfaces {@code soft}
     * overruns as warnings.
     *
     * @param folder        the kanban-app folder (already normalised).
     * @param cardRef       full path, filename (with/without {@code .md}),
     *                      or sanitised title.
     * @param toColumnRaw   target column (sanitised internally).
     */
    public MoveResult moveCard(RefreshContext ctx,
                               String folder,
                               String cardRef,
                               String toColumnRaw) {
        String toColumn = sanitiseColumnName(toColumnRaw);
        KanbanFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), folder);
        DocumentDocument cardDoc = resolveCard(scan, folder, cardRef);
        String fromColumn = KanbanFolderReader.columnFor(folder, cardDoc.getPath());

        if (fromColumn.equals(toColumn)) {
            return new MoveResult(cardDoc.getPath(), fromColumn, toColumn,
                    List.of(), true, "Card already in target column.");
        }

        // WIP-limit check.
        KanbanAppConfig.Column target = scan.kanbanConfig().columns().get(toColumn);
        Integer wipLimit = target != null ? target.wipLimit() : null;
        List<String> warnings = new ArrayList<>();
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
                                    + "wipEnforce=hard blocks this move.");
                }
                warnings.add("wip-exceeded:" + toColumn + ":" + (currentCount + 1)
                        + "/" + wipLimit);
            }
        }

        // Compute new path.
        String oldPath = cardDoc.getPath();
        int slash = oldPath.lastIndexOf('/');
        String filename = slash < 0 ? oldPath : oldPath.substring(slash + 1);
        String newPath = folder + "/" + toColumn + "/" + filename;

        Optional<DocumentDocument> collision = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), newPath);
        if (collision.isPresent() && !collision.get().getId().equals(cardDoc.getId())) {
            throw new ToolException(
                    "Target path '" + newPath + "' is already occupied "
                            + "by another card. Rename the card first.");
        }

        DocumentDocument moved = documentService.update(
                cardDoc.getId(), null, null, null, newPath);

        log.info("KanbanApplication.moveCard tenant='{}' folder='{}' "
                        + "card='{}' {}→{} warnings={}",
                ctx.tenantId(), folder, filename,
                fromColumn, toColumn, warnings);

        return new MoveResult(moved.getPath(), fromColumn, toColumn,
                warnings, false, null);
    }

    private static DocumentDocument resolveCard(KanbanFolderReader.Scan scan,
                                                String folder, String ref) {
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            if (cf.doc().getPath().equals(ref)) return cf.doc();
        }
        String wantedLeaf = ref.contains("/")
                ? ref.substring(ref.lastIndexOf('/') + 1)
                : ref;
        if (!wantedLeaf.endsWith(".md")) wantedLeaf = wantedLeaf + ".md";
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            String path = cf.doc().getPath();
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.equalsIgnoreCase(wantedLeaf)) return cf.doc();
        }
        String titleSlug = sanitiseColumnName(ref);
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            if (sanitiseColumnName(cf.card().title()).equals(titleSlug)) return cf.doc();
        }
        throw new ToolException(
                "No card matching '" + ref + "' found in '" + folder + "'.");
    }

    private static String sanitiseColumnName(String raw) {
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

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        KanbanFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), ctx.folder());

        ArtefactResult board = doRefreshBoard(scan, ctx);
        ArtefactResult stats = doRefreshStats(scan, ctx);

        log.info("KanbanApplication.refresh tenant='{}' folder='{}' "
                        + "→ {} + {}",
                ctx.tenantId(), scan.folder(),
                board.path(), stats.path());

        return new RefreshResult(APP_NAME, scan.folder(),
                List.of(board, stats));
    }

    // ── Internal: board ───────────────────────────────────────────

    private ArtefactResult doRefreshBoard(KanbanFolderReader.Scan scan,
                                          RefreshContext ctx) {
        String fallbackTitle = leafFolderName(scan.folder());
        String body = KanbanBoardRenderer.render(scan, fallbackTitle);
        String title = scan.manifest().title() != null
                ? scan.manifest().title() : fallbackTitle;

        String outputPath = KanbanFolderReader.resolveOutputPath(
                scan.folder(), scan.kanbanConfig().board().outputPath());
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, body, "Board — " + title, MD_MIME,
                List.of("kanban", "generated", "board"));

        Map<String, Object> bodyStats = new LinkedHashMap<>();
        bodyStats.put("cardCount", scan.cards().size());
        bodyStats.put("columnCount", countDistinctColumns(scan));
        bodyStats.put("style", scan.kanbanConfig().board().style().wireName());

        return new ArtefactResult(
                "board", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                bodyStats);
    }

    // ── Internal: stats ───────────────────────────────────────────

    private ArtefactResult doRefreshStats(KanbanFolderReader.Scan scan,
                                          RefreshContext ctx) {
        Map<String, Object> body = KanbanStatsBuilder.build(scan);
        DataDocument data = new DataDocument("data", body, new LinkedHashMap<>());
        String yaml = DataCodec.serialize(data, YAML_MIME);

        String outputPath = KanbanFolderReader.resolveOutputPath(
                scan.folder(), scan.kanbanConfig().stats().outputPath());
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, yaml, "Kanban stats (" + scan.folder() + ")", YAML_MIME,
                List.of("kanban", "generated", "stats"));

        Map<String, Object> outStats = new LinkedHashMap<>();
        outStats.put("cardCount", scan.cards().size());
        Object progressRaw = body.get("progress");
        if (progressRaw instanceof Map<?, ?> p && p.get("done") instanceof Number doneNum) {
            outStats.put("done", doneNum.intValue());
        }
        Object blocked = body.get("blocked");
        if (blocked instanceof List<?> bl) outStats.put("blockedCount", bl.size());
        Object stale = body.get("stale");
        if (stale instanceof List<?> st) outStats.put("staleCount", st.size());
        int wipExceeded = countWipExceeded(scan);
        if (wipExceeded > 0) outStats.put("wipExceeded", wipExceeded);

        return new ArtefactResult(
                "stats", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                outStats);
    }

    private static int countWipExceeded(KanbanFolderReader.Scan scan) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            counts.merge(cf.column(), 1, Integer::sum);
        }
        int exceeded = 0;
        for (Map.Entry<String, KanbanAppConfig.Column> e :
                scan.kanbanConfig().columns().entrySet()) {
            Integer limit = e.getValue().wipLimit();
            if (limit == null) continue;
            int actual = counts.getOrDefault(e.getKey(), 0);
            if (actual > limit) exceeded++;
        }
        return exceeded;
    }

    private static int countDistinctColumns(KanbanFolderReader.Scan scan) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (KanbanFolderReader.CardFile cf : scan.cards()) seen.add(cf.column());
        return seen.size();
    }

    // ── Common write path ─────────────────────────────────────────

    private DocumentDocument writeArtefact(RefreshContext ctx,
                                           String outputPath,
                                           String body,
                                           String title,
                                           String mime,
                                           List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title, tags, body, null, null, null, null, mime);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    outputPath, title, tags, mime, in, ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    private void writeOrUpdateCard(CreateContext ctx, String path,
                                   String body, String title) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    title, List.of("card"),
                    body, null, null, null, null, MD_MIME);
            return;
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8))) {
            documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    path, title, List.of("card"),
                    MD_MIME, in, ctx.userId());
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write card '" + path + "': " + e.getMessage());
        }
    }

    // ── Build a CardDocument from raw map ─────────────────────────

    private static CardDocument buildCardDocument(Map<String, Object> raw) {
        String title = asString(raw.get("title"));
        if (title == null) {
            throw new ToolException("card is missing 'title'");
        }
        String priority = asString(raw.get("priority"));
        String assignee = asString(raw.get("assignee"));
        List<String> labels = asStringList(raw.get("labels"));
        String dueDate = asString(raw.get("dueDate"));
        Double estimate = null;
        Object eRaw = raw.get("estimate");
        if (eRaw instanceof Number n) estimate = n.doubleValue();
        else if (eRaw instanceof String s && !s.isBlank()) {
            try { estimate = Double.parseDouble(s.trim()); }
            catch (NumberFormatException ignored) { /* skipped */ }
        }
        boolean blocked = raw.get("blocked") instanceof Boolean b && b;
        String body = asString(raw.get("body"));
        if (body == null) body = "";
        return new CardDocument(
                "card", title, priority, assignee, labels, dueDate,
                estimate, blocked, body, new LinkedHashMap<>());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1).toLowerCase(Locale.ROOT);
    }

    /** Sanitise a name for use as a folder / filename slug. */
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

    private static String slugify(String raw) {
        return sanitiseName(raw);
    }

    private static String uniquePath(String folder, String slug, String ext,
                                     java.util.Set<String> used) {
        String base = folder + "/" + slug + ext;
        if (!used.contains(base)) return base;
        int n = 2;
        while (true) {
            String candidate = folder + "/" + slug + "-" + n + ext;
            if (!used.contains(candidate)) return candidate;
            n++;
        }
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static List<Map<String, Object>> asMapList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> m = asMap(item);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static List<String> asStringList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = asString(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Permissive column-list reader: accepts rich objects OR plain
     *  strings ({@code ["backlog", "todo", "doing", "done"]}). */
    private static List<Map<String, Object>> asColumnInputList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("name", s.trim());
                out.add(wrapped);
            } else {
                Map<String, Object> m = asMap(item);
                if (m != null) out.add(m);
            }
        }
        return out;
    }
}
