package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.web.LinkPreviewDto;
import de.mhus.vance.brain.tools.web.LinkPreviewService;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
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
 */
@Component
@Slf4j
public class LinksManifestOps {

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
     * Add a link. Idempotent on the URL: adding one that is already in the
     * list changes nothing (and says so in the log) rather than producing a
     * second card for the same page.
     *
     * <p>When no title is given, one is fetched once from the link-preview
     * proxy and stored. That single fetch is the whole reason the list stays
     * readable later — see {@link LinkEntry}.
     *
     * @return true when an entry was added, false when it was already there.
     */
    public boolean addEntry(String tenantId, String projectId, String folder,
                            String url, LinkFields fields, @Nullable String userId) {
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);
        String id = LinkUrls.identity(url);
        if (find(loaded.config().entries(), id) != null) {
            log.debug("LinksManifestOps.addEntry folder='{}' url='{}' already present",
                    folder, id);
            return false;
        }
        String group = blankToNull(fields.group());
        String title = blankToNull(fields.title());
        if (title == null) {
            title = fetchTitle(id, tenantId, projectId);
        }
        LinkEntry entry = new LinkEntry(id, title,
                blankToNull(fields.teaser()), blankToNull(fields.image()), group,
                fields.tags() == null ? List.of() : cleanTags(fields.tags()),
                blankToNull(fields.note()), Instant.now());

        List<LinkEntry> entries = insertGrouped(loaded.config().entries(), entry);
        store.saveConfig(loaded, withEntries(loaded.config(), entries, group), userId);
        log.info("LinksManifestOps.addEntry tenant='{}' folder='{}' url='{}' group='{}'",
                tenantId, folder, id, group == null ? "" : group);
        return true;
    }

    /** Remove the entry with this URL. Unknown URL is an error, not a no-op. */
    public void removeEntry(String tenantId, String projectId, String folder,
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
                patch(current.image(), fields.image()),
                patch(current.group(), fields.group()),
                fields.tags() == null ? current.tags() : cleanTags(fields.tags()),
                patch(current.note(), fields.note()),
                current.addedAt());

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
     * Reorder to match {@code orderedUrls}; anything not listed keeps its
     * relative order at the tail. Tolerant by design — the client sends the
     * order it is showing, and a list that changed underneath must not throw
     * away the entries the client did not know about.
     */
    public void reorder(String tenantId, String projectId, String folder,
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
        String source = blankToNull(from);
        if (source == null) throw new ToolException("from is required");
        String target = blankToNull(to);
        LinksStore.Loaded loaded = store.load(tenantId, projectId, folder);

        List<LinkEntry> entries = new ArrayList<>();
        boolean touched = false;
        for (LinkEntry e : loaded.config().entries()) {
            if (source.equals(e.group())) {
                entries.add(new LinkEntry(e.url(), e.title(), e.teaser(), e.image(),
                        target, e.tags(), e.note(), e.addedAt()));
                touched = true;
            } else {
                entries.add(e);
            }
        }
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

    /**
     * The title the page calls itself, fetched once. A failed preview is a
     * normal answer here, not an error: the card falls back to the hostname
     * and the reader can type a title. Adding a link must not depend on the
     * link being reachable right now.
     */
    private @Nullable String fetchTitle(String url, String tenantId, String projectId) {
        try {
            LinkPreviewDto dto = linkPreview.preview(url, tenantId, projectId, null);
            String title = dto.getTitle();
            return title == null || title.isBlank() ? null : title.trim();
        } catch (RuntimeException e) {
            log.debug("LinksManifestOps: no title for {} ({})", url, e.toString());
            return null;
        }
    }

    /** Put the entry after the last one sharing its group, else at the end. */
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

    /** {@code null} keeps the current value, blank clears it. */
    private static @Nullable String patch(@Nullable String current, @Nullable String given) {
        if (given == null) return current;
        return given.isBlank() ? null : given.trim();
    }

    private static boolean equalGroup(@Nullable String a, @Nullable String b) {
        String x = a == null || a.isBlank() ? "" : a;
        String y = b == null || b.isBlank() ? "" : b;
        return x.equals(y);
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
