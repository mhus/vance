package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentNote;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Issues domain logic — number reservation, create/update, comments (via
 * {@link DocumentNote}s) and archive/unarchive (file move). Persistence goes
 * through {@link DocumentService}.
 */
@Service
@Slf4j
public class IssuesService {

    private static final String MD_MIME = "text/markdown";
    private static final String YAML_MIME = "application/yaml";
    private static final String PAGE_EXT = ".md";
    private static final int MAX_NUMBER_ATTEMPTS = 50;

    private final DocumentService documentService;
    private final IssuesFolderReader folderReader;

    public IssuesService(DocumentService documentService, IssuesFolderReader folderReader) {
        this.documentService = documentService;
        this.folderReader = folderReader;
    }

    public IssuesFolderReader.Scan scan(String tenantId, String projectId, String folder) {
        return folderReader.scan(tenantId, projectId, folder);
    }

    // ── Read ──────────────────────────────────────────────────────

    public IssueDocument readIssue(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return IssueCodec.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), doc.getMimeType());
        } catch (IOException | RuntimeException e) {
            throw new ToolException("Could not read issue '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Create (with stable-number reservation) ───────────────────

    /**
     * Create a new issue under {@code items/}. The number is reserved
     * monotonically from the manifest's {@code nextNumber}, floored to
     * {@code max(existing)+1} so it self-heals a stale counter. The unique
     * {@code (tenant,project,path)} index is the hard guard against a
     * concurrent duplicate — a clash retries with the next number.
     */
    public DocumentDocument createIssue(String tenantId, String projectId, String folder,
                                        String title, @Nullable List<String> labels,
                                        @Nullable String assignee, @Nullable String priority,
                                        @Nullable String body, @Nullable String userId) {
        if (title == null || title.isBlank()) throw new ToolException("title is required");
        String normalized = IssuesFolderReader.normaliseFolder(folder);

        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            IssuesFolderReader.Scan scan = folderReader.scan(tenantId, projectId, normalized);
            IssuesConfig config = scan.config();
            int floor = folderReader.maxNumber(tenantId, projectId, normalized, config) + 1;
            int number = Math.max(config.nextNumber(), floor) + attempt;

            String slug = IssuesFolderReader.slugify(title);
            if (slug.isEmpty()) slug = "issue";
            String path = normalized + "/" + config.itemsDir() + "/" + number + "-" + slug + PAGE_EXT;

            IssueDocument issue = new IssueDocument(IssueDocument.KIND, number, title.trim(),
                    IssueDocument.STATE_OPEN, cleanList(labels), nullIfBlank(assignee),
                    nullIfBlank(priority), body == null ? "" : body, new java.util.LinkedHashMap<>());
            String serialized = IssueCodec.serialize(issue, MD_MIME);
            try (InputStream in = new ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8))) {
                DocumentDocument stored = documentService.create(tenantId, projectId, path,
                        title.trim(), nativeTags(issue), MD_MIME, in, userId);
                bumpNextNumber(scan.manifest(), config, number + 1);
                log.info("IssuesService.createIssue tenant='{}' #{} path='{}'", tenantId, number, path);
                return stored;
            } catch (DocumentService.DocumentAlreadyExistsException clash) {
                log.debug("issue number {} taken, retrying", number);
            } catch (IOException e) {
                throw new ToolException("Could not write issue '" + path + "': " + e.getMessage());
            }
        }
        throw new ToolException("Could not reserve a free issue number after "
                + MAX_NUMBER_ATTEMPTS + " attempts.");
    }

    private void bumpNextNumber(DocumentDocument manifest, IssuesConfig config, int next) {
        if (next <= config.nextNumber()) return; // never decrease
        try {
            documentService.update(manifest.getId(),
                    manifest.getTitle(), List.of("application", "issues"),
                    config.withNextNumber(next).render(), null, null, null, null, YAML_MIME);
        } catch (RuntimeException e) {
            // Best-effort: the unique-path guard + max()+1 floor keep numbers
            // correct even if this bump loses a race. Log and move on.
            log.debug("nextNumber bump to {} failed (non-fatal): {}", next, e.getMessage());
        }
    }

    // ── Update (in-place field patch) ─────────────────────────────

    public DocumentDocument updateIssue(String tenantId, String projectId, String path,
                                        @Nullable String state, @Nullable List<String> labels,
                                        @Nullable String assignee, @Nullable String priority,
                                        @Nullable String title, @Nullable String body) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No issue at '" + path + "'"));
        IssueDocument base = readIssue(doc);
        IssueDocument merged = new IssueDocument(IssueDocument.KIND, base.number(),
                title != null && !title.isBlank() ? title.trim() : base.title(),
                state != null && !state.isBlank() ? state.trim() : base.state(),
                labels != null ? cleanList(labels) : base.labels(),
                assignee != null ? nullIfBlank(assignee) : base.assignee(),
                priority != null ? nullIfBlank(priority) : base.priority(),
                body != null ? body : base.body(),
                base.extra());
        String serialized = IssueCodec.serialize(merged, MD_MIME);
        return documentService.update(doc.getId(), merged.title(), nativeTags(merged),
                serialized, null, null, null, null, MD_MIME);
    }

    public DocumentDocument setState(String tenantId, String projectId, String path, String state) {
        return updateIssue(tenantId, projectId, path, state, null, null, null, null, null);
    }

    // ── Comments (DocumentNotes) ──────────────────────────────────

    public DocumentNote addComment(String tenantId, String projectId, String path,
                                   String text, @Nullable String userId) {
        if (text == null || text.isBlank()) throw new ToolException("comment text is required");
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No issue at '" + path + "'"));
        return documentService.addNote(doc.getId(), text.trim(),
                userId == null ? "unknown" : userId, null);
    }

    public List<DocumentNote> listComments(DocumentDocument doc) {
        List<DocumentNote> notes = new ArrayList<>(documentService.listNotes(doc.getId()));
        notes.sort(java.util.Comparator.comparing(
                DocumentNote::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return notes;
    }

    public boolean deleteComment(String tenantId, String projectId, String path, String commentId) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No issue at '" + path + "'"));
        return documentService.deleteNote(doc.getId(), commentId);
    }

    // ── Archive / unarchive (file move) ───────────────────────────

    public DocumentDocument archive(String tenantId, String projectId, String folder,
                                    IssuesConfig config, String path) {
        return relocate(tenantId, projectId, folder, config, path, config.archiveDir());
    }

    public DocumentDocument unarchive(String tenantId, String projectId, String folder,
                                      IssuesConfig config, String path) {
        return relocate(tenantId, projectId, folder, config, path, config.itemsDir());
    }

    private DocumentDocument relocate(String tenantId, String projectId, String folder,
                                      IssuesConfig config, String path, String targetDir) {
        String normalized = IssuesFolderReader.normaliseFolder(folder);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No issue at '" + path + "'"));
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        String base = normalized + "/" + targetDir + "/" + leaf;
        if (base.equals(path)) return doc; // already there
        String newPath = uniquePath(tenantId, projectId, stripExt(base));
        DocumentDocument moved = documentService.update(doc.getId(),
                null, null, null, newPath, null, null, null, null);
        log.info("IssuesService.relocate '{}' -> '{}'", path, newPath);
        return moved;
    }

    public void trash(String tenantId, String projectId, String path, @Nullable String userId) {
        documentService.findByPath(tenantId, projectId, path)
                .ifPresent(d -> documentService.trash(d.getId(), userId));
    }

    // ── Search ────────────────────────────────────────────────────

    public DocumentService.DocumentMetaListing search(String tenantId, String projectId, String folder,
                                                       @Nullable String query, @Nullable String label,
                                                       int limit) {
        String prefix = IssuesFolderReader.normaliseFolder(folder) + "/";
        List<String> requireTags = label != null && !label.isBlank() ? List.of(label.trim()) : List.of();
        return documentService.searchProjectDocumentsMeta(
                tenantId, projectId, prefix, query, requireTags, new java.util.LinkedHashMap<>(), limit);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static List<String> nativeTags(IssueDocument issue) {
        List<String> tags = new ArrayList<>();
        tags.add("issue");
        for (String l : issue.labels()) if (!tags.contains(l)) tags.add(l);
        return tags;
    }

    private String uniquePath(String tenantId, String projectId, String base) {
        String candidate = base + PAGE_EXT;
        if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) return candidate;
        for (int n = 2; n < 1000; n++) {
            candidate = base + "-" + n + PAGE_EXT;
            if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) return candidate;
        }
        throw new ToolException("Could not find a free path under '" + base + "'");
    }

    private static String stripExt(String pathWithLeaf) {
        int dot = pathWithLeaf.lastIndexOf('.');
        int slash = pathWithLeaf.lastIndexOf('/');
        return dot > slash ? pathWithLeaf.substring(0, dot) : pathWithLeaf;
    }

    private static List<String> cleanList(@Nullable List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) if (s != null && !s.isBlank() && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    private static @Nullable String nullIfBlank(@Nullable String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
