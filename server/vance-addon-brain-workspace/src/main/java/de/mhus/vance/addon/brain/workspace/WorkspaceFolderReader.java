package de.mhus.vance.addon.brain.workspace;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Scans a workspace folder for {@code kind: canvas} pages plus the
 * {@code _app.yaml} manifest. System-managed files (underscore-prefixed)
 * are excluded from the page list — they're outputs, not sources.
 */
@Component
public class WorkspaceFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";

    private final DocumentService documentService;

    public WorkspaceFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    public record Scan(
            String folder,
            DocumentDocument manifest,
            WorkspaceConfig config,
            List<WorkspacePage> pages) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest = documentService.findByPath(
                tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException(
                    "No workspace manifest at '" + manifestPath + "'.");
        }
        WorkspaceConfig config = parseConfig(manifest.get());

        // Page list = all kind: canvas documents inside the folder.
        String prefix = normalized + "/";
        List<DocumentDocument> all = documentService.listByKind(tenantId, projectId, "canvas");
        List<WorkspacePage> pages = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (!path.startsWith(prefix)) continue;
            String rel = path.substring(prefix.length());
            if (rel.startsWith("_")) continue;
            // ignore generated _index pages by path-relative leaf check
            String leaf = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            if (leaf.startsWith("_")) continue;

            int slash = rel.lastIndexOf('/');
            String section = slash < 0 ? "" : rel.substring(0, slash);
            // Top-level Section = leaf of first '/' segment, deeper nesting
            // collapses to its top-level section (v1).
            if (section.contains("/")) {
                section = section.substring(0, section.indexOf('/'));
            }

            PageHeader hdr = readPageHeader(doc);
            String title = hdr.title != null && !hdr.title.isBlank()
                    ? hdr.title
                    : stem(leaf);
            pages.add(new WorkspacePage(doc, rel, section, title, hdr.description));
        }

        pages.sort(Comparator
                .comparing(WorkspacePage::section)
                .thenComparing((p) -> p.title().toLowerCase(java.util.Locale.ROOT)));

        return new Scan(normalized, manifest.get(), config, pages);
    }

    private record PageHeader(@Nullable String title, @Nullable String description) {}

    private PageHeader readPageHeader(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!body.startsWith("---\n")) {
                return new PageHeader(doc.getTitle(), null);
            }
            int end = body.indexOf("\n---\n", 4);
            if (end < 0) return new PageHeader(doc.getTitle(), null);
            String headerText = body.substring(4, end);
            Object loaded = new Yaml().load(headerText);
            if (loaded instanceof Map<?, ?> m) {
                Object t = m.get("title");
                Object d = m.get("description");
                String title = t != null ? t.toString() : doc.getTitle();
                String desc = d != null ? d.toString() : null;
                return new PageHeader(title, desc);
            }
            return new PageHeader(doc.getTitle(), null);
        } catch (IOException | RuntimeException e) {
            return new PageHeader(doc.getTitle(), null);
        }
    }

    private WorkspaceConfig parseConfig(DocumentDocument manifest) {
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return WorkspaceConfig.parse(body);
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not parse workspace manifest '" + manifest.getPath() + "': "
                            + e.getMessage());
        }
    }

    public static String normaliseFolder(String folder) {
        if (folder == null) throw new ToolException("folder is required");
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    public static String resolveOutputPath(String folder, String configured) {
        String c = configured == null || configured.isBlank() ? "_index.md" : configured.trim();
        if (c.startsWith("/")) return c.substring(1);
        return folder + "/" + c;
    }

    private static String stem(String filename) {
        int dot = filename.indexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
