package de.mhus.vance.addon.brain.gtd;

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
 * {@link VanceApplication} for {@code app: gtd} folders. Owns the three derived
 * artefacts — {@code _today.md}, {@code _upcoming.md}, {@code _stats.yaml}.
 * Action discovery lives in {@link GtdFolderReader}; capture/create/update/move
 * in {@link GtdService}; bucketing in {@link GtdBucketResolver}.
 */
@Service
@Slf4j
public class GtdApplication implements VanceApplication {

    public static final String APP_NAME = GtdConfig.APP_NAME;
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";
    private static final String TODAY_FILE = "_today.md";
    private static final String UPCOMING_FILE = "_upcoming.md";
    private static final String STATS_FILE = "_stats.yaml";

    private final GtdFolderReader folderReader;
    private final GtdService gtdService;
    private final GtdStatsBuilder statsBuilder;
    private final GtdRenderer renderer;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public GtdApplication(GtdFolderReader folderReader,
                          GtdService gtdService,
                          GtdStatsBuilder statsBuilder,
                          GtdRenderer renderer,
                          DocumentService documentService,
                          DocumentLinkBuilder linkBuilder,
                          de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.folderReader = folderReader;
        this.gtdService = gtdService;
        this.statsBuilder = statsBuilder;
        this.renderer = renderer;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in a GTD workspace at `" + ctx.folder() + "` (Things-style). "
                + "Actions are `kind: action` files. Their bucket — Inbox / Today / Upcoming / "
                + "Anytime / Someday — is DERIVED from the `when` attribute (+ optional `deadline`) "
                + "and today's date, NOT from a folder. `when` is `` (Anytime), `today`, `someday`, "
                + "or an ISO date (future=Upcoming, slides into Today on the day). "
                + "Capture quickly with `gtd_capture(folder=\"" + ctx.folder() + "\", title=...)` (→ Inbox); "
                + "create a processed action with `gtd_action_create(...)`; change a bucket with "
                + "`gtd_action_update(...)` setting `when` (do NOT try to move files between bucket "
                + "folders — there are none). Search with `gtd_search`, list with `gtd_query`, and "
                + "`app_rebuild('" + ctx.folder() + "')` regenerates _today/_upcoming/_stats. "
                + "Read `manual_read('app-gtd')` and `manual_read('gtd-buckets')` for the model.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = GtdFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + GtdFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '" + manifestPath
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));

        StringBuilder mb = new StringBuilder();
        mb.append("$meta:\n  kind: application\n  app: gtd\n");
        if (title != null) mb.append("title: \"").append(escape(title)).append("\"\n");
        if (description != null) mb.append("description: \"").append(escape(description)).append("\"\n");
        mb.append("gtd:\n");
        mb.append("  inboxDir: ").append(GtdConfig.DEFAULT_INBOX_DIR).append('\n');
        mb.append("  actionsDir: ").append(GtdConfig.DEFAULT_ACTIONS_DIR).append('\n');
        mb.append("  projectsDir: ").append(GtdConfig.DEFAULT_PROJECTS_DIR).append('\n');
        mb.append("  contexts: [\"@calls\", \"@errands\", \"@home\", \"@office\", \"@computer\"]\n");
        String manifestBody = mb.toString();

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(existing.get().getId(),
                    title != null ? title : "GTD",
                    List.of("application", "gtd"),
                    manifestBody, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(ctx.tenantId(), ctx.projectName(), manifestPath,
                        title != null ? title : "GTD",
                        List.of("application", "gtd"), YAML_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException("Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId());
        RefreshResult refresh = refresh(rc);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("artefactCount", refresh.artefacts().size());
        String nextStep = "GTD ready. Capture with "
                + "`gtd_capture(folder=\"" + folder + "\", title=\"...\")`, process into buckets by "
                + "setting `when`, then `app_rebuild('" + folder + "')`.";

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), refresh.artefacts(), nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = GtdFolderReader.normaliseFolder(ctx.folder());
        GtdFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);
        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        LocalDate today = LocalDate.now();
        Map<GtdBucket, List<GtdAction>> buckets = gtdService.computeBuckets(scan, today);
        List<GtdAction> overdue = gtdService.overdue(scan, today);
        GtdStatsBuilder.Stats stats = statsBuilder.build(scan, today);

        List<ArtefactResult> artefacts = new ArrayList<>();
        artefacts.add(writeArtefact(ctx, folder + "/" + TODAY_FILE,
                renderer.renderToday(buckets.get(GtdBucket.TODAY), overdue, title),
                "Today — " + title, MD_MIME, List.of("gtd", "generated", "today"),
                Map.of("today", buckets.get(GtdBucket.TODAY).size(), "overdue", overdue.size())));
        artefacts.add(writeArtefact(ctx, folder + "/" + UPCOMING_FILE,
                renderer.renderUpcoming(buckets.get(GtdBucket.UPCOMING), title),
                "Upcoming — " + title, MD_MIME, List.of("gtd", "generated", "upcoming"),
                Map.of("upcoming", buckets.get(GtdBucket.UPCOMING).size())));
        artefacts.add(writeArtefact(ctx, folder + "/" + STATS_FILE,
                renderer.renderStats(folder, stats),
                "Stats — " + title, YAML_MIME, List.of("gtd", "generated", "stats"),
                Map.of("totalOpen", stats.totalOpen(), "inbox", stats.bucketCounts().getOrDefault("inbox", 0))));

        log.info("GtdApplication.refresh tenant='{}' folder='{}' open={} inbox={}",
                ctx.tenantId(), folder, stats.totalOpen(), stats.bucketCounts().get("inbox"));
        return new RefreshResult(APP_NAME, folder, artefacts);
    }

    private ArtefactResult writeArtefact(RefreshContext ctx, String outputPath, String body,
                                         String title, String mime, List<String> tags,
                                         Map<String, Object> statsMap) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(existing.get().getId(), title, tags,
                    body, null, null, null, null, mime,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(ctx.tenantId(), ctx.projectName(),
                        outputPath, title, tags, mime, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
            } catch (IOException e) {
                throw new ToolException("Could not write artefact '" + outputPath + "': " + e.getMessage());
            }
        }
        String name = outputPath.substring(outputPath.lastIndexOf('/') + 1).replaceAll("^_|\\.\\w+$", "");
        return new ArtefactResult(name, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()), new LinkedHashMap<>(statsMap));
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
