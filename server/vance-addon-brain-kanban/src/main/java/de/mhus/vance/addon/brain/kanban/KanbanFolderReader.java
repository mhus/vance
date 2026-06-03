package de.mhus.vance.addon.brain.kanban;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.CardCodec;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.document.kind.KanbanAppConfig;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared folder-scan logic used by every kanban-suite tool
 * ({@code kanban_app_create}, {@code kanban_move},
 * {@code kanban_aggregate}, {@code app_rebuild}).
 *
 * <p>Resolves the {@code _app.yaml} manifest, lists every
 * {@code kind: card} document under the folder, and tags each one
 * with the column it belongs to (= leaf folder name relative to the
 * suite root). Generated artefacts ({@code _board.md},
 * {@code _stats.yaml}) and the manifest itself are excluded from the
 * card list.
 */
@Service
public class KanbanFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";

    private static final List<String> GENERATED_LEAF_NAMES = List.of(
            "_board.md", "_stats.yaml", "_app.yaml", "_info.yaml");

    /** Default column for cards that sit directly in the suite root
     *  (no sub-folder = no explicit column). */
    public static final String DEFAULT_COLUMN = "backlog";

    /** A card file plus its resolved column name and parsed body. */
    public record CardFile(
            DocumentDocument doc,
            String column,
            CardDocument card) { }

    /** Bundle of everything a tool needs after scanning the folder. */
    public record Scan(
            String folder,
            DocumentDocument manifestDoc,
            ApplicationDocument manifest,
            KanbanAppConfig kanbanConfig,
            List<CardFile> cards) { }

    private final DocumentService documentService;

    public KanbanFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Read the full state of a kanban-app folder: manifest + cards +
     * column assignments + typed config view.
     *
     * @throws ToolException when the manifest is missing or configured
     *         for a different app ({@code app != "kanban"}).
     */
    public Scan scan(String tenantId, String projectName, String folder) {
        String normalised = normaliseFolder(folder);
        DocumentDocument manifestDoc = loadManifest(tenantId, projectName, normalised);
        ApplicationDocument manifest = parseManifest(manifestDoc);
        if (!KanbanAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
            throw new ToolException(
                    "Folder '" + normalised + "' is an "
                            + (manifest.app().isBlank() ? "untyped" : manifest.app())
                            + " application — expected 'kanban'. "
                            + "Edit '" + normalised + "/_app.yaml' "
                            + "and set `$meta.app: kanban`.");
        }
        KanbanAppConfig kanbanConfig = KanbanAppConfig.from(manifest);
        List<CardFile> cards = loadCards(tenantId, projectName, normalised);
        return new Scan(normalised, manifestDoc, manifest, kanbanConfig, cards);
    }

    /** Optional-manifest variant used by tools that should work on
     *  unbootstrapped folders too. */
    public Scan scanOptional(String tenantId, String projectName, String folder) {
        String normalised = normaliseFolder(folder);
        Optional<DocumentDocument> manifestOpt = documentService.findByPath(
                tenantId, projectName, normalised + "/" + APP_MANIFEST);
        DocumentDocument manifestDoc;
        ApplicationDocument manifest;
        KanbanAppConfig kanbanConfig;
        if (manifestOpt.isPresent()) {
            manifestDoc = manifestOpt.get();
            manifest = parseManifest(manifestDoc);
            if (!KanbanAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
                throw new ToolException(
                        "Folder '" + normalised + "' is an "
                                + manifest.app() + " app, expected 'kanban'.");
            }
            kanbanConfig = KanbanAppConfig.from(manifest);
        } else {
            manifestDoc = null;
            manifest = ApplicationDocument.empty(KanbanAppConfig.APP_NAME);
            kanbanConfig = KanbanAppConfig.from(new LinkedHashMap<>());
        }
        List<CardFile> cards = loadCards(tenantId, projectName, normalised);
        return new Scan(normalised, manifestDoc, manifest, kanbanConfig, cards);
    }

    // ── Manifest ──────────────────────────────────────────────────

    private DocumentDocument loadManifest(String tenantId, String projectName, String folder) {
        String path = folder + "/" + APP_MANIFEST;
        return documentService.findByPath(tenantId, projectName, path)
                .orElseThrow(() -> new ToolException(
                        "No _app.yaml manifest found at '" + path
                                + "'. Use `kanban_app_create` to "
                                + "bootstrap a new kanban app — "
                                + "writing the manifest by hand is "
                                + "error-prone."));
    }

    private ApplicationDocument parseManifest(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has mime '"
                            + mime + "' — must be JSON or YAML.");
        }
        ApplicationDocument parsed;
        try {
            parsed = ApplicationCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse manifest '" + doc.getPath()
                            + "': " + e.getMessage());
        }
        String dbKind = doc.getKind();
        if (dbKind == null || dbKind.isBlank()) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' is missing "
                            + "`$meta.kind: application`. Recreate "
                            + "the app via `kanban_app_create` or add "
                            + "the `$meta` header manually:\n"
                            + "  $meta:\n"
                            + "    kind: application\n"
                            + "    app:  kanban");
        }
        if (!"application".equalsIgnoreCase(dbKind)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has "
                            + "`$meta.kind: " + dbKind + "`, expected "
                            + "'application'. This document is not "
                            + "an app manifest.");
        }
        if (parsed.app() == null || parsed.app().isBlank()) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' is missing "
                            + "`$meta.app`. Set `app: kanban` so the "
                            + "registry can dispatch the right service.");
        }
        return parsed;
    }

    // ── Cards ─────────────────────────────────────────────────────

    private List<CardFile> loadCards(String tenantId, String projectName, String folder) {
        List<DocumentDocument> all = documentService.listByKind(
                tenantId, projectName, "card");
        List<CardFile> out = new ArrayList<>();
        String prefix = folder + "/";
        for (DocumentDocument d : all) {
            String path = d.getPath();
            if (path == null || !path.startsWith(prefix)) continue;
            if (isGeneratedArtefactPath(path)) continue;
            String column = columnFor(folder, path);
            CardDocument card = parseCard(d);
            out.add(new CardFile(d, column, card));
        }
        return out;
    }

    private CardDocument parseCard(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!CardCodec.supports(mime)) {
            throw new ToolException(
                    "Card '" + doc.getPath() + "' has mime '"
                            + mime + "' — must be Markdown, JSON, or YAML.");
        }
        try {
            return CardCodec.parse(body, mime);
        } catch (Exception e) {
            throw new ToolException(
                    "Could not parse card '" + doc.getPath()
                            + "': " + e.getMessage());
        }
    }

    private String loadAsText(DocumentDocument doc) {
        if (doc.getInlineText() != null) return doc.getInlineText();
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not read '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Column resolution ─────────────────────────────────────────

    /**
     * Compute the column for a card document. Rule (deterministic):
     * the leaf folder of the file relative to the suite root.
     * Files directly in the suite root land in {@link #DEFAULT_COLUMN}.
     */
    public static String columnFor(String suiteFolder, String filePath) {
        String relative = filePath.substring(suiteFolder.length() + 1);
        int slash = relative.lastIndexOf('/');
        if (slash < 0) return DEFAULT_COLUMN;
        String parent = relative.substring(0, slash);
        int innerSlash = parent.lastIndexOf('/');
        return innerSlash < 0 ? parent : parent.substring(innerSlash + 1);
    }

    static boolean isGeneratedArtefactPath(String path) {
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        return GENERATED_LEAF_NAMES.contains(leaf);
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

    /** Resolve a relative output path inside a suite folder. */
    public static String resolveOutputPath(String suiteFolder, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return null;
        if (relativeOrAbsolute.contains("/")) return relativeOrAbsolute;
        return suiteFolder + "/" + relativeOrAbsolute;
    }
}
