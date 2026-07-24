package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Wiki domain logic — slug/name index across spaces, {@code [[Wikilink]]}
 * resolution, page creation and the backlink graph. All persistence goes
 * through {@link DocumentService} (data sovereignty — the wiki owns no
 * MongoDB collection of its own and never touches {@code MongoTemplate}).
 */
@Service
@Slf4j
public class WikiService {

    private static final String MD_MIME = "text/markdown";

    private final DocumentService documentService;
    private final WikiFolderReader folderReader;
    private final SecurityContextFactory contextFactory;

    public WikiService(DocumentService documentService, WikiFolderReader folderReader,
                       SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.contextFactory = contextFactory;
    }

    public WikiFolderReader.Scan scan(String tenantId, String projectId, String folder) {
        return folderReader.scan(tenantId, projectId, folder);
    }

    // ── Resolution ────────────────────────────────────────────────

    /**
     * Outcome of resolving a {@code [[target]]}.
     *
     * @param exists      whether a page was found
     * @param page        the resolved page ({@code null} when missing)
     * @param ambiguous   the slug matched more than one page (first-match returned)
     * @param slug        the slugified name portion of the target
     * @param createSpace the space a red-link create would use (explicit
     *                    space from the target, else the current space)
     */
    public record Resolution(
            boolean exists,
            @Nullable WikiPage page,
            boolean ambiguous,
            String slug,
            String createSpace) {}

    /**
     * Resolve a {@code [[target]]} against a scan, space-aware, per the
     * planning §3 cascade:
     * <ol>
     *   <li>explicit {@code Space/Name} → that space's slug;</li>
     *   <li>slug in the current space;</li>
     *   <li>globally-unique slug;</li>
     *   <li>ambiguous → first match + flag;</li>
     *   <li>missing → red link (create under {@code createSpace}).</li>
     * </ol>
     */
    public Resolution resolve(WikiFolderReader.Scan scan, String currentSpace, String target) {
        String cur = currentSpace == null ? "" : currentSpace;
        String raw = target.trim();
        @Nullable String explicitSpace = null;
        String namePart = raw;
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) {
            explicitSpace = normaliseSpace(raw.substring(0, slash));
            namePart = raw.substring(slash + 1);
        }
        String slug = WikiFolderReader.slugify(namePart);
        String createSpace = explicitSpace != null ? explicitSpace : cur;

        if (slug.isEmpty()) {
            return new Resolution(false, null, false, slug, createSpace);
        }

        // 1. Explicit space wins outright.
        if (explicitSpace != null) {
            for (WikiPage p : scan.pages()) {
                if (p.slug().equals(slug) && p.space().equals(explicitSpace)) {
                    return new Resolution(true, p, false, slug, createSpace);
                }
            }
            return new Resolution(false, null, false, slug, createSpace);
        }

        // 2. Current-space match.
        for (WikiPage p : scan.pages()) {
            if (p.slug().equals(slug) && p.space().equals(cur)) {
                return new Resolution(true, p, false, slug, createSpace);
            }
        }

