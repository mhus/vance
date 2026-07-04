package de.mhus.vance.addon.brain.canvas;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Scans a canvasbook folder for {@code kind: canvas} pages plus the
 * {@code _app.yaml} manifest. System-managed files (underscore-prefixed)
 * are excluded — they are outputs, not source pages.
 */
@Component
public class CanvasbookFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";

    private final DocumentService documentService;

    public CanvasbookFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** One page inside the canvasbook. */
    public record Page(
            DocumentDocument doc,
            String relativePath,
            String title,
            @Nullable String description) {}

    public record Scan(
            String folder,
            DocumentDocument manifest,
            ApplicationDocument config,
            @Nullable String landingPage,
            List<Page> pages) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest =
                documentService.findByPath(tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No canvasbook manifest at '" + manifestPath + "'.");
        }
        ApplicationDocument config = parseManifest(manifest.get());
        String landingPage = readLandingPage(config);

        String prefix = normalized + "/";
        List<DocumentDocument> all =
                documentService.listByKind(tenantId, projectId, CanvasService.KIND);
        List<Page> pages = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (path == null || !path.startsWith(prefix)) continue;
            String rel = path.substring(prefix.length());
            String leaf = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            if (leaf.startsWith("_")) continue;
            String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle() : stem(leaf);
            pages.add(new Page(doc, rel, title, null));
        }
        pages.sort(Comparator.comparing(p -> p.title().toLowerCase(Locale.ROOT)));

        return new Scan(normalized, manifest.get(), config, landingPage, pages);
    }

    private ApplicationDocument parseManifest(DocumentDocument manifest) {
        String mime = manifest.getMimeType();
        if (!ApplicationCodec.supports(mime)) {
            throw new ToolException("Canvasbook manifest '" + manifest.getPath()
                    + "' has mime '" + mime + "' — must be YAML or JSON.");
        }
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ApplicationCodec.parse(body, mime);
        } catch (IOException | RuntimeException e) {
            throw new ToolException("Could not parse canvasbook manifest '"
                    + manifest.getPath() + "': " + e.getMessage());
        }
    }

    private static @Nullable String readLandingPage(ApplicationDocument config) {
        Object block = config.config().get(CanvasbookApplication.APP_NAME);
        if (block instanceof Map<?, ?> m) {
            Object lp = m.get("landingPage");
            if (lp instanceof String s && !s.isBlank()) return s.trim();
        }
        return null;
    }

    public static String normaliseFolder(String folder) {
        if (folder == null) throw new ToolException("folder is required");
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    public static String resolveOutputPath(String folder, @Nullable String configured) {
        String c = configured == null || configured.isBlank() ? "_index.md" : configured.trim();
        if (c.startsWith("/")) return c.substring(1);
        return folder + "/" + c;
    }

    private static String stem(String filename) {
        int dot = filename.indexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
