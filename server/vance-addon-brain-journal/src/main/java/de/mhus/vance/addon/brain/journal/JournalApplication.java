package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: journal} folders. Owns the two
 * derived artefacts — {@code _index.md} (recent + year-grouped list) and
 * {@code _stats.yaml} (streaks, mood, tags). Entry discovery lives in
 * {@link JournalFolderReader}; upsert / queries in {@link JournalService};
 * numbers in {@link JournalStatsBuilder}; rendering in
 * {@link JournalIndexRenderer} / {@link JournalStatsRenderer}.
 */
@Service
@Slf4j
public class JournalApplication implements VanceApplication {

    public static final String APP_NAME = JournalConfig.APP_NAME;
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";
    private static final String INDEX_FILE = "_index.md";
    private static final String STATS_FILE = "_stats.yaml";

    private final JournalFolderReader folderReader;
    private final JournalStatsBuilder statsBuilder;
    private final JournalIndexRenderer indexRenderer;
    private final JournalStatsRenderer statsRenderer;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public JournalApplication(JournalFolderReader folderReader,
                              JournalStatsBuilder statsBuilder,
                              JournalIndexRenderer indexRenderer,
                              JournalStatsRenderer statsRenderer,
                              DocumentService documentService,
                              DocumentLinkBuilder linkBuilder) {
        this.folderReader = folderReader;
        this.statsBuilder = statsBuilder;
        this.indexRenderer = indexRenderer;
        this.statsRenderer = statsRenderer;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in a journal (diary) at `" + ctx.folder() + "`. "
                + "Entries are `kind: journal-entry` Markdown files, one per calendar day, "
                + "under `entries/<YYYY>/<YYYY-MM-DD>.md`. Each carries a `date`, optional "
                + "`mood` (great/good/neutral/low/bad) and free `tags`. "
                + "Write or amend a day with "
                + "`journal_entry_create(folder=\"" + ctx.folder() + "\", date=..., body=..., mood=...)` "
                + "(date defaults to today; re-running the same date appends via the editor, "
                + "not a duplicate). Search past entries with "
                + "`journal_search(folder=\"" + ctx.folder() + "\", query=...)` and regenerate "
                + "the index + stats with `app_rebuild('" + ctx.folder() + "')`. "
                + "Never edit `_index.md` / `_stats.yaml` — they're rewritten on rebuild. "
                + "Read `manual_read('app-journal')` for the full model.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = JournalFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + JournalFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));

        StringBuilder mb = new StringBuilder();
        mb.append("$meta:\n  kind: application\n  app: journal\n");
        if (title != null) mb.append("title: \"").append(escape(title)).append("\"\n");
        if (description != null) mb.append("description: \"").append(escape(description)).append("\"\n");
        mb.append("journal:\n");
        mb.append("  entriesDir: ").append(JournalConfig.DEFAULT_ENTRIES_DIR).append('\n');
        mb.append("  indexLimit: ").append(JournalConfig.DEFAULT_INDEX_LIMIT).append('\n');
        mb.append("  moodPresets: [")
                .append(String.join(", ", JournalConfig.DEFAULT_MOODS)).append("]\n");
        String manifestBody = mb.toString();

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Journal",
                    List.of("application", "journal"),
                    manifestBody, null, null, null, null, YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(), manifestPath,
                        title != null ? title : "Journal",
                        List.of("application", "journal"),
                        YAML_MIME, in, ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId());
        RefreshResult refresh = refresh(rc);

        log.info("JournalApplication.create tenant='{}' folder='{}'", ctx.tenantId(), folder);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("artefactCount", refresh.artefacts().size());

        String nextStep = "Journal ready. Add today's entry with "
                + "`journal_entry_create(folder=\"" + folder + "\", body=\"...\", mood=\"good\")`, "
                + "then `app_rebuild('" + folder + "')` to refresh the index + stats.";

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(),
                refresh.artefacts(),
                nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = JournalFolderReader.normaliseFolder(ctx.folder());
        JournalFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        JournalStatsBuilder.Stats stats = statsBuilder.build(scan, LocalDate.now());

        List<ArtefactResult> artefacts = new ArrayList<>();

        String indexBody = indexRenderer.render(scan, title, scan.config().indexLimit());
        DocumentDocument index = writeArtefact(ctx, folder + "/" + INDEX_FILE, indexBody,
                "Index — " + title, MD_MIME, List.of("journal", "generated", "index"));
        Map<String, Object> indexStats = new LinkedHashMap<>();
        indexStats.put("entryCount", scan.entries().size());
        artefacts.add(new ArtefactResult("index", index.getPath(),
                linkBuilder.linkFor(index, ctx.projectName()), indexStats));

        String statsBody = statsRenderer.render(folder, stats);
        DocumentDocument statsDoc = writeArtefact(ctx, folder + "/" + STATS_FILE, statsBody,
                "Stats — " + title, YAML_MIME, List.of("journal", "generated", "stats"));
        Map<String, Object> statStats = new LinkedHashMap<>();
        statStats.put("totalEntries", stats.totalEntries());
        statStats.put("currentStreak", stats.currentStreak());
        statStats.put("longestStreak", stats.longestStreak());
        artefacts.add(new ArtefactResult("stats", statsDoc.getPath(),
                linkBuilder.linkFor(statsDoc, ctx.projectName()), statStats));

        log.info("JournalApplication.refresh tenant='{}' folder='{}' entries={} streak={}",
                ctx.tenantId(), folder, scan.entries().size(), stats.currentStreak());

        return new RefreshResult(APP_NAME, folder, artefacts);
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title, String mime,
                                           List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(), title, tags,
                    body, null, null, null, null, mime);
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

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
