package de.mhus.vance.addon.brain.wiki;

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
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Scans a wiki folder for {@code kind: workpage} pages plus the
 * {@code _app.yaml} manifest. System-managed files (underscore-prefixed
 * leaves such as {@code _index.md}) are excluded from the page list —
 * they're generated outputs, not sources. {@code main.md} is a normal
 * curated page (no underscore) and is flagged as the space home.
 *
 * <p>Each page's body is scanned for {@code [[target]]} /
 * {@code [[target|label]]} occurrences so the {@link WikiService} can
 * build the backlink graph.
 */
@Component
public class WikiFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";
    public static final String MAIN_PAGE = "main";
    public static final String PAGE_EXTENSION = ".md";

    /** {@code [[target]]} or {@code [[target|label]]} — target is anything but {@code ]} or {@code |}. */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]*))?\\]\\]");

    private final DocumentService documentService;

    public WikiFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Result of scanning a wiki folder.
     *
     * @param folder  normalised wiki root
     * @param manifest the {@code _app.yaml} document
     * @param config  parsed {@code wiki:} config
     * @param spaces  distinct spaces (relative sub-folder paths, including
     *                every ancestor folder and the empty-string root),
     *                sorted; the root {@code ""} always present
     * @param pages   content + home pages (generated files excluded), sorted
     */
    public record Scan(
            String folder,
            DocumentDocument manifest,
            WikiConfig config,
            List<String> spaces,
            List<WikiPage> pages) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest = documentService.findByPath(
                tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No wiki manifest at '" + manifestPath + "'.");
        }
        WikiConfig config = parseConfig(manifest.get());

        String prefix = normalized + "/";
        List<DocumentDocument> all = documentService.listByKind(tenantId, projectId, "workpage");
        List<WikiPage> pages = new ArrayList<>();
        TreeSet<String> spaces = new TreeSet<>();
        spaces.add("");
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (!path.startsWith(prefix)) continue;
            String rel = path.substring(prefix.length());
            String leaf = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            // Generated / system-managed files (underscore leaf) are outputs.
            if (leaf.startsWith("_")) continue;

            int slash = rel.lastIndexOf('/');
            String space = slash < 0 ? "" : rel.substring(0, slash);
            String slug = slugify(stem(leaf));
            boolean main = MAIN_PAGE.equals(slug);

            PageBody body = readPageBody(doc);
            String title = body.title != null && !body.title.isBlank()
                    ? body.title
                    : humanise(slug);
            pages.add(new WikiPage(doc, rel, space, slug, title, main, body.links));

            // Register the space plus every ancestor folder as a space.
            addSpaceWithAncestors(spaces, space);
        }

        // Root pages first (space ""), then by space, then main-first, then title.
        pages.sort(Comparator
                .comparing(WikiPage::space)
                .thenComparing((WikiPage p) -> p.main() ? 0 : 1)
                .thenComparing((WikiPage p) -> p.title().toLowerCase(Locale.ROOT)));

        return new Scan(normalized, manifest.get(), config,
                new ArrayList<>(spaces), pages);
    }

    private static void addSpaceWithAncestors(TreeSet<String> spaces, String space) {
        if (space.isEmpty()) return;
        String cur = space;
        spaces.add(cur);
        int slash;
        while ((slash = cur.lastIndexOf('/')) > 0) {
            cur = cur.substring(0, slash);
            spaces.add(cur);
        }
    }

    private record PageBody(@Nullable String title, List<WikiLink> links) {}

    private PageBody readPageBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String title = doc.getTitle();
            String content = body;
            if (body.startsWith("---\n")) {
                int end = body.indexOf("\n---\n", 4);
                if (end > 0) {
                    String headerText = body.substring(4, end);
                    content = body.substring(end + 5);
                    Object loaded = new Yaml().load(headerText);
                    if (loaded instanceof Map<?, ?> m && m.get("title") != null) {
                        title = m.get("title").toString();
                    }
                }
            }
            return new PageBody(title, extractLinks(content));
        } catch (IOException | RuntimeException e) {
            return new PageBody(doc.getTitle(), List.of());
        }
    }

    /** Extract every {@code [[target]]} / {@code [[target|label]]} occurrence. */
    public static List<WikiLink> extractLinks(String content) {
        List<WikiLink> out = new ArrayList<>();
        Matcher m = WIKILINK.matcher(content);
        while (m.find()) {
            String target = m.group(1).trim();
            if (target.isEmpty()) continue;
            String label = m.group(2) != null ? m.group(2).trim() : null;
            out.add(new WikiLink(target, label == null || label.isBlank() ? null : label));
        }
        return out;
    }

    private WikiConfig parseConfig(DocumentDocument manifest) {
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return WikiConfig.parse(body);
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not parse wiki manifest '" + manifest.getPath() + "': "
                            + e.getMessage());
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

    /** Resolve the {@code _index.md} path for a space under the wiki root. */
    public static String indexPathFor(String folder, String space, String outputPath) {
        String out = outputPath == null || outputPath.isBlank() ? "_index.md" : outputPath.trim();
        while (out.startsWith("/")) out = out.substring(1);
        return space.isEmpty()
                ? folder + "/" + out
                : folder + "/" + space + "/" + out;
    }

    /**
     * Slugify a raw name to the wiki file-name convention: lower-case,
     * only {@code a-z 0-9 _ -}, other runs collapse to a single {@code -}.
     *
     * <p><b>Mirrored client-side</b> — byte-for-byte — in {@code slug.ts} of the
     * wiki addon's client, with a parity case-table in {@code slug.test.ts}. If
     * this algorithm changes, update both in the same PR; otherwise the client's
     * synchronous red-link check silently drifts from the filenames the server
     * generates here.
     */
    public static String slugify(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == '-' || c == ' ' || c == '/' || c == '.') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
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
