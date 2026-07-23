package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * GTD domain logic — capture, action create/update, and the {@code move}
 * operation whose semantics are the crux of the app: moving to a bucket
 * <b>sets the {@code when} attribute</b> (Today/Anytime/Someday/Upcoming); only
 * the Inbox transition relocates the file. Buckets are computed by
 * {@link GtdBucketResolver}. Persistence goes through {@link DocumentService}.
 */
@Service
@Slf4j
public class GtdService {

    private static final String MD_MIME = "text/markdown";

    private final DocumentService documentService;
    private final GtdFolderReader folderReader;
    private final GtdBucketResolver bucketResolver;

    public GtdService(DocumentService documentService,
                      GtdFolderReader folderReader,
                      GtdBucketResolver bucketResolver) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.bucketResolver = bucketResolver;
    }

    public GtdFolderReader.Scan scan(String tenantId, String projectId, String folder) {
        return folderReader.scan(tenantId, projectId, folder);
    }

    public GtdBucketResolver bucketResolver() {
        return bucketResolver;
    }

    // ── Read ──────────────────────────────────────────────────────

    public GtdActionDocument readAction(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return GtdActionCodec.parse(body, doc.getMimeType());
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not read action '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Capture / create ──────────────────────────────────────────

    /** Quick capture into {@code inbox/} — the fast unprocessed path. */
    public DocumentDocument capture(String tenantId, String projectId, String folder,
                                    GtdConfig config, String title, @Nullable String note,
                                    @Nullable String userId) {
        if (title == null || title.isBlank()) throw new ToolException("title is required");
        String base = normalise(folder) + "/" + config.inboxDir() + "/"
                + slugOrDefault(title);
        String path = uniquePath(tenantId, projectId, base);
        GtdActionDocument action = new GtdActionDocument(
                GtdActionDocument.KIND, title.trim(), "", null,
                new ArrayList<>(), false, note == null ? "" : note, new LinkedHashMap<>());
        return create(tenantId, projectId, path, action, userId);
    }

    /** Create a processed action under {@code actions/} or {@code projects/<project>/}. */
    public DocumentDocument createAction(String tenantId, String projectId, String folder,
                                         GtdConfig config, String title, @Nullable String when,
                                         @Nullable String deadline, @Nullable List<String> contexts,
                                         @Nullable String project, @Nullable String body,
                                         @Nullable String userId) {
        if (title == null || title.isBlank()) throw new ToolException("title is required");
        String dir = project != null && !project.isBlank()
                ? config.projectsDir() + "/" + GtdFolderReader.slugify(project)
                : config.actionsDir();
        String base = normalise(folder) + "/" + dir + "/" + slugOrDefault(title);
        String path = uniquePath(tenantId, projectId, base);
        GtdActionDocument action = new GtdActionDocument(
                GtdActionDocument.KIND, title.trim(),
                when == null ? "" : when.trim(), nullIfBlank(deadline),
                cleanList(contexts), false, body == null ? "" : body, new LinkedHashMap<>());
        return create(tenantId, projectId, path, action, userId);
    }

    // ── Update (in-place field patch) ─────────────────────────────

    public DocumentDocument updateAction(String tenantId, String projectId, String path,
                                         @Nullable String when, @Nullable String deadline,
                                         @Nullable List<String> contexts, @Nullable Boolean done,
                                         @Nullable String title, @Nullable String body) {
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No action at '" + path + "'"));
        GtdActionDocument base = readAction(doc);
        GtdActionDocument merged = new GtdActionDocument(
                GtdActionDocument.KIND,
                title != null && !title.isBlank() ? title.trim() : base.title(),
                when != null ? when.trim() : base.when(),
                deadline != null ? nullIfBlank(deadline) : base.deadline(),
                contexts != null ? cleanList(contexts) : base.contexts(),
                done != null ? done : base.done(),
                body != null ? body : base.body(),
                base.extra());
        return writeExisting(doc, merged);
    }

    // ── Move (bucket = set when; Inbox transition relocates) ──────

    /**
     * Move an action to {@code bucket}. Sets the {@code when} attribute
     * (Today/Anytime/Someday/Upcoming); the Inbox transition also relocates
     * the file between {@code inbox/} and {@code actions/}. {@code date} is
     * required for {@link GtdBucket#UPCOMING}.
     */
    public DocumentDocument move(String tenantId, String projectId, String folder,
                                 GtdConfig config, String path, GtdBucket bucket,
                                 @Nullable String date, @Nullable String userId) {
        String normFolder = normalise(folder);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No action at '" + path + "'"));
        GtdActionDocument base = readAction(doc);
        boolean inInbox = path.startsWith(normFolder + "/" + config.inboxDir() + "/");
        String leaf = path.substring(path.lastIndexOf('/') + 1);

        String newWhen = base.when();
        String newPath = null;
        switch (bucket) {
            case INBOX -> {
                if (!inInbox) {
                    newPath = uniquePath(tenantId, projectId,
                            stripExt(normFolder + "/" + config.inboxDir() + "/" + leaf));
                }
            }
            case TODAY -> { newWhen = GtdBucketResolver.WHEN_TODAY; newPath = outOfInbox(tenantId, projectId, normFolder, config, inInbox, leaf); }
            case ANYTIME -> { newWhen = ""; newPath = outOfInbox(tenantId, projectId, normFolder, config, inInbox, leaf); }
            case SOMEDAY -> { newWhen = GtdBucketResolver.WHEN_SOMEDAY; newPath = outOfInbox(tenantId, projectId, normFolder, config, inInbox, leaf); }
            case UPCOMING -> {
                if (date == null || date.isBlank()) {
                    throw new ToolException("Upcoming requires a date (yyyy-MM-dd)");
                }
                newWhen = date.trim();
                newPath = outOfInbox(tenantId, projectId, normFolder, config, inInbox, leaf);
            }
        }
        GtdActionDocument merged = new GtdActionDocument(
                GtdActionDocument.KIND, base.title(), newWhen, base.deadline(),
                base.contexts(), base.done(), base.body(), base.extra());
        String serialized = GtdActionCodec.serialize(merged, MD_MIME);
        DocumentDocument updated = documentService.update(
                doc.getId(), base.title(), nativeTags(merged),
                serialized, newPath, null, null, null, MD_MIME);
        log.info("GtdService.move path='{}' bucket={} newPath='{}'", path, bucket, newPath);
        return updated;
    }

    /** When leaving the Inbox, relocate into {@code actions/}; else stay in place. */
    private @Nullable String outOfInbox(String tenantId, String projectId, String normFolder,
                                        GtdConfig config, boolean inInbox, String leaf) {
        if (!inInbox) return null;
        return uniquePath(tenantId, projectId,
                stripExt(normFolder + "/" + config.actionsDir() + "/" + leaf));
    }

    public void trash(String tenantId, String projectId, String path, @Nullable String userId) {
        documentService.findByPath(tenantId, projectId, path)
                .ifPresent(d -> documentService.trash(d.getId(), userId));
    }

    // ── Bucket computation ────────────────────────────────────────

    /** Group non-done actions into their derived buckets for {@code today}. */
    public Map<GtdBucket, List<GtdAction>> computeBuckets(GtdFolderReader.Scan scan, LocalDate today) {
        Map<GtdBucket, List<GtdAction>> map = new LinkedHashMap<>();
        for (GtdBucket b : GtdBucket.values()) map.put(b, new ArrayList<>());
        for (GtdAction a : scan.actions()) {
            if (a.done()) continue;
            GtdBucket bucket = bucketResolver.bucketOf(a.inInbox(), a.when(), a.deadline(), today);
            map.get(bucket).add(a);
        }
        return map;
    }

    public List<GtdAction> overdue(GtdFolderReader.Scan scan, LocalDate today) {
        List<GtdAction> out = new ArrayList<>();
        for (GtdAction a : scan.actions()) {
            if (a.done() || a.inInbox()) continue;
            if (bucketResolver.isOverdue(a.when(), a.deadline(), today)) out.add(a);
        }
        return out;
    }

    // ── Search (shared metadata + summary path) ───────────────────

    public DocumentService.DocumentMetaListing search(
            String tenantId, String projectId, String folder,
            @Nullable String query, @Nullable String context, int limit) {
        String prefix = normalise(folder) + "/";
        List<String> requireTags = context != null && !context.isBlank()
                ? List.of(context.trim()) : List.of();
        return documentService.searchProjectDocumentsMeta(
                tenantId, projectId, prefix, query, requireTags, new LinkedHashMap<>(), limit);
    }

    // ── Persistence helpers ───────────────────────────────────────

    private DocumentDocument create(String tenantId, String projectId, String path,
                                    GtdActionDocument action, @Nullable String userId) {
        String serialized = GtdActionCodec.serialize(action, MD_MIME);
        try (InputStream in = new ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, path, action.title(),
                    nativeTags(action), MD_MIME, in, userId);
            log.info("GtdService.create tenant='{}' path='{}'", tenantId, path);
            return stored;
        } catch (IOException e) {
            throw new ToolException("Could not write action '" + path + "': " + e.getMessage());
        }
    }

    private DocumentDocument writeExisting(DocumentDocument doc, GtdActionDocument action) {
        String serialized = GtdActionCodec.serialize(action, MD_MIME);
        return documentService.update(
                doc.getId(), action.title(), nativeTags(action),
                serialized, null, null, null, null, MD_MIME);
    }

    private static List<String> nativeTags(GtdActionDocument action) {
        List<String> tags = new ArrayList<>();
        tags.add("gtd");
        tags.add("action");
        for (String c : action.contexts()) if (!tags.contains(c)) tags.add(c);
        return tags;
    }

    private String uniquePath(String tenantId, String projectId, String base) {
        String candidate = base + GtdFolderReader.PAGE_EXTENSION;
        if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) return candidate;
        for (int n = 2; n < 1000; n++) {
            candidate = base + "-" + n + GtdFolderReader.PAGE_EXTENSION;
            if (documentService.findByPath(tenantId, projectId, candidate).isEmpty()) return candidate;
        }
        throw new ToolException("Could not find a free slug under '" + base + "'");
    }

    private static String stripExt(String pathWithLeaf) {
        int dot = pathWithLeaf.lastIndexOf('.');
        int slash = pathWithLeaf.lastIndexOf('/');
        return dot > slash ? pathWithLeaf.substring(0, dot) : pathWithLeaf;
    }

    private static String slugOrDefault(String title) {
        String slug = GtdFolderReader.slugify(title);
        return slug.isEmpty() ? "action" : slug;
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

    private static String normalise(String folder) {
        return GtdFolderReader.normaliseFolder(folder);
    }
}
