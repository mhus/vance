package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Journal domain logic — entry paths, upsert, date-range / recent /
 * on-this-day queries, the calendar month mask and the shared metadata
 * search. All persistence goes through {@link DocumentService} (data
 * sovereignty — the journal owns no MongoDB collection of its own).
 */
@Service
@Slf4j
public class JournalService {

    private static final String MD_MIME = "text/markdown";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DocumentService documentService;
    private final JournalFolderReader folderReader;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public JournalService(DocumentService documentService, JournalFolderReader folderReader,
                          de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.folderReader = folderReader;
        this.contextFactory = contextFactory;
    }

    public JournalFolderReader.Scan scan(String tenantId, String projectId, String folder) {
        return folderReader.scan(tenantId, projectId, folder);
    }

    // ── Paths ─────────────────────────────────────────────────────

    /** {@code <folder>/<entriesDir>/<YYYY>/<YYYY-MM-DD>.md} for a valid ISO date. */
    public static String entryPath(String folder, JournalConfig config, String isoDate) {
        LocalDate d = parseOrThrow(isoDate);
        String normalized = JournalFolderReader.normaliseFolder(folder);
        return normalized + "/" + config.entriesDir() + "/" + d.getYear()
                + "/" + d.format(ISO) + JournalFolderReader.PAGE_EXTENSION;
    }

    // ── Read ──────────────────────────────────────────────────────

    /** Load + decode a single entry document into its typed model. */
    public JournalEntryDocument readEntry(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JournalEntryCodec.parse(body, doc.getMimeType());
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not read journal entry '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    public Optional<DocumentDocument> findEntry(
            String tenantId, String projectId, String folder, JournalConfig config, String isoDate) {
        return documentService.findByPath(tenantId, projectId, entryPath(folder, config, isoDate));
    }

    // ── Upsert ────────────────────────────────────────────────────

    /**
     * Create or update the entry for {@code isoDate}. Each field argument
     * is independently optional: {@code null} leaves an existing value
     * untouched (or omits it on a fresh entry). The body markdown is the
     * block-editor's body-only output; the codec re-attaches the
     * front-matter server-side so the two levels never race.
     *
     * @return the stored document
     */
    public DocumentDocument upsertEntry(
            String tenantId, String projectId, String folder, JournalConfig config,
            String isoDate,
            @Nullable String body,
            @Nullable String title,
            @Nullable String mood,
            @Nullable List<String> tags,
            @Nullable String userId) {

        String date = parseOrThrow(isoDate).format(ISO);
        String path = entryPath(folder, config, date);
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);

        JournalEntryDocument base = existing.isPresent()
                ? readEntry(existing.get())
                : JournalEntryDocument.empty();

        String effectiveTitle = title != null && !title.isBlank()
                ? title.trim()
                : (!base.title().isEmpty() ? base.title() : JournalFolderReader.humaniseDate(date));
        String effectiveMood = mood != null ? nullIfBlank(mood) : base.mood();
        List<String> effectiveTags = tags != null ? cleanTags(tags) : base.tags();
        String effectiveBody = body != null ? body : base.body();

        JournalEntryDocument entry = new JournalEntryDocument(
                JournalEntryDocument.KIND, date, effectiveTitle,
                effectiveMood, effectiveTags, effectiveBody, base.extra());
        String serialized = JournalEntryCodec.serialize(entry, MD_MIME);

        // Native tags mirror the front-matter tags so the shared metadata
        // search can require them via the indexed `tags` array.
        List<String> nativeTags = new ArrayList<>();
        nativeTags.add("journal");
        nativeTags.addAll(effectiveTags);

