package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: designer} — a folder of
 * designs.
 *
 * <p>A designer app owns no data beyond its folder: every design is a
 * subfolder of ordinary documents (web files written with the standard
 * document tools), and the preview is served by
 * {@link DesignerContentController} straight from that tree. The only
 * derived artefact is the {@code _index.md} catalogue, which exists so
 * the collection is readable from anywhere that renders markdown —
 * chat, a workpage embed, an export — and not only inside the app.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DesignerApplication implements VanceApplication {

    public static final String APP_NAME = "designer";

    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    /**
     * Starter-design entry file. Plain HTML, English (runtime strings are
     * English; a design's language is the author's choice, not the system's).
     * The relative {@code style.css} link is the point of the seed: it proves
     * sub-resource resolution inside the sandboxed preview.
     */
    private static final String HELLO_INDEX = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Hello Design</title>
              <link rel="stylesheet" href="style.css">
            </head>
            <body>
              <main>
                <h1>Hello, design.</h1>
                <p>
                  This is the seeded starter design — a folder of plain web files.
                  Its stylesheet loads <em>relatively</em>, exactly like your own
                  files will. Edit <code>hello/index.html</code> and
                  <code>hello/style.css</code>, or add a new design folder next to
                  <code>hello/</code> with its own <code>index.html</code>.
                </p>
              </main>
            </body>
            </html>
            """.stripIndent();

    private static final String HELLO_STYLE = """
            :root {
              --bg: #faf9f7;
              --ink: #1f2933;
              --accent: #0f766e;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
              background: var(--bg);
              color: var(--ink);
              display: grid;
              place-items: center;
              min-height: 100vh;
            }
            main {
              max-width: 40rem;
              padding: 2rem;
              background: #ffffff;
              border-radius: 1rem;
              box-shadow: 0 10px 30px rgb(0 0 0 / 8%);
            }
            h1 {
              margin: 0 0 0.5rem;
              color: var(--accent);
            }
            code {
              background: #eef2f1;
              padding: 0.1em 0.35em;
              border-radius: 0.25rem;
            }
            """.stripIndent();

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;
    private final DesignerFolderReader folderReader;
    private final DocumentLinkBuilder linkBuilder;

    @Override
    public String appName() {
        return APP_NAME;
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder;
        try {
            folder = DesignerPaths.normaliseFolder(ctx.folder());
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage(), e);
        }
        Map<String, Object> params = ctx.params() == null ? Map.of() : ctx.params();
        String manifestPath = DesignerPaths.manifestPath(folder);

        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));

        // The config block is per-convention keyed by the app name; the
        // designer app has nothing to configure yet, so the block carries
        // only the artefact path so the format is stable from day one.
        Map<String, Object> designerBlock = new LinkedHashMap<>();
        designerBlock.put("index", Map.of("outputPath", DesignerPaths.INDEX_OUTPUT_PATH));
        Map<String, Object> appConfig = new LinkedHashMap<>();
        appConfig.put(APP_NAME, designerBlock);

        ApplicationDocument manifest =
                new ApplicationDocument("application", APP_NAME, title, description, appConfig, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Designer",
                    List.of("application", APP_NAME),
                    body,
                    null,
                    null,
                    null,
                    null,
                    YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(),
                        ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Designer",
                        List.of("application", APP_NAME),
                        YAML_MIME,
                        in,
                        ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException("Could not write manifest '" + manifestPath + "': " + e.getMessage(), e);
            }
        }

        // Seed a starter design when the folder has none — the scaffold's
        // acceptance is the same as Bistromath's Hello World: something
        // visibly running, not a memo about itself. The demo also exercises
        // the one contract that is not obvious from a single file: a relative
        // stylesheet load inside the sandboxed preview.
        boolean seeded = false;
        if (folderReader
                .scan(ctx.tenantId(), ctx.projectName(), folder)
                .designs()
                .isEmpty()) {
            seedHelloDesign(ctx, folder);
            seeded = true;
        }

        RefreshResult refresh =
                refresh(new RefreshContext(ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        log.info("DesignerApplication.create tenant='{}' folder='{}'", ctx.tenantId(), folder);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        int designCount = refresh.artefacts().isEmpty()
                ? 0
                : (int) refresh.artefacts().get(0).stats().getOrDefault("designCount", 0);
        stats.put("designCount", designCount);
        String nextStep = seeded
                ? "Designer app ready with a starter design 'hello' — open the app "
                        + "to see it live, then iterate or add designs by writing web files "
                        + "with doc_write under '" + folder + "/<design-name>/index.html'. "
                        + "Details: manual_read('app-designer')."
                : "Designer app ready. Create a design by writing web files with doc_write "
                        + "under '" + folder + "/<design-name>/index.html' (plus css/js as "
                        + "you like) — it shows up in the app preview. Details: "
                        + "manual_read('app-designer').";
        return new CreateResult(
                APP_NAME,
                folder,
                stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(),
                refresh.artefacts(),
                nextStep,
                stats);
    }

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        String folder = DesignerPaths.normaliseFolder(ctx.folder());
        DesignerFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);

        String title = leafFolderName(folder);
        DocumentDocument stored = writeArtefact(
                ctx.tenantId(),
                ctx.projectName(),
                folder + "/" + DesignerPaths.INDEX_OUTPUT_PATH,
                "Designs — " + title,
                MD_MIME,
                renderIndex(scan),
                List.of(APP_NAME, "generated", "index"),
                ctx.userId());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("designCount", scan.designs().size());
        ArtefactResult index =
                new ArtefactResult("index", stored.getPath(), linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info(
                "DesignerApplication.refresh tenant='{}' folder='{}' designs={}",
                ctx.tenantId(),
                folder,
                scan.designs().size());
        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🎨", null);
    }

    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        try {
            DesignerFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), ctx.folder());
            List<StatusItem> items = scan.designs().stream()
                    .limit(8)
                    .map(d -> new StatusItem(
                            d.displayTitle(), d.fileCount() + " file" + (d.fileCount() == 1 ? "" : "s"), null, null))
                    .toList();
            return Optional.of(new AppStatus(
                    scan.designs().size() + (scan.designs().size() == 1 ? " design" : " designs"),
                    StatusSeverity.OK,
                    List.of(new StatusMetric(
                            "Designs", Integer.toString(scan.designs().size()))),
                    items,
                    null));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<AppTarget> targets(TargetsContext ctx) {
        try {
            return folderReader.scan(ctx.tenantId(), ctx.projectName(), ctx.folder()).designs().stream()
                    .map(d -> AppTarget.of(d.name(), d.displayTitle()))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public @Nullable String promptInject(PromptInjectContext ctx) {
        DesignerFolderReader.Scan scan;
        try {
            scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), DesignerPaths.normaliseFolder(ctx.folder()));
        } catch (RuntimeException e) {
            return null;
        }
        String folder = DesignerPaths.normaliseFolder(ctx.folder());
        StringBuilder sb = new StringBuilder();
        sb.append("You are in a designer app at `")
                .append(folder)
                .append("` — a collection of web page designs. ")
                .append(scan.designs().size())
                .append(" design(s)");
        if (!scan.designs().isEmpty()) {
            sb.append(": ");
            List<String> names = scan.designs().stream()
                    .limit(12)
                    .map(d -> "`" + d.name() + "`")
                    .toList();
            sb.append(String.join(", ", names));
            if (scan.designs().size() > 12) sb.append(", …");
        }
        sb.append(".\n")
                .append("Each design is a folder of plain web files with entry `index.html` ")
                .append("(plus `style.css`, `script.js`, assets — all ordinary documents). ")
                .append("Create a design with `doc_write(path=\"")
                .append(folder)
                .append("/<design>/index.html\", content=…)`, add files the same way. ")
                .append("The design preview in the app updates on reload. ")
                .append("After creating or restructuring designs run ")
                .append("`designer_validate(folder=\"")
                .append(folder)
                .append("\")` ")
                .append("(it reports entry-file typos and broken design.yaml, which ")
                .append("the app silently hides). ")
                .append("Optional per-design metadata: `design.yaml` with `title` and ")
                .append("`description`. Skills tagged `design` are design blueprints — ")
                .append("check for an active one before designing from scratch. ")
                .append("Authoring rules and the file layout: ")
                .append("`manual_read('app-designer')`.\n");
        appendOpenDesign(sb, scan, folder, ctx.selection());
        return sb.toString();
    }

    /**
     * What the preview is showing, so "this design" / "make the headline
     * bigger" is a sentence the model can act on without guessing.
     *
     * <p>The client sends only the design name. Everything shown here is
     * read off the scan, because the documents are what the tools will
     * edit — a hint that carried its own copy of the title could describe
     * a design that no longer looks like that. Unknown names (stale tab,
     * design deleted in another tab) fall away silently: the answer to
     * "this design" is then the catalogue, which the chunk already lists.
     *
     * <p>The wording avoids the word "selection" on purpose. To a chat
     * model that word means a text range somebody marked; here nothing
     * was marked, and the lesson from the links app is that the model
     * duly answers "I cannot read your selection" unless the phrasing
     * says what actually happened: a design is open.
     */
    private void appendOpenDesign(
            StringBuilder sb, DesignerFolderReader.Scan scan, String folder, @Nullable String openDesign) {
        if (openDesign == null || openDesign.isBlank()) {
            return;
        }
        scan.find(openDesign.strip()).ifPresent(design -> {
            sb.append("The design currently open in the preview is `")
                    .append(design.name())
                    .append("`");
            if (design.title() != null
                    && !design.title().isBlank()
                    && !design.title().equals(design.name())) {
                sb.append(" (").append(design.title()).append(")");
            }
            sb.append(" — requests like \"this design\", \"the headline\" ")
                    .append("refer to it. Its files (paths relative to the design ")
                    .append("folder): ");
            for (int i = 0; i < design.files().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("`").append(design.files().get(i)).append("`");
            }
            sb.append(". Edit them with `doc_write` under `")
                    .append(folder)
                    .append("/")
                    .append(design.name())
                    .append("/`.\n");
        });
    }

    // ── Design operations (server side of the app's mutation endpoints) ──

    /**
     * Creates a design folder: entry file plus, when metadata is given, a
     * {@code design.yaml}. The name rule is the one the content route
     * enforces — single plain segment, no reserved {@code _} prefix.
     */
    public void createDesign(
            String tenantId,
            String projectId,
            String folder,
            String name,
            @Nullable String title,
            @Nullable String description,
            @Nullable String userId) {
        String f = DesignerPaths.normaliseFolder(folder);
        String entryPath = DesignerPaths.resolveFile(f, name, "")
                .orElseThrow(() -> new ToolException(
                        "invalid design name '" + name + "' — one path segment, no dots, " + "no reserved '_' prefix"));
        if (documentService.findByPath(tenantId, projectId, entryPath).isPresent()) {
            throw new ToolException("design '" + name + "' already exists");
        }
        String displayTitle = title == null || title.isBlank() ? name : title.trim();
        writeArtefact(
                tenantId,
                projectId,
                entryPath,
                displayTitle,
                "text/html",
                renderEntryStub(displayTitle),
                List.of(APP_NAME, "design"),
                userId);
        if ((title != null && !title.isBlank()) || (description != null && !description.isBlank())) {
            writeDesignMeta(tenantId, projectId, f, name, title, description, userId);
        }
        log.info("DesignerApplication.createDesign tenant='{}' folder='{}' design='{}'", tenantId, f, name);
    }

    /**
     * Moves every document of one design to the trash — the recoverable
     * delete, matching what the document editor's delete does.
     */
    public int deleteDesign(String tenantId, String projectId, String folder, String name, @Nullable String userId) {
        String f = DesignerPaths.normaliseFolder(folder);
        if (DesignerPaths.resolveFile(f, name, "").isEmpty()) {
            throw new ToolException("invalid design name '" + name + "'");
        }
        String prefix = f + "/" + name + "/";
        List<DocumentDocument> docs = documentService.listUnderFolder(tenantId, projectId, prefix);
        if (docs.isEmpty()) {
            throw new ToolException("no such design '" + name + "'");
        }
        var actor = contextFactory.writeActor(tenantId, userId, prefix);
        for (DocumentDocument doc : docs) {
            documentService.trash(doc.getId(), actor);
        }
        log.info(
                "DesignerApplication.deleteDesign tenant='{}' folder='{}' design='{}' docs={}",
                tenantId,
                f,
                name,
                docs.size());
        return docs.size();
    }

    /**
     * Persists the design order into the manifest's
     * {@code config.designer.order}. Names that are not designs are
     * dropped — the list a client sends is derived from a catalogue that
     * may be stale, and a stale name must not pin a ghost position.
     */
    public void reorder(String tenantId, String projectId, String folder, List<String> order, @Nullable String userId) {
        String f = DesignerPaths.normaliseFolder(folder);
        String manifestPath = DesignerPaths.manifestPath(f);
        DocumentDocument manifestDoc = documentService
                .findByPath(tenantId, projectId, manifestPath)
                .orElseThrow(() -> new ToolException("no designer app manifest at '" + manifestPath + "'"));
        ApplicationDocument app =
                ApplicationCodec.parse(documentService.readContent(manifestDoc), manifestDoc.getMimeType());

        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        folderReader.scan(tenantId, projectId, f).designs().forEach(d -> known.add(d.name()));
        List<String> cleaned = order.stream().filter(known::contains).distinct().toList();

        Map<String, Object> config = new LinkedHashMap<>(app.config());
        @SuppressWarnings("unchecked")
        Map<String, Object> designer =
                new LinkedHashMap<>((Map<String, Object>) config.getOrDefault(APP_NAME, Map.of()));
        if (cleaned.isEmpty()) {
            designer.remove("order");
        } else {
            designer.put("order", cleaned);
        }
        config.put(APP_NAME, designer);
        ApplicationDocument updated =
                new ApplicationDocument(app.kind(), app.app(), app.title(), app.description(), config, app.extra());
        documentService.update(
                manifestDoc.getId(),
                manifestDoc.getTitle(),
                manifestDoc.getTags(),
                ApplicationCodec.serialize(updated, manifestDoc.getMimeType()),
                null,
                null,
                null,
                null,
                manifestDoc.getMimeType(),
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(tenantId, userId, manifestPath));
        log.debug("DesignerApplication.reorder tenant='{}' folder='{}' order={}", tenantId, f, cleaned);
    }

    /**
     * Upserts the {@code design.yaml} of one design: blank values remove
     * their key, foreign keys survive. No file is written when nothing
     * would change it.
     */
    public void updateDesignMeta(
            String tenantId,
            String projectId,
            String folder,
            String name,
            @Nullable String title,
            @Nullable String description,
            @Nullable String userId) {
        String f = DesignerPaths.normaliseFolder(folder);
        if (documentService
                .findByPath(
                        tenantId,
                        projectId,
                        DesignerPaths.resolveFile(f, name, "").orElse(""))
                .isEmpty()) {
            throw new ToolException("no such design '" + name + "'");
        }
        writeDesignMeta(tenantId, projectId, f, name, title, description, userId);
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Writes the starter design {@code hello/} — the scaffold's acceptance
     * is something visibly running (the Bistromath Hello-World rule), and its
     * relative stylesheet reference demonstrates the one contract a single
     * file cannot show: sub-resource loads inside the sandboxed preview.
     */
    private void seedHelloDesign(CreateContext ctx, String folder) {
        String base = folder + "/hello/";
        writeArtefact(
                ctx.tenantId(),
                ctx.projectName(),
                base + "index.html",
                "Hello",
                "text/html",
                HELLO_INDEX,
                List.of(APP_NAME, "design", "seed"),
                ctx.userId());
        writeArtefact(
                ctx.tenantId(),
                ctx.projectName(),
                base + "style.css",
                "Hello",
                "text/css",
                HELLO_STYLE,
                List.of(APP_NAME, "design", "seed"),
                ctx.userId());
        writeArtefact(
                ctx.tenantId(),
                ctx.projectName(),
                base + "design.yaml",
                "Hello",
                YAML_MIME,
                "title: Hello Design\ndescription: The seeded starter design — edit or replace it.\n",
                List.of(APP_NAME, "design", "seed"),
                ctx.userId());
        log.info("DesignerApplication seeded starter design at '{}'", base);
    }

    private String renderIndex(DesignerFolderReader.Scan scan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Designs\n\n");
        if (scan.designs().isEmpty()) {
            sb.append("No designs yet — a design is a subfolder with an `index.html`.\n");
            return sb.toString();
        }
        for (DesignerFolderReader.DesignScan design : scan.designs()) {
            sb.append("## ").append(design.displayTitle()).append("\n\n");
            if (design.description() != null) {
                sb.append(design.description()).append("\n\n");
            }
            for (String file : design.files()) {
                sb.append("- `").append(file).append("`\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * A minimal entry file for a freshly created design. Escaped — the
     * title is user input and must not break out of the HTML.
     */
    private static String renderEntryStub(String title) {
        return "<!doctype html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"utf-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "  <title>" + escapeHtml(title) + "</title>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <h1>" + escapeHtml(title) + "</h1>\n"
                + "  <p>Edit this file, add a style.css next to it — the app preview reloads.</p>\n"
                + "</body>\n"
                + "</html>\n";
    }

    /**
     * Upserts {@code <design>/design.yaml}: blank values remove their key,
     * foreign keys survive. Writes only when something would change.
     */
    private void writeDesignMeta(
            String tenantId,
            String projectId,
            String folder,
            String name,
            @Nullable String title,
            @Nullable String description,
            @Nullable String userId) {
        String metaPath = folder + "/" + name + "/" + DesignerPaths.DESIGN_META_FILE;
        Map<String, Object> meta = new LinkedHashMap<>();
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, metaPath);
        if (existing.isPresent()) {
            try {
                meta.putAll(KindHeaderCodec.parseYamlBody(documentService.readContent(existing.get())));
            } catch (RuntimeException e) {
                // A broken file the edit replaces — starting from the two keys
                // the app owns is the useful answer to hand-edited garbage.
            }
        }
        boolean titleSet = title != null && !title.isBlank();
        boolean descriptionSet = description != null && !description.isBlank();
        if (titleSet) {
            meta.put("title", title.trim());
        } else {
            meta.remove("title");
        }
        if (descriptionSet) {
            meta.put("description", description.trim());
        } else {
            meta.remove("description");
        }
        if (meta.isEmpty() && existing.isEmpty()) {
            return;
        }
        writeArtefact(
                tenantId, projectId, metaPath, name, YAML_MIME, dumpYaml(meta), List.of(APP_NAME, "design"), userId);
    }

    /** Plain block-style YAML — the same dumper settings KindHeaderCodec uses. */
    private static String dumpYaml(Map<String, Object> body) {
        org.yaml.snakeyaml.DumperOptions opts = new org.yaml.snakeyaml.DumperOptions();
        opts.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setSplitLines(false);
        return new org.yaml.snakeyaml.Yaml(opts).dump(body);
    }

    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private DocumentDocument writeArtefact(
            String tenantId,
            String projectId,
            String path,
            String title,
            String mimeType,
            String body,
            List<String> kinds,
            @Nullable String userId) {
        var actor = contextFactory.writeActor(tenantId, userId, path);
        Optional<DocumentDocument> existing = documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title,
                    kinds,
                    body,
                    null,
                    null,
                    null,
                    null,
                    mimeType,
                    DocumentService.TOOL_IDENTITY,
                    actor);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(tenantId, projectId, path, title, kinds, mimeType, in, userId, actor);
        } catch (IOException e) {
            throw new ToolException("Could not write artefact '" + path + "': " + e.getMessage(), e);
        }
    }

    private static @Nullable String asString(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }
}
