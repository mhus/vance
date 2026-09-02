package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public GtdService(DocumentService documentService,
                      GtdFolderReader folderReader,
                      GtdBucketResolver bucketResolver,
                      de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.bucketResolver = bucketResolver;
        this.contextFactory = contextFactory;
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

    // ── Move (bucket = set when; Inbox/Trash transitions relocate) ─

    /**
     * Front-matter key remembering the folder an action was in before it was
     * put in the trash, so restoring it puts it back where it came from —
     * including a {@code projects/<slug>/} membership, which the trash folder
     * would otherwise silently drop.
     *
     * <p>Set on every move <i>into</i> the trash and removed on every move out
     * of it, so it can never be read stale. An action trashed straight out of
     * the inbox gets no key: restoring it means processing it, and a processed
     * action belongs in {@code actions/} — the same thing a bucket move out of
     * the inbox does.
     */
    public static final String TRASHED_FROM = "trashedFrom";

    /**
     * Move an action to {@code bucket}. Sets the {@code when} attribute
     * (Today/Anytime/Someday/Upcoming); the Inbox and Trash transitions also
     * relocate the file between {@code inbox/} / {@code trash/} and the
     * working folders. {@code date} is required for {@link GtdBucket#UPCOMING}.
     *
     * <p>{@link GtdBucket#TRASH} is the one target that leaves {@code when}
     * alone: putting something away must not also rewrite when it was due, or
     * dragging it back out would land it somewhere it never was.
     */
    public DocumentDocument move(String tenantId, String projectId, String folder,
                                 GtdConfig config, String path, GtdBucket bucket,
                                 @Nullable String date, @Nullable String userId) {
        String normFolder = normalise(folder);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No action at '" + path + "'"));
        GtdActionDocument base = readAction(doc);
        boolean inInbox = path.startsWith(normFolder + "/" + config.inboxDir() + "/");
        boolean inTrash = path.startsWith(normFolder + "/" + config.trashDir() + "/");
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> extra = new LinkedHashMap<>(base.extra());

        String newWhen = base.when();
        String newPath = null;
        switch (bucket) {
            case TRASH -> {
                if (!inTrash) {
                    String from = currentDir(normFolder, path);
                    if (from.isBlank() || from.equals(config.inboxDir())) extra.remove(TRASHED_FROM);
                    else extra.put(TRASHED_FROM, from);
                    newPath = uniquePath(tenantId, projectId,
                            stripExt(normFolder + "/" + config.trashDir() + "/" + leaf));
                }
            }
            case INBOX -> {
                if (!inInbox) {
                    extra.remove(TRASHED_FROM);
                    newPath = uniquePath(tenantId, projectId,
                            stripExt(normFolder + "/" + config.inboxDir() + "/" + leaf));
                }
            }
            case TODAY -> { newWhen = GtdBucketResolver.WHEN_TODAY; newPath = outOfHolding(tenantId, projectId, normFolder, config, inInbox, inTrash, extra, leaf); }
            case ANYTIME -> { newWhen = ""; newPath = outOfHolding(tenantId, projectId, normFolder, config, inInbox, inTrash, extra, leaf); }
            case SOMEDAY -> { newWhen = GtdBucketResolver.WHEN_SOMEDAY; newPath = outOfHolding(tenantId, projectId, normFolder, config, inInbox, inTrash, extra, leaf); }
            case UPCOMING -> {
                if (date == null || date.isBlank()) {
                    throw new ToolException("Upcoming requires a date (yyyy-MM-dd)");
                }
                newWhen = date.trim();
                newPath = outOfHolding(tenantId, projectId, normFolder, config, inInbox, inTrash, extra, leaf);
            }
        }
        GtdActionDocument merged = new GtdActionDocument(
                GtdActionDocument.KIND, base.title(), newWhen, base.deadline(),
                base.contexts(), base.done(), base.body(), extra);
        String serialized = GtdActionCodec.serialize(merged, MD_MIME);
        DocumentDocument updated = documentService.update(
                doc.getId(), base.title(), nativeTags(merged),
                serialized, newPath, null, null, null, MD_MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(tenantId, userId, doc.getPath()));
        log.info("GtdService.move path='{}' bucket={} newPath='{}'", path, bucket, newPath);
        return updated;
    }

    // ── Re-file (project = folder; relocation only) ───────────────

    /**
     * Re-file an action into {@code projects/<slug>/}, or back into
     * {@code actions/} when {@code project} is blank. The one operation that
     * <b>only</b> relocates: no field of the action changes, so its derived
     * bucket stays the same — except that leaving {@code inbox/} makes it
     * processed, exactly as a bucket move out of the Inbox does. A no-op when
     * the action already sits in the target folder.
     */
    public DocumentDocument assignProject(String tenantId, String projectId, String folder,
                                          GtdConfig config, String path,
                                          @Nullable String project, @Nullable String userId) {
        String normFolder = normalise(folder);
        DocumentDocument doc = documentService.findByPath(tenantId, projectId, path)
                .orElseThrow(() -> new ToolException("No action at '" + path + "'"));
        String targetDir = projectDir(config, project);
        if (targetDir.equals(currentDir(normFolder, path))) return doc;
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        String newPath = uniquePath(tenantId, projectId,
                stripExt(normFolder + "/" + targetDir + "/" + leaf));
        DocumentDocument updated = documentService.update(
                doc.getId(), null, null, null, newPath,
                contextFactory.writeActor(tenantId, userId, doc.getPath()));
        log.info("GtdService.assignProject path='{}' project='{}' newPath='{}'",
                path, project, newPath);
        return updated;
    }

    /** Folder (relative to the GTD root) an action belongs in for {@code project}. */
    static String projectDir(GtdConfig config, @Nullable String project) {
        if (project == null || project.isBlank()) return config.actionsDir();
        String slug = GtdFolderReader.slugify(project);
        if (slug.isEmpty()) {
            throw new ToolException(
                    "Project '" + project + "' has no usable folder name");
        }
        return config.projectsDir() + "/" + slug;
    }

    /** The action's folder relative to the GTD root ({@code ""} directly at the root). */
    static String currentDir(String normFolder, String path) {
        String prefix = normFolder + "/";
        if (!path.startsWith(prefix)) {
            throw new ToolException(
                    "Action '" + path + "' is not inside '" + normFolder + "'");
        }
        String rel = path.substring(prefix.length());
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /**
     * Leaving one of the two holding folders — {@code inbox/} (unprocessed) or
     * {@code trash/} (put away) — relocates the file into a working folder;
     * an action already in one stays where it is.
     *
     * <p>Out of the inbox that folder is {@code actions/}. Out of the trash it
     * is whatever {@link #TRASHED_FROM} remembers, which is how a project
     * membership survives a round trip through the bin. The remembered value
     * is checked before it is used: it is written by us, but it lives in a
     * hand-editable front matter, and "restore" must not be a way to write
     * outside the GTD folder.
     */
    private @Nullable String outOfHolding(String tenantId, String projectId, String normFolder,
                                          GtdConfig config, boolean inInbox, boolean inTrash,
                                          Map<String, Object> extra, String leaf) {
        if (!inInbox && !inTrash) return null;
        String dir = config.actionsDir();
        if (inTrash) {
            Object from = extra.remove(TRASHED_FROM);
            String candidate = from == null ? "" : from.toString().trim();
            if (isSafeRelativeDir(candidate)) dir = candidate;
        }
        return uniquePath(tenantId, projectId,
                stripExt(normFolder + "/" + dir + "/" + leaf));
    }

    /** A folder we are willing to restore into: relative, inside the root, no traversal. */
    private static boolean isSafeRelativeDir(String dir) {
        if (dir.isBlank() || dir.startsWith("/")) return false;
        for (String segment : dir.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    /** What {@link #deleteAction} did — the caller reports it, it does not decide it. */
    public enum DeleteOutcome { TRASHED, PURGED, MISSING }

    /**
     * The delete key, and it means two different things depending on where the
     * action is. Outside the trash it <b>moves the action into it</b>: the
     * whole point of having a visible bin is that the destructive step is the
     * second one, taken deliberately, in a place the person can look at first.
     * Inside the trash it hands the document to the project-wide soft delete —
     * gone from the app, recoverable only with the document tools.
     */
    public DeleteOutcome deleteAction(String tenantId, String projectId, String folder,
                                      GtdConfig config, String path, @Nullable String userId) {
        String normFolder = normalise(folder);
        Optional<DocumentDocument> found = documentService.findByPath(tenantId, projectId, path);
        if (found.isEmpty()) return DeleteOutcome.MISSING;
        DocumentDocument doc = found.get();
        if (path.startsWith(normFolder + "/" + config.trashDir() + "/")) {
            documentService.trash(doc.getId(),
                    contextFactory.writeActor(tenantId, userId, doc.getPath()));
            log.info("GtdService.deleteAction purged path='{}'", path);
            return DeleteOutcome.PURGED;
        }
        move(tenantId, projectId, normFolder, config, path, GtdBucket.TRASH, null, userId);
        return DeleteOutcome.TRASHED;
    }

    /**
     * Sweep every completed action that is not already in the bin into
     * {@code trash/} — the tidy-up step, run from {@code refresh()}.
     *
     * <p>Ticking a box does not move anything (that would make a line vanish
     * from under the cursor); the list is cleared in one deliberate act
     * instead, and the result is reviewable rather than gone. A single failing
     * action does not abort the sweep: a rebuild that stops halfway is worse
     * than one that leaves one item behind and says so.
     *
     * @return how many actions were moved.
     */
    public int sweepDoneToTrash(String tenantId, String projectId, String folder,
                                GtdConfig config, GtdFolderReader.Scan scan,
                                @Nullable String userId) {
        int moved = 0;
        for (GtdAction a : scan.actions()) {
            if (!a.done() || a.inTrash()) continue;
            try {
                move(tenantId, projectId, folder, config, a.doc().getPath(),
                        GtdBucket.TRASH, null, userId);
                moved++;
            } catch (RuntimeException e) {
                log.warn("GtdService.sweepDoneToTrash could not move '{}': {}",
                        a.doc().getPath(), e.getMessage());
            }
        }
        if (moved > 0) {
            log.info("GtdService.sweepDoneToTrash tenant='{}' folder='{}' moved={}",
                    tenantId, folder, moved);
        }
        return moved;
    }

    // ── Bucket computation ────────────────────────────────────────

    /**
     * Group actions into their derived buckets for {@code today}.
     *
     * <p>Completed actions are <b>included</b>, in the bucket they were
     * completed in. Dropping them here is what made ticking a box look like a
     * delete: the line disappeared, and nothing on screen said where it went.
     * They leave the work list at {@code refresh()}, which sweeps them into
     * {@link GtdBucket#TRASH} in one visible step.
     */
    public Map<GtdBucket, List<GtdAction>> computeBuckets(GtdFolderReader.Scan scan, LocalDate today) {
        Map<GtdBucket, List<GtdAction>> map = new LinkedHashMap<>();
        for (GtdBucket b : GtdBucket.values()) map.put(b, new ArrayList<>());
        for (GtdAction a : scan.actions()) {
            GtdBucket bucket = bucketResolver.bucketOf(
                    a.inInbox(), a.inTrash(), a.when(), a.deadline(), today);
            map.get(bucket).add(a);
        }
        return map;
    }

    // ── Manual order within a bucket (§8b) ────────────────────────

    /** Convenience overload: take the order hint for {@code bucket} from the manifest. */
    public List<GtdAction> applyBucketOrder(
            GtdBucket bucket, GtdConfig config, List<GtdAction> bucketed) {
        return applyBucketOrder(bucket, bucketed,
                config.bucketOrder().getOrDefault(bucket, List.of()));
    }

    /**
     * Apply the manifest's per-bucket order hint to an already bucketed list
     * (§8b). Actions named in {@code order} come first in that order (only
     * those actually in the bucket — dead ids are dropped); the rest follow in
     * {@link #defaultOrder} . Returns a new list; the input is not mutated.
     *
     * <p>Every reader of a bucket goes through here — the interactive list, the
     * generated {@code _today.md}, and {@code gtd_query}. A reader that skipped
     * it would show the person a different sequence than the one they dragged
     * into place, which is the whole point of the feature.
     */
    public List<GtdAction> applyBucketOrder(
            GtdBucket bucket, List<GtdAction> bucketed, List<String> order) {
        if (order.isEmpty()) return defaultOrder(bucket, bucketed);
        Map<String, GtdAction> byId = new LinkedHashMap<>();
        for (GtdAction a : bucketed) byId.put(a.doc().getId(), a);
        List<GtdAction> out = new ArrayList<>(bucketed.size());
        Set<String> seen = new HashSet<>();
        for (String id : order) {
            GtdAction a = byId.get(id);
            if (a != null && seen.add(id)) out.add(a);
        }
        for (GtdAction a : defaultOrder(bucket, bucketed)) {
            if (!seen.contains(a.doc().getId())) out.add(a);
        }
        return out;
    }

    /**
     * Splice a caller's ordering of <b>part</b> of a bucket into the order the
     * manifest already records.
     *
     * <p>The subset matters: the middle list can be narrowed by a project or
     * context filter, so {@code requestedOrder} is regularly not the whole
     * bucket. Replacing the recorded list with it would drop every hidden
     * Action to the back — one drag under a filter would silently reshuffle the
     * bucket for everything the person could not see. Instead the named ids are
     * permuted <b>among the slots they already occupy</b>: unnamed Actions keep
     * their exact position, and an unfiltered reorder (every id named) still
     * reduces to "the list the caller sent".
     *
     * <p>Dead ids — deleted, done, or moved to another bucket — are dropped and
     * Actions the manifest does not mention yet are folded in at their
     * {@link #defaultOrder} position, so every reorder is also a small garbage
     * collection of the affected list.
     */
    public List<String> resyncBucketOrder(GtdBucket bucket, List<GtdAction> bucketed,
                                          List<String> existingOrder,
                                          List<String> requestedOrder) {
        Set<String> alive = new LinkedHashSet<>();
        for (GtdAction a : defaultOrder(bucket, bucketed)) alive.add(a.doc().getId());

        // The order as it stands right now: what the manifest records (minus the
        // dead), then everything it does not mention.
        List<String> base = new ArrayList<>();
        Set<String> inBase = new HashSet<>();
        for (String id : existingOrder) if (alive.contains(id) && inBase.add(id)) base.add(id);
        for (String id : alive) if (inBase.add(id)) base.add(id);

        List<String> named = new ArrayList<>();
        Set<String> isNamed = new HashSet<>();
        for (String id : requestedOrder) if (alive.contains(id) && isNamed.add(id)) named.add(id);
        if (named.isEmpty()) return base;

        List<String> out = new ArrayList<>(base.size());
        int next = 0;
        for (String id : base) out.add(isNamed.contains(id) ? named.get(next++) : id);
        return out;
    }

    /**
     * The sequence a bucket has before anybody drags anything — and the
     * position an Action falls back to when the manifest does not name it.
     *
     * <p>Alphabetical by title, except Upcoming, which is chronological: its
     * whole meaning is "later, in this order", and {@code _upcoming.md} groups
     * by date regardless — an alphabetical list beside it would be two answers
     * to one question. Comparison runs through a {@link Collator} rather than
     * {@code toLowerCase}, so "Ärger" sorts next to "Arbeit" and not behind
     * "Zettel". Ties break on the id so the order never depends on scan order.
     */
    private static List<GtdAction> defaultOrder(GtdBucket bucket, List<GtdAction> bucketed) {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.SECONDARY);
        Comparator<GtdAction> byTitle = Comparator.comparing(GtdAction::title, collator);
        Comparator<GtdAction> comparator = bucket == GtdBucket.UPCOMING
                ? Comparator.comparing(GtdAction::when).thenComparing(byTitle)
                : byTitle;
        List<GtdAction> out = new ArrayList<>(bucketed);
        out.sort(comparator.thenComparing(a -> a.doc().getId()));
        return out;
    }

    public List<GtdAction> overdue(GtdFolderReader.Scan scan, LocalDate today) {
        List<GtdAction> out = new ArrayList<>();
        for (GtdAction a : scan.actions()) {
            if (a.done() || a.inInbox() || a.inTrash()) continue;
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
                    nativeTags(action), MD_MIME, in, userId,
                    contextFactory.writeActor(tenantId, userId, path));
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
                serialized, null, null, null, null, MD_MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(doc.getTenantId(), null, doc.getPath()));
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
