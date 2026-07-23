package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Scans a GTD folder for {@code kind: action} pages plus the {@code _app.yaml}
 * manifest. Action fields are read from the document's mirrored {@code headers}
 * + native title/tags (no blob load). Inbox membership and project are derived
 * from the path; the bucket itself is left to {@link GtdBucketResolver} (needs
 * today's date, not available at scan time).
 */
@Component
public class GtdFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";
    public static final String PAGE_EXTENSION = ".md";

    private final DocumentService documentService;

    public GtdFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    public record Scan(
            String folder,
            DocumentDocument manifest,
            GtdConfig config,
            List<GtdAction> actions) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest = documentService.findByPath(
                tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No GTD manifest at '" + manifestPath + "'.");
        }
        GtdConfig config = parseConfig(manifest.get());

        String prefix = normalized + "/";
        String inboxPrefix = prefix + config.inboxDir() + "/";
        String projectsPrefix = prefix + config.projectsDir() + "/";

        List<DocumentDocument> all = documentService.listByKind(
                tenantId, projectId, GtdActionDocument.KIND);
        List<GtdAction> actions = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (!path.startsWith(prefix)) continue;
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.startsWith("_")) continue;
            String rel = path.substring(prefix.length());

            boolean inInbox = path.startsWith(inboxPrefix);
            String project = null;
            if (path.startsWith(projectsPrefix)) {
                String afterProjects = path.substring(projectsPrefix.length());
                int slash = afterProjects.indexOf('/');
                if (slash > 0) project = afterProjects.substring(0, slash);
            }

            String when = headerValue(doc, "when");
            String deadline = headerValue(doc, "deadline");
            boolean done = "true".equalsIgnoreCase(headerValue(doc, "done"));
            List<String> contexts = splitContexts(headerValue(doc, "contexts"));
            String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle() : humanise(stem(leaf));

            actions.add(new GtdAction(doc, rel, inInbox, project, title,
                    when == null ? "" : when, deadline, contexts, done));
        }

        actions.sort(Comparator
                .comparing((GtdAction a) -> a.done() ? 1 : 0)
                .thenComparing(a -> a.title().toLowerCase(Locale.ROOT)));
        return new Scan(normalized, manifest.get(), config, actions);
    }

    private static @Nullable String headerValue(DocumentDocument doc, String key) {
        if (doc.getHeaders() == null) return null;
        String v = doc.getHeaders().get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static List<String> splitContexts(@Nullable String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private GtdConfig parseConfig(DocumentDocument manifest) {
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return GtdConfig.parse(body);
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not parse GTD manifest '" + manifest.getPath() + "': " + e.getMessage());
        }
    }

    public static String normaliseFolder(@Nullable String folder) {
        if (folder == null) throw new ToolException("folder is required");
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    public static String slugify(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static String humanise(String slug) {
        if (slug.isEmpty()) return "Untitled";
        String s = slug.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String stem(String filename) {
        int dot = filename.indexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
