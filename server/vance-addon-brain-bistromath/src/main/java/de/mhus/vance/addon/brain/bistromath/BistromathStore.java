package de.mhus.vance.addon.brain.bistromath;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Document IO for Bistromath apps — the manifest, the view documents and the
 * program.
 *
 * <p><b>Not the app's data.</b> There is no row reading here and no table
 * endpoint anywhere: a script lists and reads documents through the generic
 * document API in the browser and builds its own rows. The folder-as-table
 * pattern is a way to store records, not a concept the backend implements —
 * giving it a dedicated endpoint would have put the same knowledge in two
 * places and made the runtime pretend to understand data it never sees.
 *
 * <p>Data ownership: every touch goes through {@link DocumentService}. No
 * {@code MongoTemplate}, no foreign repository.
 */
@Component
public class BistromathStore {

    static final String YAML_MIME = "application/yaml";
    static final String JS_MIME = "text/javascript";
    static final List<String> MANIFEST_KINDS = List.of("application", BistromathConfig.BLOCK);

    private final DocumentService documentService;
    private final SecurityContextFactory contextFactory;

    public BistromathStore(DocumentService documentService,
                           SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.contextFactory = contextFactory;
    }

    /** A loaded manifest: the document, its parsed form, and the config block. */
    public record Loaded(
            String folder,
            DocumentDocument manifest,
            ApplicationDocument manifestDoc,
            BistromathConfig config) {}

    /** What a folder scan found, plus what it had to refuse. */
    public record Discovered(List<ViewRef> views, List<String> problems) {}

    // ── manifest ──────────────────────────────────────────────────

