package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
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
 * {@link VanceApplication} for {@code app: canvasbook} folders — a
 * container of {@code kind: canvas} pages. Owns the single derived
 * artefact, the {@code _index.md} workpage. Page discovery lives in
 * {@link CanvasbookFolderReader}.
 */
@Service
@Slf4j
public class CanvasbookApplication implements VanceApplication {

    public static final String APP_NAME = "canvasbook";
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private final CanvasbookFolderReader folderReader;
    private final CanvasService canvasService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    public CanvasbookApplication(CanvasbookFolderReader folderReader,
                                 CanvasService canvasService,
                                 DocumentService documentService,
                                 DocumentLinkBuilder linkBuilder) {
        this.folderReader = folderReader;
        this.canvasService = canvasService;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        String base = "You are in a canvasbook at `" + ctx.folder() + "` — a container of "
                + "spatial `kind: canvas` boards. The document bound to this chat is the "
                + "board the user currently has open: when they say \"this canvas\", "
                + "\"this node\" or refer to a selection, they mean that file. It is a canvas "
                + "board, NOT a Markdown file — inspect it with `canvas_query` (nodes/edges by "
                + "id) and edit in place with `canvas_node_add/_update/_delete` and "
                + "`canvas_edge_add/_delete`. Add boards with "
                + "`canvasbook_page_create(folder=\"" + ctx.folder() + "\", title=\"...\")` and "
                + "`app_rebuild('" + ctx.folder() + "')` after structural changes. Node/edge "
                + "grammar: `manual_read('canvas')`.";
        String selection = ctx.selection();
        if (selection != null && !selection.isBlank()) {
            base += " The user has selected node(s): " + selection.trim()
                    + " — that is what \"this node\"/\"the selection\" refers to; read them "
                    + "with `canvas_query` and change them with `canvas_node_update` by id.";
        }
        return base;
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = CanvasbookFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + CanvasbookFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException("Manifest already exists at '" + manifestPath
                    + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));
        String landingPage = asString(params.get("landingPage"));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("outputPath", "_index.md");
        Map<String, Object> block = new LinkedHashMap<>();
        if (landingPage != null) block.put("landingPage", landingPage);
        block.put("index", index);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(APP_NAME, block);

        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description, config, new LinkedHashMap<>());
        String manifestBody = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(existing.get().getId(),
                    title != null ? title : "Canvasbook",
                    List.of("application", "canvasbook"),
                    manifestBody, null, null, null, null, YAML_MIME);
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(ctx.tenantId(), ctx.projectName(), manifestPath,
                        title != null ? title : "Canvasbook",
                        List.of("application", "canvasbook"),
                        YAML_MIME, in, ctx.userId());
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath + "': " + e.getMessage());
            }
        }

        // Optional initial pages: params.pages = [{ title, slug }].
        int created = 0;
        Object pagesRaw = params.get("pages");
        if (pagesRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> pm)) continue;
                String pTitle = asString(pm.get("title"));
                String slug = asString(pm.get("slug"));
                if (slug == null) slug = slugify(pTitle != null ? pTitle : "canvas");
                canvasService.create(ctx.tenantId(), ctx.projectName(),
                        folder + "/" + slug, pTitle, null, ctx.userId());
                created++;
            }
        }

        RefreshResult refresh = refresh(new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        log.info("CanvasbookApplication.create tenant='{}' folder='{}' initialPages={}",
                ctx.tenantId(), folder, created);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("pageCount", created);

        String nextStep = "Canvasbook ready. Add boards with "
                + "`canvasbook_page_create(folder=\"" + folder + "\", title=\"...\")`, then "
                + "`canvas_node_add` / `canvas_edge_add` to fill them.";

        return new CreateResult(APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), refresh.artefacts(), nextStep, stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = CanvasbookFolderReader.normaliseFolder(ctx.folder());
        CanvasbookFolderReader.Scan scan =
                folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        String indexBody = renderIndex(scan, title);
        String outputPath = CanvasbookFolderReader.resolveOutputPath(folder, "_index.md");
        DocumentDocument stored = writeArtefact(ctx, outputPath, indexBody, "Index — " + title);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pageCount", scan.pages().size());
        ArtefactResult index = new ArtefactResult(
                "index", stored.getPath(), linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info("CanvasbookApplication.refresh tenant='{}' folder='{}' pages={}",
                ctx.tenantId(), folder, scan.pages().size());
        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    private static String renderIndex(CanvasbookFolderReader.Scan scan, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(title)).append(" — Index\"\n");
        sb.append("description: \"Automatisch generiert aus Canvasbook-Seiten.\"\n");
        sb.append("---\n");
        sb.append("# ").append(title).append("\n\n");
        sb.append("```vance-callout\nseverity: note\ntitle: Auto-generiert\n")
                .append("body: Diese Seite wird bei jedem `app_rebuild` neu geschrieben — ")
                .append("Edits hier gehen verloren.\n```\n\n");
        if (scan.pages().isEmpty()) {
            sb.append("Noch keine Canvas-Seiten in diesem Canvasbook.\n");
            return sb.toString();
        }
        sb.append("## Canvases\n\n");
        for (CanvasbookFolderReader.Page p : scan.pages()) {
            sb.append("- [").append(p.title()).append("](").append(p.relativePath()).append(")\n");
        }
        return sb.toString();
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(),
                    title, List.of("canvasbook", "generated", "index"),
                    body, null, null, null, null, MD_MIME);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(ctx.tenantId(), ctx.projectName(),
                    outputPath, title, List.of("canvasbook", "generated", "index"),
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

    public static String slugify(String s) {
        String base = s.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "canvas" : base;
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
