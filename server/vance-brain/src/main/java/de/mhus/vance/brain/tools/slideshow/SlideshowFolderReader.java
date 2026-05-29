package de.mhus.vance.brain.tools.slideshow;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.document.kind.SlideshowAppConfig;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Shared folder-scan logic for slideshow-app folders. Lists every
 * image document under the folder, resolves the slide order (manifest
 * {@code order:} → alphabetical fallback), and probes per-image
 * pixel dimensions.
 */
@Service
public class SlideshowFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";

    private static final List<String> GENERATED_LEAF_NAMES = List.of(
            "_app.yaml", "_index.yaml", "_info.yaml");

    /** Mime types we treat as slide-eligible. SVG kept in for vector
     *  diagrams but {@link ImageDimensionProbe} won't know the
     *  dimensions for those — the UI accepts {@code null} sizes. */
    private static final List<String> IMAGE_MIMES = List.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif",
            "image/webp", "image/svg+xml", "image/bmp", "image/tiff");

    public record Slide(
            DocumentDocument doc,
            String relativePath,
            @Nullable String caption,
            @Nullable Integer width,
            @Nullable Integer height,
            long sizeBytes,
            String mimeType) { }

    public record Scan(
            String folder,
            @Nullable DocumentDocument manifestDoc,
            ApplicationDocument manifest,
            SlideshowAppConfig slideshowConfig,
            List<Slide> slides) { }

    private final DocumentService documentService;

    public SlideshowFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    public Scan scan(String tenantId, String projectName, String folder) {
        String normalised = normaliseFolder(folder);
        DocumentDocument manifestDoc = loadManifest(tenantId, projectName, normalised);
        ApplicationDocument manifest = parseManifest(manifestDoc);
        if (!SlideshowAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
            throw new ToolException(
                    "Folder '" + normalised + "' is an "
                            + (manifest.app().isBlank() ? "untyped" : manifest.app())
                            + " application — expected 'slideshow'.");
        }
        SlideshowAppConfig cfg = SlideshowAppConfig.from(manifest);
        List<Slide> slides = loadSlides(tenantId, projectName, normalised, cfg);
        return new Scan(normalised, manifestDoc, manifest, cfg, slides);
    }

    public Scan scanOptional(String tenantId, String projectName, String folder) {
        String normalised = normaliseFolder(folder);
        Optional<DocumentDocument> manifestOpt = documentService.findByPath(
                tenantId, projectName, normalised + "/" + APP_MANIFEST);
        DocumentDocument manifestDoc;
        ApplicationDocument manifest;
        SlideshowAppConfig cfg;
        if (manifestOpt.isPresent()) {
            manifestDoc = manifestOpt.get();
            manifest = parseManifest(manifestDoc);
            if (!SlideshowAppConfig.APP_NAME.equalsIgnoreCase(manifest.app())) {
                throw new ToolException(
                        "Folder '" + normalised + "' is an "
                                + manifest.app() + " app, expected 'slideshow'.");
            }
            cfg = SlideshowAppConfig.from(manifest);
        } else {
            manifestDoc = null;
            manifest = ApplicationDocument.empty(SlideshowAppConfig.APP_NAME);
            cfg = SlideshowAppConfig.from(new LinkedHashMap<>());
        }
        List<Slide> slides = loadSlides(tenantId, projectName, normalised, cfg);
        return new Scan(normalised, manifestDoc, manifest, cfg, slides);
    }

    // ── Manifest ──────────────────────────────────────────────────

    private DocumentDocument loadManifest(String tenantId, String projectName, String folder) {
        String path = folder + "/" + APP_MANIFEST;
        return documentService.findByPath(tenantId, projectName, path)
                .orElseThrow(() -> new ToolException(
                        "No _app.yaml manifest found at '" + path
                                + "'. Use `slideshow_app_create` to "
                                + "bootstrap a new slideshow app."));
    }

    private ApplicationDocument parseManifest(DocumentDocument doc) {
        String body = loadAsText(doc);
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has mime '" + mime
                            + "' — must be JSON or YAML.");
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
                            + "`$meta.kind: application`. Recreate the "
                            + "app via `slideshow_app_create` or add "
                            + "the `$meta` header manually:\n"
                            + "  $meta:\n"
                            + "    kind: application\n"
                            + "    app:  slideshow");
        }
        if (!"application".equalsIgnoreCase(dbKind)) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' has "
                            + "`$meta.kind: " + dbKind + "`, expected "
                            + "'application'.");
        }
        if (parsed.app() == null || parsed.app().isBlank()) {
            throw new ToolException(
                    "Manifest '" + doc.getPath() + "' is missing "
                            + "`$meta.app: slideshow`.");
        }
        return parsed;
    }

    // ── Slides ────────────────────────────────────────────────────

    private List<Slide> loadSlides(String tenantId, String projectName,
                                   String folder, SlideshowAppConfig cfg) {
        // Find every image document under the folder.
        List<DocumentDocument> all = documentService.listByProject(tenantId, projectName);
        String prefix = folder + "/";
        Map<String, DocumentDocument> byRelative = new LinkedHashMap<>();
        for (DocumentDocument d : all) {
            String path = d.getPath();
            if (path == null || !path.startsWith(prefix)) continue;
            String mime = d.getMimeType();
            if (mime == null || !isImageMime(mime)) continue;
            if (isGeneratedArtefactPath(path)) continue;
            String relative = path.substring(prefix.length());
            byRelative.put(relative, d);
        }

        // Order: manifest `order` first (filtered to those that exist),
        // then any remaining images alphabetically.
        List<String> ordered = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String requested : cfg.order()) {
            if (byRelative.containsKey(requested) && seen.add(requested)) {
                ordered.add(requested);
            }
        }
        List<String> remainder = new ArrayList<>(byRelative.keySet());
        remainder.removeIf(seen::contains);
        remainder.sort(Comparator.naturalOrder());
        ordered.addAll(remainder);

        // Build slides with dimensions. Caption hierarchy:
        //   1. explicit manifest captions  (user-curated)
        //   2. document.summary            (LLM-written at import time
        //                                   or via doc_set_summary)
        //   3. filename stem               (last-resort fallback)
        List<Slide> out = new ArrayList<>(ordered.size());
        for (String relative : ordered) {
            DocumentDocument d = byRelative.get(relative);
            String caption = cfg.captions().get(relative);
            if (caption == null) {
                String summary = d.getSummary();
                caption = (summary != null && !summary.isBlank())
                        ? summary.trim() : stem(relative);
            }
            ImageDimensionProbe.Dim dim = probeDimensions(d);
            out.add(new Slide(d, relative, caption,
                    dim != null ? dim.width() : null,
                    dim != null ? dim.height() : null,
                    d.getSize(), d.getMimeType()));
        }
        return out;
    }

    private ImageDimensionProbe.@Nullable Dim probeDimensions(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return ImageDimensionProbe.probe(in, doc.getMimeType());
        } catch (IOException e) {
            return null;
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

    // ── Helpers ───────────────────────────────────────────────────

    private static String stem(String relative) {
        int slash = relative.lastIndexOf('/');
        String leaf = slash < 0 ? relative : relative.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        return dot > 0 ? leaf.substring(0, dot) : leaf;
    }

    private static boolean isImageMime(String mime) {
        String m = mime.toLowerCase(Locale.ROOT);
        return IMAGE_MIMES.contains(m);
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

    public static String resolveOutputPath(String suiteFolder, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return null;
        if (relativeOrAbsolute.contains("/")) return relativeOrAbsolute;
        return suiteFolder + "/" + relativeOrAbsolute;
    }
}