    /**
     * Load the manifest of a Bistromath app.
     *
     * <p>The identity check lives here rather than in the callers: a write path
     * that puts {@code app: custom} back unconditionally would silently convert
     * somebody else's app folder, and one check on the read side covers every
     * caller.
     */
    public Loaded load(String tenantId, String projectId, String folder) {
        String normalised = normaliseFolder(folder);
        String manifestPath = manifestPath(normalised);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, manifestPath)
                .orElseThrow(() -> new ToolException(
                        "No app manifest at '" + manifestPath + "'."));
        ApplicationDocument parsed = parseManifest(doc);
        String app = parsed.app();
        if (!app.isBlank() && !BistromathConfig.BLOCK.equals(app)) {
            throw new ToolException("'" + normalised + "' is an app: " + app
                    + ", not a Bistromath app — refusing to touch its manifest.");
        }
        return new Loaded(normalised, doc, parsed, BistromathConfig.from(parsed));
    }

    private ApplicationDocument parseManifest(DocumentDocument manifest) {
        String mime = manifest.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException("App manifest '" + manifest.getPath() + "' has mime '"
                    + mime + "' — must be YAML or JSON.");
        }
        try {
            return ApplicationCodec.parse(documentService.readContent(manifest), mime);
        } catch (RuntimeException e) {
            throw new ToolException("Could not parse app manifest '" + manifest.getPath()
                    + "': " + e.getMessage());
        }
    }

    // ── discovery ─────────────────────────────────────────────────

    /**
     * Every view under the app folder, found by its own header.
     *
     * <p>A view is a document whose {@code $meta.kind} is
     * {@value BistromathConfig#VIEW_KIND}. That value lands on the indexed
     * {@code kind} field when the document is written, so this is a filter over
     * a folder listing rather than a content scan.
     *
     * <p><b>Recursive on purpose.</b> The app folder has no prescribed layout —
     * an author may keep views flat, in {@code views/}, or grouped by feature,
     * and none of those is the runtime's business. The handle is the file name
     * either way, which is why two views with the same file name in different
     * sub-folders are a reported collision rather than a silent winner: a deep
     * link could not say which one it means.
     *
     * <p>Problems are collected, not thrown. One unusable file name must not
     * take down an app whose other four views are fine.
     */
    public Discovered discoverViews(String tenantId, String projectId, String folder) {
        String prefix = folderPrefix(normaliseFolder(folder));
        List<DocumentDocument> docs = new ArrayList<>();
        for (DocumentDocument doc : documentService.listUnderFolder(tenantId, projectId, prefix)) {
            if (BistromathConfig.VIEW_KIND.equals(doc.getKind())) docs.add(doc);
        }
        docs.sort(Comparator.comparing(DocumentDocument::getPath));

        List<ViewRef> views = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> handles = new LinkedHashSet<>();
        for (DocumentDocument doc : docs) {
            String handle = baseName(doc.getPath());
            if (!BistromathConfig.isValidHandle(handle)) {
                problems.add("'" + doc.getPath() + "' cannot be a view: its file name is not a"
                        + " slug (lowercase letters, digits, '-' and '_'), and the file name is"
                        + " the handle a link points at.");
                continue;
            }
            if (!handles.add(handle)) {
                problems.add("Two views share the handle '" + handle + "' — '" + doc.getPath()
                        + "' is unreachable because a link could not say which one it means.");
                continue;
            }
            views.add(new ViewRef(handle, doc.getPath(), doc.getTitle()));
        }
        return new Discovered(List.copyOf(views), List.copyOf(problems));
    }

    /** The app's program, or empty when it has none. */
    public Optional<DocumentDocument> findProgram(String tenantId, String projectId,
                                                 String folder, BistromathConfig config) {
        String path = normaliseFolder(folder) + "/" + config.program();
        return documentService.findByPath(tenantId, projectId, path);
    }

    // ── reads ─────────────────────────────────────────────────────

    /** Read and parse a view document that {@link #discoverViews} found. */
    public ViewNode readView(String tenantId, String projectId, ViewRef view) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, view.path())
                .orElseThrow(() -> new ToolException("View '" + view.handle()
                        + "' is gone: '" + view.path() + "' no longer exists."));
        return ViewParser.parse(documentService.readContent(doc), view.path());
    }

    // ── writes ────────────────────────────────────────────────────

    /** Whether a manifest document exists at this folder — by path, not by load. */
    public boolean manifestExists(String tenantId, String projectId, String folder) {
        return documentExists(tenantId, projectId, manifestPath(normaliseFolder(folder)));
    }

    /** Whether any document exists at this exact path. */
    public boolean documentExists(String tenantId, String projectId, String path) {
        return documentService.findByPath(tenantId, projectId, path).isPresent();
    }

    /** Write a fresh manifest (create or replace). */
    public DocumentDocument writeManifest(String tenantId, String projectId, String folder,
                                          @Nullable String title,
                                          @Nullable String description,
                                          BistromathConfig config, @Nullable String userId) {
        String manifestPath = manifestPath(normaliseFolder(folder));
        Map<String, Object> configBlock = new LinkedHashMap<>();
        configBlock.put(BistromathConfig.BLOCK, config.toBlock());
        ApplicationDocument manifest = new ApplicationDocument(
                "application", BistromathConfig.BLOCK, title, description,
                configBlock, new LinkedHashMap<>());
        String body = ApplicationCodec.serialize(manifest, YAML_MIME);
        String docTitle = title == null || title.isBlank() ? "App" : title;
        return write(tenantId, projectId, manifestPath, docTitle, YAML_MIME, body,
                MANIFEST_KINDS, userId);
    }

    /** Write any document of the app (a view, the program). */
    public DocumentDocument writeDocument(String tenantId, String projectId, String path,
                                          String title, String mime, String body,
                                          List<String> kinds, @Nullable String userId) {
        return write(tenantId, projectId, path, title, mime, body, kinds, userId);
    }

    private DocumentDocument write(String tenantId, String projectId, String path, String title,
                                   String mime, String body, List<String> kinds,
                                   @Nullable String userId) {
        var actor = contextFactory.writeActor(tenantId, userId, path);
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            return documentService.update(existing.get().getId(), title, kinds, body,
                    null, null, null, null, mime, DocumentService.TOOL_IDENTITY, actor);
        }
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            return documentService.create(tenantId, projectId, path, title, kinds, mime,
                    in, userId, actor);
        } catch (IOException e) {
            throw new ToolException("Could not write '" + path + "': " + e.getMessage());
        }
    }

    // ── paths ──────────────────────────────────────────────────────

    public static String normaliseFolder(@Nullable String folder) {
        String f = folder == null ? "" : folder.trim();
        while (f.startsWith("/")) f = f.substring(1);
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        if (f.isEmpty()) {
            throw new ToolException("An app needs a folder — the project root cannot be one.");
        }
        return f;
    }

    public static String manifestPath(String folder) {
        return normaliseFolder(folder) + "/" + VanceApplication.APP_MANIFEST;
    }

    private static String folderPrefix(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * File name without extension — the handle of a view.
     *
     * <p>Dropping the extension means {@code a.yaml} and {@code a.json} in one
     * folder reduce to the same handle, which is why that is reported as a
     * collision. Keeping the extension would be worse: this string appears in
     * deep links.
     */
    static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
