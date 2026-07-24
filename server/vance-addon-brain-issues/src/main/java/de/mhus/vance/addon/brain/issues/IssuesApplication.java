package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: issues} folders. Owns the derived
 * artefacts {@code _index.md} + {@code _stats.yaml} (active issues only —
 * archived ones under {@code archive/} are excluded).
 */
@Service
@Slf4j
public class IssuesApplication implements VanceApplication {

    public static final String APP_NAME = IssuesConfig.APP_NAME;
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";
    private static final String INDEX_FILE = "_index.md";
    private static final String STATS_FILE = "_stats.yaml";
    private static final List<String> DEFAULT_LABELS = List.of("bug", "feature", "question");

    private final IssuesFolderReader folderReader;
    private final IssuesStatsBuilder statsBuilder;
    private final IssuesRenderer renderer;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public IssuesApplication(IssuesFolderReader folderReader,
                             IssuesStatsBuilder statsBuilder,
                             IssuesRenderer renderer,
                             DocumentService documentService,
                             DocumentLinkBuilder linkBuilder,
                             de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.folderReader = folderReader;
        this.statsBuilder = statsBuilder;
        this.renderer = renderer;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in an issue tracker at `" + ctx.folder() + "` (GitHub-Issues-style). "
                + "Issues are `kind: issue` files under `items/` with a stable number (#N), an "
                + "`open`/`closed` state (a field, not a folder), labels and an optional assignee. "
                + "Create with `issue_create(folder=\"" + ctx.folder() + "\", title=...)` (the number "
                + "is assigned automatically); change state/labels with `issue_update`; add to the "
                + "discussion with `issue_comment`; tidy old ones with `issue_update(archived=true)` "
                + "(moves them to `archive/`, out of the active list). Search with `issue_search`, "
                + "list with `issue_query`, and `app_rebuild('" + ctx.folder() + "')` regenerates the "
                + "index + stats. Read `manual_read('app-issues')` for the model.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = IssuesFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + IssuesFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '" + manifestPath
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        IssuesConfig config = new IssuesConfig(title, description,
                IssuesConfig.DEFAULT_ITEMS_DIR, IssuesConfig.DEFAULT_ARCHIVE_DIR,
                1, new ArrayList<>(DEFAULT_LABELS));
        String manifestBody = config.render();

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(existing.get().getId(),
                    title != null ? title : "Issues", List.of("application", "issues"),
                    manifestBody, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(ctx.tenantId(), ctx.projectName(), manifestPath,
                        title != null ? title : "Issues", List.of("application", "issues"),
                        YAML_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException("Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        RefreshResult refresh = refresh(new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("artefactCount", refresh.artefacts().size());
        String nextStep = "Issue tracker ready. Add issues with "
                + "`issue_create(folder=\"" + folder + "\", title=\"...\")`.";
        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), refresh.artefacts(), nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = IssuesFolderReader.normaliseFolder(ctx.folder());
        IssuesFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);
        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);
        IssuesStatsBuilder.Stats stats = statsBuilder.build(scan);

        List<ArtefactResult> artefacts = new ArrayList<>();
        DocumentDocument index = writeArtefact(ctx, folder + "/" + INDEX_FILE,
                renderer.renderIndex(scan, title), "Issues — " + title, MD_MIME,
                List.of("issues", "generated", "index"));
        Map<String, Object> is = new LinkedHashMap<>();
        is.put("open", stats.open());
        is.put("closed", stats.closed());
        artefacts.add(new ArtefactResult("index", index.getPath(),
                linkBuilder.linkFor(index, ctx.projectName()), is));

        DocumentDocument statsDoc = writeArtefact(ctx, folder + "/" + STATS_FILE,
                renderer.renderStats(folder, stats), "Stats — " + title, YAML_MIME,
                List.of("issues", "generated", "stats"));
        artefacts.add(new ArtefactResult("stats", statsDoc.getPath(),
                linkBuilder.linkFor(statsDoc, ctx.projectName()),
                Map.of("total", stats.total())));

        log.info("IssuesApplication.refresh tenant='{}' folder='{}' open={} closed={}",
                ctx.tenantId(), folder, stats.open(), stats.closed());
        return new RefreshResult(APP_NAME, folder, artefacts);
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath, String body,
                                           String title, String mime, List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(), title, tags,
                    body, null, null, null, null, mime,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(ctx.tenantId(), ctx.projectName(),
                    outputPath, title, tags, mime, in, ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } catch (IOException e) {
            throw new ToolException("Could not write artefact '" + outputPath + "': " + e.getMessage());
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
}