        // 3./4. Global slug match — unique or first-of-many.
        List<WikiPage> matches = new ArrayList<>();
        for (WikiPage p : scan.pages()) {
            if (p.slug().equals(slug)) matches.add(p);
        }
        if (matches.isEmpty()) {
            return new Resolution(false, null, false, slug, createSpace);
        }
        return new Resolution(true, matches.get(0), matches.size() > 1, slug, createSpace);
    }

    // ── Listing ───────────────────────────────────────────────────

    /** Content + home pages of the wiki (already filtered of generated files). */
    public List<WikiPage> listPages(WikiFolderReader.Scan scan) {
        return scan.pages();
    }

    /** Pages belonging to a space, recursive over sub-spaces. */
    public List<WikiPage> pagesInSpace(WikiFolderReader.Scan scan, String space) {
        String s = normaliseSpace(space);
        List<WikiPage> out = new ArrayList<>();
        String descendantPrefix = s.isEmpty() ? "" : s + "/";
        for (WikiPage p : scan.pages()) {
            if (s.isEmpty() || p.space().equals(s) || p.space().startsWith(descendantPrefix)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Wiki-global pages ordered by most-recently modified first. Vance
     * documents carry no {@code updatedAt}; the effective timestamp is
     * {@code lastArchivedAt} (bumped on content-changing saves) falling
     * back to {@code createdAt}.
     */
    public List<WikiPage> recentlyModified(WikiFolderReader.Scan scan, int limit) {
        int safe = Math.max(1, limit);
        List<WikiPage> sorted = new ArrayList<>(scan.pages());
        sorted.sort(Comparator.comparing(
                (WikiPage p) -> effectiveModified(p.doc()),
                Comparator.reverseOrder()));
        return sorted.size() > safe ? new ArrayList<>(sorted.subList(0, safe)) : sorted;
    }

    private static Instant effectiveModified(DocumentDocument doc) {
        if (doc.getLastArchivedAt() != null) return doc.getLastArchivedAt();
        if (doc.getCreatedAt() != null) return doc.getCreatedAt();
        return Instant.EPOCH;
    }

    // ── Backlink graph ────────────────────────────────────────────

    /**
     * Build the inbound-link graph: for every page, resolve each of its
     * {@code [[…]]} links against the page's own space and record the
     * source under the resolved target. Keyed by target
     * {@code relativePath}; values are inbound {@code relativePath}s
     * (deduplicated, self-links dropped), in stable page order.
     */
    public Map<String, List<String>> buildBacklinks(WikiFolderReader.Scan scan) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (WikiPage source : scan.pages()) {
            for (WikiLink link : source.links()) {
                Resolution r = resolve(scan, source.space(), link.target());
                if (!r.exists() || r.page() == null) continue;
                String targetRel = r.page().relativePath();
                if (targetRel.equals(source.relativePath())) continue;
                List<String> inbound = graph.computeIfAbsent(targetRel, k -> new ArrayList<>());
                if (!inbound.contains(source.relativePath())) {
                    inbound.add(source.relativePath());
                }
            }
        }
        return graph;
    }

    // ── Page creation ─────────────────────────────────────────────

    /**
     * Create a new {@code kind: workpage} page in a wiki space. The page is
     * a plain Markdown document with a {@code $meta.kind: workpage} header
     * (no dependency on the workbook addon). Slug is derived from the
     * title; the path is made unique inside the space.
     *
     * @return the stored document
     */
    public DocumentDocument createPage(
            String tenantId, String projectId, String folder,
            @Nullable String space, String title, @Nullable String userId) {
        if (title == null || title.isBlank()) throw new ToolException("title is required");
        String normalisedFolder = WikiFolderReader.normaliseFolder(folder);
        String normalisedSpace = normaliseSpace(space);
        String slug = WikiFolderReader.slugify(title);
        if (slug.isEmpty()) slug = "page";

        String base = normalisedSpace.isEmpty()
                ? normalisedFolder + "/" + slug
                : normalisedFolder + "/" + normalisedSpace + "/" + slug;
        String path = uniquePath(tenantId, projectId, base);

        String body = workpageStub(title);
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, path, title,
                    List.of("wiki", "workpage"), MD_MIME, in, userId,
                    contextFactory.writeActor(tenantId, userId, path));
            log.info("WikiService.createPage tenant='{}' folder='{}' path='{}'",
                    tenantId, normalisedFolder, path);
            return stored;
        } catch (IOException e) {
            throw new ToolException("Could not write wiki page '" + path + "': " + e.getMessage());
        }
    }

    /** Seed body for a new wiki page — a minimal {@code kind: workpage} document. */
    public static String workpageStub(String title) {
        return "---\n$meta:\n  kind: workpage\ntitle: \"" + escape(title) + "\"\n---\n"
                + "# " + title + "\n\n";
    }

    private String uniquePath(String tenantId, String projectId, String base) {
        String candidate = base + WikiFolderReader.PAGE_EXTENSION;
        if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) {
            return candidate;
        }
        for (int n = 2; n < 1000; n++) {
            candidate = base + "-" + n + WikiFolderReader.PAGE_EXTENSION;
            if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ToolException("Could not find a free slug under '" + base + "'");
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Slugify each segment of a space path; blank / null → root (""). */
    public static String normaliseSpace(@Nullable String space) {
        if (space == null) return "";
        String s = space.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        String[] segs = s.split("/");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            String slug = WikiFolderReader.slugify(seg);
            if (slug.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(slug);
        }
        return sb.toString();
    }

    public Optional<DocumentDocument> findByPath(String tenantId, String projectId, String path) {
        return documentService.findByPath(tenantId, projectId, path);
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }
}
