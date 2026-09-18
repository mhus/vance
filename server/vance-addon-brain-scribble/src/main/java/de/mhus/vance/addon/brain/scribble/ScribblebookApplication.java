package de.mhus.vance.addon.brain.scribble;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link VanceApplication} for {@code app: scribblebook} folders — a
 * container of {@code kind: scribble} handwriting sheets. Owns the single
 * derived artefact, the {@code _index.md} workpage. Sheet discovery lives
 * in {@link ScribblebookFolderReader}.
 */
@Service
@Slf4j
public class ScribblebookApplication implements VanceApplication {

    public static final String APP_NAME = "scribblebook";
    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";

    private final ScribblebookFolderReader folderReader;
    private final ScribbleService scribbleService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;

    public ScribblebookApplication(
            ScribblebookFolderReader folderReader,
            ScribbleService scribbleService,
            DocumentService documentService,
            DocumentLinkBuilder linkBuilder,
            SecurityContextFactory contextFactory) {
        this.folderReader = folderReader;
        this.scribbleService = scribbleService;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override
    public String appName() {
        return APP_NAME;
    }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        // Deliberately short (planning/scribble.md §9.2): an agent cannot read
        // ink, so the prompt says what the sheet is and where text would live.
        return "You are in a scribblebook at `" + ctx.folder() + "` — a container of "
                + "handwritten `kind: scribble` sheets. The document bound to this chat is "
                + "the sheet the user currently has open. It is handwriting, NOT text — "
                + "you cannot read its ink content; check structure with "
                + "`scribble_validate(path)` and structure questions against titles. Add "
                + "sheets with `scribblebook_page_create(folder=\"" + ctx.folder()
                + "\", title=\"...\")` and `app_rebuild('" + ctx.folder() + "')` after "
                + "structural changes.";
    }

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = ScribblebookFolderReader.normaliseFolder(ctx.folder());
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + ScribblebookFolderReader.APP_MANIFEST;

        Optional<DocumentDocument> existing =
                documentService.findByPath(ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath + "'. Pass overwrite=true to replace it.");
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

        ApplicationDocument manifest =
                new ApplicationDocument("application", APP_NAME, title, description, config, new LinkedHashMap<>());
        String manifestBody = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(),
                    title != null ? title : "Scribblebook",
                    List.of("application", "scribblebook"),
                    manifestBody,
                    null,
                    null,
                    null,
                    null,
                    YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(),
                        ctx.projectName(),
                        manifestPath,
                        title != null ? title : "Scribblebook",
                        List.of("application", "scribblebook"),
                        YAML_MIME,
                        in,
                        ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException("Could not write manifest '" + manifestPath + "': " + e.getMessage(), e);
            }
        }

        // Optional initial sheets: params.pages = [{ title, slug }].
        int created = 0;
        Object pagesRaw = params.get("pages");
        if (pagesRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> pm)) continue;
                String pTitle = asString(pm.get("title"));
                String slug = asString(pm.get("slug"));
                if (slug == null) slug = slugify(pTitle != null ? pTitle : "sheet");
                scribbleService.create(ctx.tenantId(), ctx.projectName(), folder + "/" + slug, pTitle, ctx.userId());
                created++;
            }
        }

        RefreshResult refresh =
                refresh(new RefreshContext(ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId()));

        log.info(
                "ScribblebookApplication.create tenant='{}' folder='{}' initialSheets={}",
                ctx.tenantId(),
                folder,
                created);

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);
        stats.put("pageCount", created);

        String nextStep = "Scribblebook ready. Add sheets with "
                + "`scribblebook_page_create(folder=\"" + folder + "\", title=\"...\")` — "
                + "the user writes them with the pen editor.";

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
        String folder = ScribblebookFolderReader.normaliseFolder(ctx.folder());
        ScribblebookFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), ctx.projectName(), folder);

        String title = scan.config().title();
        if (title == null || title.isBlank()) title = leafFolderName(folder);

        String indexBody = renderIndex(scan, title);
        String outputPath = ScribblebookFolderReader.resolveOutputPath(folder, "_index.md");
        DocumentDocument stored = writeArtefact(ctx, outputPath, indexBody, "Index — " + title);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pageCount", scan.pages().size());
        ArtefactResult index =
                new ArtefactResult("index", stored.getPath(), linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info(
                "ScribblebookApplication.refresh tenant='{}' folder='{}' sheets={}",
                ctx.tenantId(),
                folder,
                scan.pages().size());
        return new RefreshResult(APP_NAME, folder, List.of(index));
    }

    private static String renderIndex(ScribblebookFolderReader.Scan scan, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n$meta:\n  kind: workpage\n");
        sb.append("title: \"").append(escape(title)).append(" — Index\"\n");
        sb.append("description: \"Automatisch generiert aus Scribblebook-Blättern.\"\n");
        sb.append("---\n");
        sb.append("# ").append(title).append("\n\n");
        sb.append("```vance-callout\nseverity: note\ntitle: Auto-generiert\n")
                .append("body: Diese Seite wird bei jedem `app_rebuild` neu geschrieben — ")
                .append("Edits hier gehen verloren.\n```\n\n");
        if (scan.pages().isEmpty()) {
            sb.append("Noch keine Blätter in diesem Scribblebook.\n");
            return sb.toString();
        }
        sb.append("## Blätter\n\n");
        for (ScribblebookFolderReader.Page p : scan.pages()) {
            sb.append("- [")
                    .append(p.title())
                    .append("](")
                    .append(p.relativePath())
                    .append(")\n");
        }
        return sb.toString();
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath, String body, String title) {
        Optional<DocumentDocument> existing = documentService.findByPath(ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(),
                    title,
                    List.of("scribblebook", "generated", "index"),
                    body,
                    null,
                    null,
                    null,
                    null,
                    MD_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(),
                    ctx.projectName(),
                    outputPath,
                    title,
                    List.of("scribblebook", "generated", "index"),
                    MD_MIME,
                    in,
                    ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } catch (IOException e) {
            throw new ToolException("Could not write artefact '" + outputPath + "': " + e.getMessage(), e);
        }
    }

    private static String leafFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    public static String slugify(String s) {
        String base =
                s.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return base.isEmpty() ? "sheet" : base;
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
