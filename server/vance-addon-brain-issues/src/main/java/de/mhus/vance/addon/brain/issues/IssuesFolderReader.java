package de.mhus.vance.addon.brain.issues;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Scans an issues folder for {@code kind: issue} pages. The active scan reads
 * <b>only {@code items/}</b> — archived issues under {@code archive/} stay out
 * of lists, counts and the index (see {@link #scanArchived}). Fields come from
 * the document's mirrored {@code headers} + native title/tags (no blob load).
 */
@Component
public class IssuesFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";
    public static final String PAGE_EXTENSION = ".md";
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(\\d+)");

    private final DocumentService documentService;

    public IssuesFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    public record Scan(
            String folder,
            DocumentDocument manifest,
            IssuesConfig config,
            List<Issue> issues) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest = documentService.findByPath(tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No issues manifest at '" + manifestPath + "'.");
        }
        IssuesConfig config = parseConfig(manifest.get());
        List<Issue> issues = readDir(tenantId, projectId, normalized + "/" + config.itemsDir() + "/", false);
        return new Scan(normalized, manifest.get(), config, issues);
    }

    /** Read the archived issues (under {@code archive/}) — separate view. */
    public List<Issue> scanArchived(String tenantId, String projectId, String folder, IssuesConfig config) {
        String normalized = normaliseFolder(folder);
        return readDir(tenantId, projectId, normalized + "/" + config.archiveDir() + "/", true);
    }

    /** Highest issue number across {@code items/} + {@code archive/} (for number reservation). */
    public int maxNumber(String tenantId, String projectId, String folder, IssuesConfig config) {
        String normalized = normaliseFolder(folder);
        String itemsPrefix = normalized + "/" + config.itemsDir() + "/";
        String archivePrefix = normalized + "/" + config.archiveDir() + "/";
        int max = 0;
        for (DocumentDocument doc : documentService.listByKind(tenantId, projectId, IssueDocument.KIND)) {
            String path = doc.getPath();
            if (!path.startsWith(itemsPrefix) && !path.startsWith(archivePrefix)) continue;
            int n = numberOf(doc);
            if (n > max) max = n;
        }
        return max;
    }

    private List<Issue> readDir(String tenantId, String projectId, String prefix, boolean archived) {
        List<Issue> out = new ArrayList<>();
        for (DocumentDocument doc : documentService.listByKind(tenantId, projectId, IssueDocument.KIND)) {
            String path = doc.getPath();
            if (!path.startsWith(prefix)) continue;
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.startsWith("_")) continue;
            int number = numberOf(doc);
            String state = headerValue(doc, "state");
            String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle() : humanise(stem(leaf));
            List<String> labels = doc.getTags() != null
                    ? new ArrayList<>(doc.getTags()) : new ArrayList<>();
            labels.remove("issue");
            out.add(new Issue(doc, number, title,
                    state == null ? IssueDocument.STATE_OPEN : state,
                    labels, headerValue(doc, "assignee"), headerValue(doc, "priority"), archived));
        }
        // Open first, then by number descending (newest issues on top).
        out.sort(Comparator
                .comparing((Issue i) -> i.isOpen() ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Issue::number).reversed()));
        return out;
    }

    private int numberOf(DocumentDocument doc) {
        String h = headerValue(doc, "number");
        if (h != null) {
            try { return Integer.parseInt(h); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        String leaf = doc.getPath().substring(doc.getPath().lastIndexOf('/') + 1);
        Matcher m = NUMBER_PREFIX.matcher(leaf);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static @Nullable String headerValue(DocumentDocument doc, String key) {
        if (doc.getHeaders() == null) return null;
        String v = doc.getHeaders().get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private IssuesConfig parseConfig(DocumentDocument manifest) {
        try (InputStream in = documentService.loadContent(manifest)) {
            return IssuesConfig.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new ToolException("Could not parse issues manifest '" + manifest.getPath()
                    + "': " + e.getMessage());
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
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
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
        String base = dot < 0 ? filename : filename.substring(0, dot);
        Matcher m = Pattern.compile("^\\d+-(.+)$").matcher(base);
        return m.find() ? m.group(1) : base;
    }
}
