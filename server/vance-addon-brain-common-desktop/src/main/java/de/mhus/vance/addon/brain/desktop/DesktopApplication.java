package de.mhus.vance.addon.brain.desktop;

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
 * Concrete {@link VanceApplication} for {@code app: common-desktop}
 * folders. Two roles:
 *
 * <ul>
 *   <li>As a <em>host</em>: {@link #refresh} writes a
 *       {@code _desktop.md} snapshot of every app under the folder
 *       (via {@link DesktopStatusService}); the live web view calls
 *       the REST endpoint instead.</li>
 *   <li>As an <em>app</em> itself: {@link #describe} gives it a desktop
 *       icon; {@link #status} is empty (a desktop has no desktop-status,
 *       which also stops it from aggregating itself).</li>
 * </ul>
 */
@Service
@Slf4j
public class DesktopApplication implements VanceApplication {

    public static final String APP_NAME = DesktopAppConfig.APP_NAME;

    private static final String YAML_MIME = "application/yaml";
    private static final String MD_MIME = "text/markdown";
    private static final String SNAPSHOT_FILE = "_desktop.md";

    private final DesktopStatusService statusService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;

    public DesktopApplication(DesktopStatusService statusService,
                              DocumentService documentService,
                              DocumentLinkBuilder linkBuilder,
                              SecurityContextFactory contextFactory) {
        this.statusService = statusService;
        this.documentService = documentService;
        this.linkBuilder = linkBuilder;
        this.contextFactory = contextFactory;
    }

    @Override public String appName() { return APP_NAME; }

    @Override
    public AppCard describe(DescribeContext ctx) {
        return new AppCard("🖥️", null);
    }

    @Override
    public Optional<AppStatus> status(StatusContext ctx) {
        return Optional.empty();
    }

    @Override
    public String promptInject(PromptInjectContext ctx) {
        return "This is a Common Desktop — a launcher + status board over "
                + "the apps under its folder. Regenerate the `_desktop.md` "
                + "snapshot via `app_rebuild('" + ctx.folder() + "')`.";
    }

    // ── Refresh: write the _desktop.md snapshot ───────────────────

    @Override
    public RefreshResult refresh(RefreshContext ctx) {
        DesktopView view = statusService.aggregate(
                ctx.tenantId(), ctx.projectName(), ctx.folder(), ctx.userId());

        String body = renderSnapshot(view);
        String outputPath = ctx.folder() + "/" + SNAPSHOT_FILE;
        DocumentDocument stored = writeArtefact(ctx, outputPath, body,
                "Desktop — " + leaf(ctx.folder()));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("appCount", view.getCards().size());

        ArtefactResult art = new ArtefactResult(
                "desktop", stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()), stats);

        log.info("DesktopApplication.refresh tenant='{}' folder='{}' → {} apps",
                ctx.tenantId(), ctx.folder(), view.getCards().size());
        return new RefreshResult(APP_NAME, ctx.folder(), List.of(art));
    }

    private static String renderSnapshot(DesktopView view) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Desktop\n\n");
        if (view.getCards().isEmpty()) {
            sb.append("_No apps found under this folder._\n");
            return sb.toString();
        }
        sb.append("| App | Type | Status |\n");
        sb.append("|-----|------|--------|\n");
        for (DesktopCard c : view.getCards()) {
            String statusCell = "—";
            if (c.getStatus() != null && c.getStatus().getHeadline() != null) {
                statusCell = escapeCell(c.getStatus().getHeadline());
            }
            sb.append("| ").append(c.getIcon()).append(' ')
                    .append(escapeCell(c.getTitle()))
                    .append(" | ").append(escapeCell(c.getApp()))
                    .append(" | ").append(statusCell)
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static String escapeCell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private DocumentDocument writeArtefact(RefreshContext ctx, String outputPath,
                                           String body, String title) {
        List<String> tags = List.of("common-desktop", "generated", "desktop");
        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), outputPath);
        if (existing.isPresent()) {
            return documentService.update(
                    existing.get().getId(), title, tags, body,
                    null, null, null, null, MD_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(
                    ctx.tenantId(), ctx.projectName(), outputPath, title,
                    tags, MD_MIME, in, ctx.userId(),
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), outputPath));
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + outputPath + "': " + e.getMessage());
        }
    }

    // ── Create: write the manifest ────────────────────────────────

    @Override
    public CreateResult create(CreateContext ctx) {
        String folder = ctx.folder();
        Map<String, Object> params = ctx.params() != null ? ctx.params() : new LinkedHashMap<>();
        String manifestPath = folder + "/" + APP_MANIFEST;

        Optional<DocumentDocument> existing = documentService.findByPath(
                ctx.tenantId(), ctx.projectName(), manifestPath);
        if (existing.isPresent() && !ctx.overwrite()) {
            throw new ToolException(
                    "Manifest already exists at '" + manifestPath
                            + "'. Pass overwrite=true to replace it.");
        }

        String title = asString(params.get("title"));
        String description = asString(params.get("description"));

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("recurse", asBoolean(params.get("recurse")));
        String root = asString(params.get("root"));
        if (root != null) block.put("root", root);
        List<String> include = asStringList(params.get("include"));
        if (!include.isEmpty()) block.put("include", include);
        List<String> exclude = asStringList(params.get("exclude"));
        if (!exclude.isEmpty()) block.put("exclude", exclude);
        List<String> order = asStringList(params.get("order"));
        if (!order.isEmpty()) block.put("order", order);

        Map<String, Object> appConfig = new LinkedHashMap<>();
        appConfig.put(APP_NAME, block);
        ApplicationDocument manifest = new ApplicationDocument(
                "application", APP_NAME, title, description,
                appConfig, new LinkedHashMap<>());
        String manifestBody = ApplicationCodec.serialize(manifest, YAML_MIME);

        DocumentDocument stored;
        List<String> tags = List.of("application", "common-desktop");
        String docTitle = title != null ? title : "Desktop";
        if (existing.isPresent()) {
            stored = documentService.update(
                    existing.get().getId(), docTitle, tags,
                    manifestBody, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
        } else {
            try (InputStream in = new ByteArrayInputStream(
                    manifestBody.getBytes(StandardCharsets.UTF_8))) {
                stored = documentService.create(
                        ctx.tenantId(), ctx.projectName(), manifestPath,
                        docTitle, tags, YAML_MIME, in, ctx.userId(),
                        contextFactory.writeActor(ctx.tenantId(), ctx.userId(), manifestPath));
            } catch (IOException e) {
                throw new ToolException(
                        "Could not write manifest '" + manifestPath
                                + "': " + e.getMessage());
            }
        }

        // Produce the first snapshot so the desktop is immediately usable.
        RefreshContext rc = new RefreshContext(
                ctx.tenantId(), ctx.projectName(), folder, ctx.userId(), ctx.processId());
        List<ArtefactResult> artefacts = refresh(rc).artefacts();

        Map<String, Object> stats = new LinkedHashMap<>();
        if (title != null) stats.put("title", title);

        log.info("DesktopApplication.create tenant='{}' folder='{}' manifestPath='{}'",
                ctx.tenantId(), folder, manifestPath);

        return new CreateResult(
                APP_NAME, folder, stored.getPath(),
                linkBuilder.linkFor(stored, ctx.projectName()),
                List.of(), artefacts,
                "Desktop ready — open it, or add apps under this folder "
                        + "and re-run app_rebuild to refresh the board.",
                stats);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String leaf(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static boolean asBoolean(@Nullable Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }

    private static List<String> asStringList(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
            else if (o != null) out.add(o.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
