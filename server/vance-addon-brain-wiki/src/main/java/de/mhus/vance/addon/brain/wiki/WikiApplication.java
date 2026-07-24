package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
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
 * {@link VanceApplication} for {@code app: wiki} folders. Owns the wiki's
 * derived artefacts: one {@code _index.md} per space plus a single
 * {@code _backlinks.yaml} at the root. Page discovery + link extraction
 * lives in {@link WikiFolderReader}; resolution / backlink-graph in
 * {@link WikiService}; rendering in {@link WikiIndexRenderer} /
 * {@link WikiBacklinksRenderer}.
 */
@Service
@Slf4j
public class WikiApplication implements VanceApplication {

    public static final String APP_NAME = WikiConfig.APP_NAME;
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";
    private static final String BACKLINKS_FILE = "_backlinks.yaml";

    private final WikiFolderReader folderReader;
    private final WikiService wikiService;
    private final WikiIndexRenderer indexRenderer;
    private final WikiBacklinksRenderer backlinksRenderer;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;

    public WikiApplication(WikiFolderReader folderReader,
                           WikiService wikiService,
                           WikiIndexRenderer indexRenderer,
                           WikiBacklinksRenderer backlinksRenderer,
                           DocumentService documentService,
                           DocumentLinkBuilder linkBuilder,
                           SecurityContextFactory contextFactory) {
        this.folderReader = folderReader;
        this.wikiService = wikiService;
        this.indexRenderer = indexRenderer;
        this.backlinksRenderer = backlinksRenderer;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in a wiki at `" + ctx.folder() + "`. "
                + "Pages are `kind: workpage` Markdown files; spaces are sub-folders. "
                + "Link pages by name with `[[Target]]` / `[[Target|Label]]` / `[[Space/Target]]` — "
                + "a missing target renders as a red link that creates the page on click. "
                + "Each space has a curated `main.md` (home) and a generated `_index.md` "
                + "(don't edit `_index.md` — it's rewritten on rebuild). "
                + "Use `wikipage_create(folder=\"" + ctx.folder() + "\", title=..., space=...)` "
                + "to add a page and `app_rebuild('" + ctx.folder() + "')` to regenerate every "
                + "`_index.md` and the `_backlinks.yaml`. "
                + "Read `manual_read('app-wiki')` for the full model.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = WikiFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + WikiFolderReader.APP_MANIFEST;

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
        mb.append("$meta:\n  kind: application\n  app: wiki\n");
        if (title != null) mb.append("title: \"").append(escape(title)).append("\"\n");
        if (description != null) mb.append("description: \"").append(escape(description)).append("\"\n");
        mb.append("wiki:\n");
        mb.append("  index:\n")
                .append("    outputPath: _index.md\n")
                .append("    showDescriptions: true\n");
        mb.append("  recentLimit: ").append(WikiConfig.DEFAULT_RECENT_LIMIT).append("\n");
        mb.append("  defaultPageKind: workpage\n");
        String manifestBody = mb.toString();

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Wiki",
                    List.of("application", "wiki"),
                    manifestBody, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Wiki",
                        List.of("application", "wiki"),
                        YAML_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        // Seed the curated root home page if absent.
        seedMainIfAbsent(ctx.tenantId(), ctx.projectName(), folder,
                title != null ? title : "Home", ctx.userId());

        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId());
        RefreshResult refresh = refresh(rc);

        log.info("WikiApplication.create tenant='{}' folder='{}'", ctx.tenantId(), folder);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("artefactCount", refresh.artefacts().size());

        String nextStep = "Wiki ready. Add pages with "
                + "`wikipage_create(folder=\"" + folder + "\", title=\"...\", space=\"...\")`, "
                + "link them with `[[Title]]`, then `app_rebuild('" + folder + "')` "
                + "to regenerate the indexes + backlinks.";

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(),
                refresh.artefacts(),
                nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = WikiFolderReader.normaliseFolder(ctx.folder());
        WikiFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        // Ensure the curated root home exists. Wikis created via the
        // document-template path (only _app.yaml) have no `main` yet — seed
        // it so every wiki has a home and the default landing is `main`, not
        // the generated `_index`. Never overwrites an existing main; re-scan
        // so the fresh page is included in the generated indexes.
        if (seedMainIfAbsent(ctx.tenantId(), ctx.projectName(), folder, title, ctx.userId())) {
            scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);
        }

        List<ArtefactResult> artefacts = new ArrayList<>();

        // One _index.md per space (root + every space).
        for (String space : scan.spaces()) {
            String indexBody = indexRenderer.render(scan, space, title);
            String outputPath = WikiFolderReader.indexPathFor(
                    folder, space, scan.config().index().outputPath());
            String label = space.isEmpty()
                    ? "Index — " + title
                    : "Index — " + WikiFolderReader.humanise(space.replace('/', ' '));
            DocumentDocument stored = writeArtefact(ctx, outputPath, indexBody, label, MD_MIME,
                    List.of("wiki", "generated", "index"));
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("space", space);
            stats.put("pageCount", wikiService.pagesInSpace(scan, space).size());
            artefacts.add(new ArtefactResult(
                    space.isEmpty() ? "index" : "index:" + space,
                    stored.getPath(),
                    linkBuilder.linkFor(stored, ctx.projectName()),
                    stats));
        }

        // Global backlink graph.
        Map<String, List<String>> graph = wikiService.buildBacklinks(scan);
        String backlinksBody = backlinksRenderer.render(scan, graph);
        String backlinksPath = folder + "/" + BACKLINKS_FILE;
        DocumentDocument backlinks = writeArtefact(ctx, backlinksPath, backlinksBody,
                "Backlinks — " + title, YAML_MIME, List.of("wiki", "generated", "backlinks"));
        Map<String, Object> backlinkStats = new LinkedHashMap<>();
        backlinkStats.put("targetCount", graph.size());
        artefacts.add(new ArtefactResult(
                "backlinks", backlinks.getPath(),
                linkBuilder.linkFor(backlinks, ctx.projectName()),
                backlinkStats));

        log.info("WikiApplication.refresh tenant='{}' folder='{}' spaces={} pages={} backlinkTargets={}",
                ctx.tenantId(), folder, scan.spaces().size(), scan.pages().size(), graph.size());

        return new RefreshResult(APP_NAME, folder, artefacts);
    }

    /**
     * Seed the curated root {@code main.md} home page when it doesn't exist.
     * Returns {@code true} when a page was created (caller should re-scan).
     * Never overwrites — {@code main} is user-curated.
     */
    private boolean seedMainIfAbsent(String tenant, String project, String folder,
                                     String title, @Nullable String userId) {
        String mainPath = folder + "/" + WikiFolderReader.MAIN_PAGE + WikiFolderReader.PAGE_EXTENSION;
        if (documentService.findByPath(tenant, project, mainPath).isPresent()) return false;
        String homeBody = WikiService.workpageStub(title);
        try (InputStream in = new ByteArrayInputStream(homeBody.getBytes(StandardCharsets.UTF_8))) {
            documentService.create(tenant, project, mainPath, title,
                    List.of("wiki", "workpage"), MD_MIME, in, userId,
                    contextFactory.writeActor(tenant, userId, mainPath));
            log.info("WikiApplication.seedMain tenant='{}' path='{}'", tenant, mainPath);
            return true;
        } catch (IOException e) {
            throw new ToolException(
                    "Could not seed home page '" + mainPath + "': " + e.getMessage());
        }
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title, String mime,
                                           List<String> tags) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(), title, tags,
                    body, null, null, null, null, mime,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    outputPath, title, tags, mime, in, ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static String asString(Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
