package de.mhus.vance.brain.tools.calendar;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.applications.VanceApplicationRegistry;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Generic refresh tool for any Vance application folder (any folder
 * containing {@code _app.yaml}). Reads {@code $meta.app} from the
 * manifest, looks up the matching {@link VanceApplication} bean via
 * the registry, and calls {@code refresh()} — which regenerates
 * every derived artefact for that app type.
 *
 * <p>For {@code app: calendar} this means {@code _conflicts.yaml}
 * and {@code _gantt.md} are both rewritten in one call. Future apps
 * (kanban: board summary, wiki: backlinks index, …) will plug into
 * the same dispatch with their own artefact set.
 */
@Component
@Slf4j
public class AppRebuildTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of(
                        "type", "string",
                        "description", "App folder containing _app.yaml."));
                put("projectId", Map.of(
                        "type", "string",
                        "description", "Default: active project."));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final DocumentService documentService;
    private final VanceApplicationRegistry registry;

    public AppRebuildTool(EddieContext eddieContext,
                          DocumentService documentService,
                          VanceApplicationRegistry registry) {
        this.eddieContext = eddieContext;
        this.documentService = documentService;
        this.registry = registry;
    }

    @Override public String name() { return "app_rebuild"; }

    @Override
    public String description() {
        return "Regenerate every derived artefact in a Vance "
                + "application folder. Reads _app.yaml, dispatches "
                + "to the right app service based on `$meta.app`. "
                + "For app: calendar this means both _gantt.md and "
                + "_conflicts.yaml. Idempotent — rerun freely after "
                + "edits to refresh the views.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "write", "document", "application");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        String projectName = project.getName();

        // Read the manifest first so we know which app to dispatch
        // to — this is the only generic step; from here on the app's
        // refresh() owns the artefacts.
        String normalisedFolder = normaliseFolder(folder);
        DocumentDocument manifestDoc = loadManifest(ctx.tenantId(), projectName, normalisedFolder);
        ApplicationDocument manifest = parseManifest(manifestDoc);

        VanceApplication app = registry.require(manifest.app());
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                ctx.tenantId(), projectName, normalisedFolder,
                ctx.userId(), ctx.processId());

        VanceApplication.RefreshResult result = app.refresh(rc);
        log.info("AppRebuildTool tenant='{}' folder='{}' app='{}' "
                        + "→ {} artefacts",
                ctx.tenantId(), normalisedFolder, manifest.app(),
                result.artefacts().size());

        return result.toMap();
    }

    // ── Helpers (manifest loading, lightweight — full scans live
    //    inside the application services). ────────────────────────

    private DocumentDocument loadManifest(String tenantId, String projectName, String folder) {
        String path = folder + "/" + CalendarFolderReader.APP_MANIFEST;
        return documentService.findByPath(tenantId, projectName, path)
                .orElseThrow(() -> new ToolException(
                        "No _app.yaml manifest found at '" + path
                                + "'. Create one with `$meta: { kind: "
                                + "application, app: <type> }` to turn "
                                + "the folder into a Vance app."));
    }

    private ApplicationDocument parseManifest(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has mime '"
                            + mime + "' — must be JSON or YAML.");
        }
        try {
            return ApplicationCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse manifest '" + doc.getPath()
                            + "': " + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read manifest content: " + e.getMessage());
        }
    }

    private static String normaliseFolder(@Nullable String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ToolException("folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
