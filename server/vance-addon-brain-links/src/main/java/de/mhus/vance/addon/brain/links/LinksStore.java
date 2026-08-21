package de.mhus.vance.addon.brain.links;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.SecurityContextFactory;
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
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Manifest IO for the links app — the only place that reads and writes
 * {@code <folder>/_app.yaml}. Everything above it (the application, the
 * mutations, the controller) works on {@link LinksConfig}.
 *
 * <p>Data ownership: every document touch goes through
 * {@link DocumentService}. No {@code MongoTemplate}, no foreign repository.
 */
@Component
public class LinksStore {

    static final String YAML_MIME = "application/yaml";
    static final List<String> KINDS = List.of("application", LinksConfig.BLOCK);

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public LinksStore(DocumentService documentService, SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    /** A loaded manifest: the document, its parsed form, and the block. */
    public record Loaded(
            String folder,
            DocumentDocument manifest,
            ApplicationDocument manifestDoc,
            LinksConfig config) {}

    public Loaded load(String tenantId, String projectId, String folder) {
        String normalised = normaliseFolder(folder);
        String manifestPath = manifestPath(normalised);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, manifestPath)
                .orElseThrow(() -> new ToolException(
                        "No links manifest at '" + manifestPath + "'."));
        ApplicationDocument parsed = parse(doc);
        return new Loaded(normalised, doc, parsed, LinksConfig.from(parsed));
    }

    /** Replace the {@code config.links} block, keeping title and description. */
    public DocumentDocument saveConfig(Loaded loaded, LinksConfig config, @Nullable String userId) {
        ApplicationDocument current = loaded.manifestDoc();
        Map<String, Object> configBlock = new LinkedHashMap<>(current.config());
        configBlock.put(LinksConfig.BLOCK, config.toBlock());
        ApplicationDocument next = new ApplicationDocument(
                "application", LinksConfig.BLOCK, current.title(), current.description(),
                configBlock, new LinkedHashMap<>(current.extra()));
        DocumentDocument manifest = loaded.manifest();
        return documentService.update(manifest.getId(),
                manifest.getTitle(), KINDS,
                ApplicationCodec.serialize(next, YAML_MIME),
                null, null, null, null, YAML_MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(
                        manifest.getTenantId(), userId, manifest.getPath()));
    }

    /** Write a fresh manifest (create or replace). Used by {@code create()}. */
    public DocumentDocument writeManifest(String tenantId, String projectId, String folder,
                                          @Nullable String title, @Nullable String description,
                                          LinksConfig config, @Nullable String userId) {
        String normalised = normaliseFolder(folder);
        String manifestPath = manifestPath(normalised);
        Map<String, Object> configBlock = new LinkedHashMap<>();
        configBlock.put(LinksConfig.BLOCK, config.toBlock());
        ApplicationDocument manifest = new ApplicationDocument(
                "application", LinksConfig.BLOCK, title, description,
                configBlock, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);
        String docTitle = title == null || title.isBlank() ? "Links" : title;

        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, manifestPath);
        var actor = contextFactory.writeActor(tenantId, userId, manifestPath);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(), docTitle, KINDS,
                    body, null, null, null, null, YAML_MIME,
                    DocumentService.TOOL_IDENTITY, actor);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(tenantId, projectId, manifestPath, docTitle,
                    KINDS, YAML_MIME, in, userId, actor);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write manifest '" + manifestPath + "': " + e.getMessage());
        }
    }

    /** Create or replace a derived artefact (the generated index). */
    public DocumentDocument writeArtefact(String tenantId, String projectId, String path,
                                          String title, String mimeType, String body,
                                          List<String> kinds, @Nullable String userId) {
        var actor = contextFactory.writeActor(tenantId, userId, path);
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(), title, kinds,
                    body, null, null, null, null, mimeType,
                    DocumentService.TOOL_IDENTITY, actor);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(tenantId, projectId, path, title, kinds,
                    mimeType, in, userId, actor);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write artefact '" + path + "': " + e.getMessage());
        }
    }

    public boolean exists(String tenantId, String projectId, String folder) {
        return documentService
                .findByPath(tenantId, projectId, manifestPath(normaliseFolder(folder)))
                .isPresent();
    }

    // ── path helpers ──────────────────────────────────────────────

    public static String manifestPath(String normalisedFolder) {
        return normalisedFolder + "/" + VanceApplication.APP_MANIFEST;
    }

    public static String normaliseFolder(@Nullable String folder) {
        if (folder == null) throw new ToolException("folder is required");
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    public static String resolveOutputPath(String folder, @Nullable String configured) {
        String c = configured == null || configured.isBlank()
                ? LinksConfig.DEFAULT_INDEX : configured.trim();
        return c.startsWith("/") ? c.substring(1) : folder + "/" + c;
    }

    private ApplicationDocument parse(DocumentDocument manifest) {
        String mime = manifest.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException("Links manifest '" + manifest.getPath()
                    + "' has mime '" + mime + "' — must be YAML or JSON.");
        }
        try {
            return ApplicationCodec.parse(documentService.readContent(manifest), mime);
        } catch (RuntimeException e) {
            throw new ToolException("Could not parse links manifest '"
                    + manifest.getPath() + "': " + e.getMessage());
        }
    }
}