        if (existing.isPresent()) {
            DocumentDocument updated = documentService.update(
                    existing.get().getId(), effectiveTitle, nativeTags,
                    serialized, null, null, null, null, MD_MIME,
                    DocumentService.TOOL_IDENTITY,
                    contextFactory.writeActor(tenantId, userId, path));
            log.info("JournalService.upsertEntry(update) tenant='{}' path='{}'", tenantId, path);
            return updated;
        }
        try (InputStream in = new ByteArrayInputStream(serialized.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, path, effectiveTitle,
                    nativeTags, MD_MIME, in, userId,
                    contextFactory.writeActor(tenantId, userId, path));
            log.info("JournalService.upsertEntry(create) tenant='{}' path='{}'", tenantId, path);
            return stored;
        } catch (IOException e) {
            throw new ToolException("Could not write journal entry '" + path + "': " + e.getMessage());
        }
    }

    // ── Queries over a scan ───────────────────────────────────────

    /** Entries whose date is within {@code [from, to]} inclusive (ISO strings). */
    public List<JournalEntry> listRange(JournalFolderReader.Scan scan,
                                        @Nullable String from, @Nullable String to) {
        List<JournalEntry> out = new ArrayList<>();
        for (JournalEntry e : scan.entries()) {
            if (from != null && e.date().compareTo(from) < 0) continue;
            if (to != null && e.date().compareTo(to) > 0) continue;
            out.add(e);
        }
        return out;
    }

    /** The {@code limit} most recent entries (scan is already newest-first). */
    public List<JournalEntry> recent(JournalFolderReader.Scan scan, int limit) {
        int safe = Math.max(1, limit);
        List<JournalEntry> all = scan.entries();
        return all.size() > safe ? new ArrayList<>(all.subList(0, safe)) : new ArrayList<>(all);
    }

    /**
     * Entries sharing the month + day of {@code isoDate} but from an
     * earlier year — the "on this day" retrospective. Most-recent year first.
     */
    public List<JournalEntry> onThisDay(JournalFolderReader.Scan scan, String isoDate) {
        LocalDate ref = parseOrThrow(isoDate);
        List<JournalEntry> out = new ArrayList<>();
        for (JournalEntry e : scan.entries()) {
            LocalDate d = parse(e.date());
            if (d == null) continue;
            if (d.getMonthValue() == ref.getMonthValue()
                    && d.getDayOfMonth() == ref.getDayOfMonth()
                    && d.getYear() != ref.getYear()) {
                out.add(e);
            }
        }
        return out; // scan order = newest first
    }

    /** Day-of-month numbers that carry an entry in the given year + month. */
    public TreeSet<Integer> monthMask(JournalFolderReader.Scan scan, int year, int month) {
        TreeSet<Integer> days = new TreeSet<>();
        for (JournalEntry e : scan.entries()) {
            LocalDate d = parse(e.date());
            if (d != null && d.getYear() == year && d.getMonthValue() == month) {
                days.add(d.getDayOfMonth());
            }
        }
        return days;
    }

    // ── Search (shared metadata + summary path) ───────────────────

    /**
     * Free-text search over entry title + summary + tags, scoped to the
     * journal's {@code entries/} sub-tree, with optional mood + tag facets.
     * Delegates to the shared {@link DocumentService#searchProjectDocumentsMeta}
     * — the journal invents no index of its own.
     */
    public DocumentService.DocumentMetaListing search(
            String tenantId, String projectId, String folder, JournalConfig config,
            @Nullable String query, @Nullable String mood, @Nullable String tag, int limit) {
        String prefix = JournalFolderReader.normaliseFolder(folder) + "/" + config.entriesDir() + "/";
        Map<String, String> headerEquals = new LinkedHashMap<>();
        if (mood != null && !mood.isBlank()) headerEquals.put("mood", mood.trim());
        List<String> requireTags = tag != null && !tag.isBlank() ? List.of(tag.trim()) : List.of();
        return documentService.searchProjectDocumentsMeta(
                tenantId, projectId, prefix, query, requireTags, headerEquals, limit);
    }

    // ── Helpers ───────────────────────────────────────────────────

    public Optional<DocumentDocument> findByPath(String tenantId, String projectId, String path) {
        return documentService.findByPath(tenantId, projectId, path);
    }

    private static List<String> cleanTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String t : tags) {
            if (t != null && !t.isBlank() && !out.contains(t.trim())) out.add(t.trim());
        }
        return out;
    }

    private static @Nullable String nullIfBlank(@Nullable String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static LocalDate parseOrThrow(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            throw new ToolException("date is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(isoDate.trim(), ISO);
        } catch (RuntimeException e) {
            throw new ToolException("Invalid date '" + isoDate + "' — expected yyyy-MM-dd");
        }
    }

    private static @Nullable LocalDate parse(String iso) {
        try {
            return LocalDate.parse(iso, ISO);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
