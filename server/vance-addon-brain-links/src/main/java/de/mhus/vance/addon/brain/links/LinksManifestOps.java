package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.web.LinkPreviewDto;
import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.brain.tools.web.LinkPreviewService;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read-modify-write mutations on a links manifest. Every mutation loads
 * the manifest, edits the typed {@link LinksConfig}, and writes the whole
 * block back through {@link LinksStore} — never a partial YAML patch.
 *
 * <p>Entries are addressed by {@link LinkUrls#identity}, so a caller may
 * pass the URL as the reader sees it on the card and hit the same row.
 *
 * <p><b>The null/blank convention</b> runs through every update method and
 * is the same one the binder uses: {@code null} leaves a field alone, a
 * blank string clears it. It matters because a link manager is edited in
 * small touches — moving one entry between groups must not silently drop
 * the teaser somebody wrote for it. The one field where "clear" means
 * something more specific is {@code title}: clearing it re-derives the
 * snapshot from the page, because a title is the field we promised to keep
 * readable even when the site is gone.
 *
 * <p><b>Mutations of one manifest are serialised.</b> Read-modify-write on a
 * whole document has no field-level merge and no optimistic guard —
 * {@code DocumentService.update} re-reads the row by id and writes over it, so
 * the version the caller loaded is never compared. Two actors adding a link at
 * the same time both read <i>n</i> entries and both write <i>n+1</i>: the first
 * one written is gone, with no error and no trace. That is not a hypothetical
 * here, because a links app is the app an agent and a person typically fill in
 * together. The lock is per {@code (tenant, project, folder)} and JVM-local —
 * enough, because a project's documents are served by its home pod, and the
 * honest alternative (a version on the update funnel) is a change to
 * {@code DocumentService}, not to this app.
 */
@Component
@Slf4j
public class LinksManifestOps {

    /**
     * Longest title snapshot kept for a card. A card label, not a page: the
     * page itself is one {@code web_fetch} away and always current.
     */
    static final int MAX_TITLE_CHARS = 300;

    /**
     * Striped rather than one lock per manifest: a map keyed by folder would
     * have to be pruned, and a manifest mutation is short enough that the
     * occasional collision between two unrelated folders costs nothing.
     */
    private static final int LOCK_STRIPES = 32;

    private static final ReentrantLock[] LOCKS = newLocks();

    private static ReentrantLock[] newLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private final LinksStore store;
    private final LinkPreviewService linkPreview;

    public LinksManifestOps(LinksStore store, LinkPreviewService linkPreview) {
        this.store = store;
        this.linkPreview = linkPreview;
    }

    /** Fields of an add or update. {@code null} = not given. */
    public record LinkFields(
            @Nullable String title,
            @Nullable String teaser,
            @Nullable String image,
            @Nullable String group,
            @Nullable List<String> tags,
            @Nullable String note) {

        public static LinkFields none() {
            return new LinkFields(null, null, null, null, null, null);
        }
    }

    /**
     * Outcome of an add.
     *
     * <p>{@code entry} is the row that is now in the list — the freshly added
     * one, or the one that was already there. Both matter to a caller that has
     * to tell somebody what happened: "saved" and "already in *Rust*" are
     * different answers, and the second needs the existing row to say where.
     */
    public record CaptureResult(boolean added, LinkEntry entry) {}

    /**
     * Add a link at the end of its group. Idempotent on the URL: adding one
     * that is already in the list changes nothing (and says so in the log)
     * rather than producing a second card for the same page.
     *
     * <p>When no title is given, one is fetched once from the link-preview
     * proxy and stored. That single fetch is the whole reason the list stays
     * readable later — see {@link LinkEntry}.
     *
     * @return true when an entry was added, false when it was already there.
     */
    public boolean addEntry(String tenantId, String projectId, String folder,
                            String url, LinkFields fields, @Nullable String userId) {
        return capture(tenantId, projectId, folder, url, fields, userId).added();
    }

    /**
     * {@link #addEntry} with the resulting row reported back.
     *
     * <p>Same code path — the boolean overload delegates here — because two
     * add implementations would be two idempotency rules, and this one is
     * load-bearing for every caller that saves the same page twice.
     */
    public CaptureResult capture(String tenantId, String projectId, String folder,
                                 String url, LinkFields fields, @Nullable String userId) {
        return mutate(tenantId, projectId, folder,
                () -> addEntryLocked(tenantId, projectId, folder, url, fields, userId));
    }

    private CaptureResult addEntryLocked(String tenantId, String projectId, String folder,
                                         String url, LinkFields fields, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        String id = LinkUrls.identity(url);
        LinkEntry existing = find(loaded.config().entries(), id);
        if (existing != null) {
            log.debug("LinksManifestOps.addEntry folder='{}' url='{}' already present",
                    folder, id);
            return new CaptureResult(false, existing);
        }
        String group = blankToNull(fields.group());
        String title = blankToNull(fields.title());
        if (title == null) {
            title = fetchTitle(id, tenantId, projectId);
        }
        LinkEntry entry = new LinkEntry(id, title,
                blankToNull(fields.teaser()), blankToNull(checkedImage(fields.image())), group,
                fields.tags() == null ? List.of() : cleanTags(fields.tags()),
                blankToNull(fields.note()), Instant.now(), null);

        List<LinkEntry> entries = insertGrouped(loaded.config().entries(), entry);
        store.saveConfig(loaded, withEntries(loaded.config(), entries, group), userId);
        log.info("LinksManifestOps.addEntry tenant='{}' folder='{}' url='{}' group='{}'",
                tenantId, folder, id, group == null ? "" : group);
        return new CaptureResult(true, entry);
    }

    /**
     * The row for this URL, or {@code null}.
     *
     * <p>A read rather than a mutation, so it takes no lock: a stale answer
     * here costs a badge that is one save behind, and holding the write lock
     * for a lookup that an extension fires on every page load would not.
     */
    public @Nullable LinkEntry lookup(String tenantId, String projectId, String folder,
                                      String url) {
        return find(store.load(tenantId, projectId, folder).config().entries(),
                LinkUrls.identity(url));
    }

    /** Remove the entry with this URL. Unknown URL is an error, not a no-op. */
    public void removeEntry(String tenantId, String projectId, String folder,
                            String url, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> removeEntryLocked(tenantId, projectId, folder, url, userId));
    }

    private void removeEntryLocked(String tenantId, String projectId, String folder,
                                   String url, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        String id = LinkUrls.identity(url);
        List<LinkEntry> entries = new ArrayList<>(loaded.config().entries());
        boolean removed = entries.removeIf(e -> e.url().equals(id));
        if (!removed) throw new ToolException("No link entry for '" + id + "'.");
        store.saveConfig(loaded, withEntries(loaded.config(), entries, null), userId);
        log.info("LinksManifestOps.removeEntry tenant='{}' folder='{}' url='{}'",
                tenantId, folder, id);
    }

    /**
     * Edit one entry. See the class comment for the null/blank convention;
     * a blank {@code title} re-derives the snapshot from the page.
     */
    public void updateEntry(String tenantId, String projectId, String folder,
                            String url, LinkFields fields, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> updateEntryLocked(tenantId, projectId, folder, url, fields, userId));
    }

    private void updateEntryLocked(String tenantId, String projectId, String folder,
                                   String url, LinkFields fields, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        String id = LinkUrls.identity(url);
        LinkEntry current = find(loaded.config().entries(), id);
        if (current == null) throw new ToolException("No link entry for '" + id + "'.");

        String title = current.title();
        if (fields.title() != null) {
            title = fields.title().isBlank()
                    ? fetchTitle(id, tenantId, projectId)   // clear ⇒ re-derive
                    : fields.title().trim();
        }
        LinkEntry next = new LinkEntry(
                id,
                title,
                patch(current.teaser(), fields.teaser()),
                patch(current.image(), checkedImage(fields.image())),
                patch(current.group(), fields.group()),
                fields.tags() == null ? current.tags() : cleanTags(fields.tags()),
                patch(current.note(), fields.note()),
                current.addedAt(),
                // Editing a link says nothing about whether it was read. The
                // one mutation that touches this is setViewed.
                current.viewedAt());

        boolean groupChanged = !equalGroup(current.group(), next.group());
        List<LinkEntry> entries = new ArrayList<>();
        for (LinkEntry e : loaded.config().entries()) {
            if (e.url().equals(id)) {
                // A group change moves the entry to the end of its new group
                // so the flat list stays group-contiguous — the same shape the
                // generated index and any later reorder round-trip assume.
                if (!groupChanged) entries.add(next);
            } else {
                entries.add(e);
            }
        }
        if (groupChanged) entries = insertGrouped(entries, next);

        store.saveConfig(loaded, withEntries(loaded.config(), entries, next.group()), userId);
        log.info("LinksManifestOps.updateEntry tenant='{}' folder='{}' url='{}'",
                tenantId, folder, id);
    }

    /**
     * Mark an entry seen or put it back on the pile.
     *
     * <p>Its own mutation rather than a field of {@link #updateEntry}, because
     * the two are different acts with different callers: editing is the person
     * curating the list, marking seen is the person working through it — and
     * the second happens with one click, over and over, from a view that has no
     * business being able to change a teaser by accident.
     *
     * <p>Marking an already-seen entry seen again <b>keeps the original
     * timestamp</b>. "When did I read this" is the interesting fact; a second
     * click on a card that already carries the tick is a slip, not a re-read.
     * Putting it back on the pile and marking it again is the way to say
     * otherwise.
     *
     * <p>The entry does not move. A reading order that reshuffled under the
     * click that acknowledged it would lose the reader's place — the view
     * decides where a seen entry is shown, the manifest keeps its order.
     */
    public void setViewed(String tenantId, String projectId, String folder,
                          String url, boolean viewed, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> setViewedLocked(tenantId, projectId, folder, url, viewed, userId));
    }

    private void setViewedLocked(String tenantId, String projectId, String folder,
                                 String url, boolean viewed, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        String id = LinkUrls.identity(url);
        LinkEntry current = find(loaded.config().entries(), id);
        if (current == null) throw new ToolException("No link entry for '" + id + "'.");

        Instant next = viewed ? (current.viewedAt() == null ? Instant.now() : current.viewedAt())
                : null;
        if (Objects.equals(next, current.viewedAt())) {
            // Nothing to write. A click that changes nothing must not cost a
            // document version, and the views are full of repeat clicks.
            return;
        }

        List<LinkEntry> entries = new ArrayList<>();
        for (LinkEntry e : loaded.config().entries()) {
            entries.add(e.url().equals(id)
                    ? new LinkEntry(e.url(), e.title(), e.teaser(), e.image(), e.group(),
                            e.tags(), e.note(), e.addedAt(), next)
                    : e);
        }
        store.saveConfig(loaded, withEntries(loaded.config(), entries, null), userId);
        log.info("LinksManifestOps.setViewed tenant='{}' folder='{}' url='{}' viewed={}",
                tenantId, folder, id, viewed);
    }

    /**
     * Reorder to match {@code orderedUrls}; anything not listed keeps its
     * relative order at the tail. Tolerant by design — the client sends the
     * order it is showing, and a list that changed underneath must not throw
     * away the entries the client did not know about.
     */
    public void reorder(String tenantId, String projectId, String folder,
                        List<String> orderedUrls, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> reorderLocked(tenantId, projectId, folder, orderedUrls, userId));
    }

    private void reorderLocked(String tenantId, String projectId, String folder,
                               List<String> orderedUrls, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        List<LinkEntry> remaining = new ArrayList<>(loaded.config().entries());
        List<LinkEntry> ordered = new ArrayList<>();
        for (String raw : orderedUrls) {
            String id;
            try {
                id = LinkUrls.identity(raw);
            } catch (RuntimeException e) {
                continue;
            }
            LinkEntry match = find(remaining, id);
            if (match != null) {
                ordered.add(match);
                remaining.remove(match);
            }
        }
        ordered.addAll(remaining);
        store.saveConfig(loaded, withEntries(loaded.config(), ordered, null), userId);
    }

    /**
     * Declare the group headings and their order. Groups still used by an
     * entry cannot be dropped this way — {@link LinksConfig#orderedGroups()}
     * would resurrect them at the tail anyway, so keeping them in the
     * declared list is the honest form. Use {@link #renameGroup} to empty a
     * group first.
     */
    public void setGroups(String tenantId, String projectId, String folder,
                          List<String> groups, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> setGroupsLocked(tenantId, projectId, folder, groups, userId));
    }

    private void setGroupsLocked(String tenantId, String projectId, String folder,
                                 List<String> groups, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        SequencedSet<String> next = new LinkedHashSet<>();
        for (String g : groups) {
            String s = blankToNull(g);
            if (s != null) next.add(s);
        }
        for (LinkEntry e : loaded.config().entries()) {
            String g = blankToNull(e.group());
            if (g != null) next.add(g);
        }
        LinksConfig config = new LinksConfig(
                List.copyOf(next), loaded.config().entries(), loaded.config().indexOutputPath());
        store.saveConfig(loaded, config, userId);
    }

    /**
     * Rename a group across the declared list and every entry in it. A blank
     * {@code to} moves the entries into the lead (ungrouped) group and drops
     * the heading.
     */
    public void renameGroup(String tenantId, String projectId, String folder,
                            String from, @Nullable String to, @Nullable String userId) {
        mutateVoid(tenantId, projectId, folder,
                () -> renameGroupLocked(tenantId, projectId, folder, from, to, userId));
    }

    private void renameGroupLocked(String tenantId, String projectId, String folder,
                                   String from, @Nullable String to, @Nullable String userId) {
        String source = blankToNull(from);
        if (source == null) throw new ToolException("from is required");
        String target = blankToNull(to);
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);

        List<LinkEntry> relabelled = new ArrayList<>();
        boolean touched = false;
        for (LinkEntry e : loaded.config().entries()) {
            if (source.equals(e.group())) {
                relabelled.add(new LinkEntry(e.url(), e.title(), e.teaser(), e.image(),
                        target, e.tags(), e.note(), e.addedAt(), e.viewedAt()));
                touched = true;
            } else {
                relabelled.add(e);
            }
        }
        // Relabelling alone can break the group-contiguous invariant of §2.3
        // that every other path upholds actively: merging A into a Y whose
        // block sits further down ([a1(A), x(X), y(Y)], A → Y) leaves
        // [Y, X, Y]. A pure rename is already contiguous, so this is a no-op
        // there.
        List<LinkEntry> entries = regroup(relabelled);
        SequencedSet<String> groups = new LinkedHashSet<>();
        boolean declared = false;
        for (String g : loaded.config().groups()) {
            if (g.equals(source)) {
                declared = true;
                if (target != null) groups.add(target);
            } else {
                groups.add(g);
            }
        }
        if (!touched && !declared) {
            throw new ToolException("No group named '" + source + "'.");
        }
        store.saveConfig(loaded, new LinksConfig(List.copyOf(groups), entries,
                loaded.config().indexOutputPath()), userId);
        log.info("LinksManifestOps.renameGroup tenant='{}' folder='{}' '{}' -> '{}'",
                tenantId, folder, source, target == null ? "(ungrouped)" : target);
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Run one read-modify-write of a single manifest under its stripe. */
    private static <T> T mutate(String tenantId, String projectId, String folder,
                                Supplier<T> body) {
        ReentrantLock lock = lockFor(tenantId, projectId, folder);
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /** {@link #mutate} for a mutation with nothing to report back. */
    private static void mutateVoid(String tenantId, String projectId, String folder,
                                   Runnable body) {
        ReentrantLock lock = lockFor(tenantId, projectId, folder);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock lockFor(String tenantId, String projectId, String folder) {
        String key = LinksStore.normaliseFolder(folder);
        return LOCKS[Math.floorMod(Objects.hash(tenantId, projectId, key), LOCK_STRIPES)];
    }

    /**
     * The title the page calls itself, fetched once. A failed preview is a
     * normal answer here, not an error: the card falls back to the hostname
     * and the reader can type a title. Adding a link must not depend on the
     * link being reachable right now.
     *
     * <p><b>The value is shaped before it is stored.</b> It is the one piece
     * of foreign text this app persists — {@code og:title} of a page nobody
     * here controls, and {@link LinkPreviewService} caps the body it reads but
     * not the meta value it pulls out of it. Everything downstream assumes a
     * card label: the generated index puts it inside a markdown link, and the
     * app-context block puts it in a {@code - key: value} list. A newline
     * breaks both, and a 50-KB title is carried by every later read.
     */
    private @Nullable String fetchTitle(String url, String tenantId, String projectId) {
        try {
            LinkPreviewDto dto = linkPreview.preview(url, tenantId, projectId, null);
            String raw = dto.getTitle();
            String title = UntrustedContent.collapseWhitespace(raw == null ? "" : raw);
            if (title.isEmpty()) return null;
            return title.length() > MAX_TITLE_CHARS
                    ? title.substring(0, MAX_TITLE_CHARS) + "…"
                    : title;
        } catch (RuntimeException e) {
            log.debug("LinksManifestOps: no title for {} ({})", url, e.toString());
            return null;
        }
    }

    /**
     * Put the entry after the last one sharing its group, else at the end.
     *
     * <p>Group-relative rather than list-relative: the app renders the
     * ungrouped entries first and then the groups in the order
     * {@code config.groups} declares them, keeping each section's entry order.
     * Where a group's block sits in the flat entry list therefore does not show
     * anywhere — only the position inside the group does.
     */
    private static List<LinkEntry> insertGrouped(List<LinkEntry> entries, LinkEntry entry) {
        List<LinkEntry> out = new ArrayList<>(entries);
        int insertAt = -1;
        for (int i = 0; i < out.size(); i++) {
            if (equalGroup(out.get(i).group(), entry.group())) insertAt = i + 1;
        }
        if (insertAt < 0) out.add(entry);
        else out.add(insertAt, entry);
        return out;
    }

    /**
     * Rebuild the config with new entries, declaring {@code newGroup} if it
     * is not declared yet — so a group typed into the add form becomes a
     * heading that survives its last entry being removed.
     */
    private static LinksConfig withEntries(LinksConfig config, List<LinkEntry> entries,
                                           @Nullable String newGroup) {
        List<String> groups = config.groups();
        if (newGroup != null && !groups.contains(newGroup)) {
            List<String> next = new ArrayList<>(groups);
            next.add(newGroup);
            groups = next;
        }
        return new LinksConfig(groups, entries, config.indexOutputPath());
    }

    private static @Nullable LinkEntry find(List<LinkEntry> entries, String id) {
        for (LinkEntry e : entries) {
            if (e.url().equals(id)) return e;
        }
        return null;
    }

    /**
     * A stored picture has to survive the same question the link itself
     * answers: is this something a browser may be pointed at? {@code url} runs
     * through {@link LinkUrls#identity} because the client's {@code safeUrl}
     * guard is the second line and not the first — and that reasoning does not
     * stop at the picture. The tool path never offered the field, so this
     * closes the REST path.
     *
     * <p>{@code null} and blank pass through unchanged: the caller's null/blank
     * convention decides what they mean, not this check.
     */
    private static @Nullable String checkedImage(@Nullable String image) {
        if (image == null || image.isBlank()) return image;
        String s = image.trim();
        if (!LinkUrls.isHttp(s)) {
            throw new ToolException("image must be an http(s) URL — got '" + s + "'");
        }
        return s;
    }

    /** {@code null} keeps the current value, blank clears it. */
    private static @Nullable String patch(@Nullable String current, @Nullable String given) {
        if (given == null) return current;
        return given.isBlank() ? null : given.trim();
    }

    /**
     * Rebuild the flat list group-contiguous, keeping the first-appearance
     * order of the groups and the order inside each. A no-op on a list that is
     * already contiguous, which every other mutation keeps it.
     */
    private static List<LinkEntry> regroup(List<LinkEntry> entries) {
        Map<String, List<LinkEntry>> buckets = new LinkedHashMap<>();
        for (LinkEntry e : entries) {
            buckets.computeIfAbsent(groupKey(e.group()), k -> new ArrayList<>()).add(e);
        }
        List<LinkEntry> out = new ArrayList<>(entries.size());
        for (List<LinkEntry> bucket : buckets.values()) {
            out.addAll(bucket);
        }
        return out;
    }

    private static boolean equalGroup(@Nullable String a, @Nullable String b) {
        return groupKey(a).equals(groupKey(b));
    }

    /** Ungrouped is the empty string, whichever way it was written. */
    private static String groupKey(@Nullable String group) {
        return group == null || group.isBlank() ? "" : group;
    }

    private static List<String> cleanTags(List<String> tags) {
        SequencedSet<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            String s = blankToNull(t);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
