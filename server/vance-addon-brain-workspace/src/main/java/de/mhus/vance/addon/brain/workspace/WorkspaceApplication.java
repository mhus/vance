package de.mhus.vance.addon.brain.workspace;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: workspace} folders. Owns
 * orchestration of the workspace's single derived artefact: the
 * {@code _index.md} workpage. Page discovery + scanning lives in
 * {@link WorkspaceFolderReader}; rendering in
 * {@link WorkspaceIndexRenderer}.
 */
@Service
@Slf4j
public class WorkspaceApplication implements VanceApplication {

    public static final String APP_NAME = WorkspaceConfig.APP_NAME;
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private final WorkspaceFolderReader folderReader;
    private final WorkspaceIndexRenderer indexRenderer;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final WorkspaceScriptService scriptService;

    public WorkspaceApplication(WorkspaceFolderReader folderReader,
                                WorkspaceIndexRenderer indexRenderer,
                                DocumentService documentService,
                                DocumentLinkBuilder linkBuilder,
                                WorkspaceScriptService scriptService) {
        this.folderReader = folderReader;
        this.indexRenderer = indexRenderer;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.scriptService = scriptService;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "You are in a workspace at `" + ctx.folder() + "`. "
                + "Pages live as `*.workpage.md` files inside this folder. "
                + "Use `workpage_create(path=\"" + ctx.folder() + "/<slug>\", ...)` "
                + "to add a page, `workpage_block_append/_insert/_update` to "
                + "write content, and `app_rebuild('" + ctx.folder() + "')` "
                + "to refresh `_index.md` after structural changes. "
                + "Read `manual_read('workpage-blocks')` for the full block grammar.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = WorkspaceFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + WorkspaceFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        String landingPage = asString(params.get("landingPage"));
        String indexStyle = asString(params.get("indexStyle"));

        // Build manifest body.
        StringBuilder mb = new StringBuilder();
        mb.append("$meta:\n  kind: application\n  app: workspace\n");
        if (title != null) mb.append("title: \"").append(escape(title)).append("\"\n");
        if (description != null) mb.append("description: \"").append(escape(description)).append("\"\n");
        mb.append("workspace:\n");
        if (landingPage != null) mb.append("  landingPage: ").append(landingPage).append("\n");
        mb.append("  index:\n")
                .append("    outputPath: _index.md\n")
                .append("    style: ").append(indexStyle != null ? indexStyle.toLowerCase(Locale.ROOT) : "cards").append("\n")
                .append("    showDescriptions: true\n")
                .append("    groupBySection: true\n");
        mb.append("  defaultPageKind: workpage\n");
        String manifestBody = mb.toString();

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Workspace",
                    List.of("application", "workspace"),
                    manifestBody, null, null, null, null, YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Workspace",
                        List.of("application", "workspace"),
                        YAML_MIME, in, ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        // First refresh to seed an _index.md (even if empty).
        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder,
                ctx.userId(), ctx.processId());
        RefreshResult refresh = refresh(rc);

        log.info("WorkspaceApplication.create tenant='{}' folder='{}'",
                ctx.tenantId(), folder);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("pageCount", refresh.artefacts().isEmpty() ? 0
                : refresh.artefacts().get(0).stats().getOrDefault("pageCount", 0));

        String nextStep = "Workspace ready. Add pages with "
                + "`workpage_create(path=\"" + folder + "/<slug>\", title=\"...\", blocks=[...])` "
                + "then `app_rebuild('" + folder + "')` to refresh the index.";

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(),
                refresh.artefacts(),
                nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = WorkspaceFolderReader.normaliseFolder(ctx.folder());
        WorkspaceFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.manifest().getTitle();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        String indexBody = indexRenderer.render(scan, title);
        String outputPath = WorkspaceFolderReader.resolveOutputPath(
                folder, scan.config().index().outputPath());
        DocumentDocument stored = writeArtefact(
                ctx, outputPath, indexBody, "Index — " + title);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pageCount", scan.pages().size());
        long sectionCount = scan.pages().stream()
                .map(WorkspacePage::section)
                .filter(s -> !s.isEmpty())
                .distinct().count();
        stats.put("sectionCount", sectionCount);

        // Run the scripts each page opted into via $meta.rebuildScripts —
        // NOT every script found in the folder. A failing script is logged
        // and skipped so it doesn't block the rest of the rebuild.
        int scriptsRun = 0;
        int scriptsFailed = 0;
        for (WorkspacePage page : scan.pages()) {
            for (String ref : page.rebuildScripts()) {
                String scriptPath = WorkspaceFormService.resolveRelative(
                        page.doc().getPath(), WorkspaceFormService.stripVanceScheme(ref));
                try {
                    scriptService.run(ctx.tenantId(), ctx.projectName(), scriptPath, ctx.userId());
                    scriptsRun++;
                } catch (RuntimeException e) {
                    scriptsFailed++;
                    log.warn("WorkspaceApplication.refresh rebuild-script failed "
                                    + "tenant='{}' page='{}' script='{}': {}",
                            ctx.tenantId(), page.doc().getPath(), scriptPath, e.getMessage());
                }
            }
        }
        stats.put("scriptsRun", scriptsRun);
        if (scriptsFailed > 0) stats.put("scriptsFailed", scriptsFailed);

        ArtefactResult index = new ArtefactResult(
                "index", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                stats);

        log.info("WorkspaceApplication.refresh tenant='{}' folder='{}' pages={} scriptsRun={} scriptsFailed={}",
                ctx.tenantId(), folder, scan.pages().size(), scriptsRun, scriptsFailed);

        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title) {
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title, List.of("workspace", "generated", "index"),
                    body, null, null, null, null, MD_MIME);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(),
                    outputPath, title,
                    List.of("workspace", "generated", "index"),
                    MD_MIME, in, ctx.userId());
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
