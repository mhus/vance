package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Scans a journal folder for {@code kind: journal-entry} pages under the
 * configured {@code entries/} sub-folder plus the {@code _app.yaml}
 * manifest. System-managed files (underscore-prefixed leaves such as
 * {@code _index.md} / {@code _stats.yaml}) are excluded — they're
 * generated outputs, not sources.
 *
 * <p>Entry fields are read from the document's mirrored {@code headers}
 * + native title/tags, so a scan never loads the (compressed) blob body.
 */
@Component
public class JournalFolderReader {

    public static final String APP_MANIFEST = "_app.yaml";
    public static final String PAGE_EXTENSION = ".md";

    /** {@code YYYY-MM-DD} at the start of a filename stem. */
    private static final Pattern DATE_STEM = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DocumentService documentService;

    public JournalFolderReader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Result of scanning a journal folder.
     *
     * @param folder   normalised journal root
     * @param manifest the {@code _app.yaml} document
     * @param config   parsed {@code journal:} config
     * @param entries  content entries (generated files excluded), newest date first
     */
    public record Scan(
            String folder,
            DocumentDocument manifest,
            JournalConfig config,
            List<JournalEntry> entries) {}

    public Scan scan(String tenantId, String projectId, String folder) {
        String normalized = normaliseFolder(folder);
        String manifestPath = normalized + "/" + APP_MANIFEST;
        Optional<DocumentDocument> manifest = documentService.findByPath(
                tenantId, projectId, manifestPath);
        if (manifest.isEmpty()) {
            throw new ToolException("No journal manifest at '" + manifestPath + "'.");
        }
        JournalConfig config = parseConfig(manifest.get());

        String prefix = normalized + "/" + config.entriesDir() + "/";
        List<DocumentDocument> all = documentService.listByKind(
                tenantId, projectId, JournalEntryDocument.KIND);
        List<JournalEntry> entries = new ArrayList<>();
        for (DocumentDocument doc : all) {
            String path = doc.getPath();
            if (!path.startsWith(prefix)) continue;
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.startsWith("_")) continue;

            String date = headerValue(doc, "date");
            if (date == null || date.isBlank()) date = dateFromLeaf(leaf);
            if (date == null) continue; // not a dated entry — skip defensively

            String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle()
                    : humaniseDate(date);
            String mood = headerValue(doc, "mood");
            List<String> tags = doc.getTags() != null ? doc.getTags() : List.of();
            entries.add(new JournalEntry(doc, date, title, mood, new ArrayList<>(tags)));
        }

        // Newest day first; stable on title for same-day (should not happen in v1).
        entries.sort(Comparator
                .comparing(JournalEntry::date, Comparator.reverseOrder())
                .thenComparing(e -> e.title().toLowerCase(Locale.ROOT)));

        return new Scan(normalized, manifest.get(), config, entries);
    }

    private static @Nullable String headerValue(DocumentDocument doc, String key) {
        if (doc.getHeaders() == null) return null;
        String v = doc.getHeaders().get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Extract the {@code YYYY-MM-DD} prefix from a filename leaf, or {@code null}. */
    public static @Nullable String dateFromLeaf(String leaf) {
        var m = DATE_STEM.matcher(leaf);
        if (!m.find()) return null;
        String iso = m.group(1);
        try {
            LocalDate.parse(iso, ISO);
            return iso;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Long-format a valid ISO date for display; echoes the input when unparseable. */
    public static String humaniseDate(String isoDate) {
        try {
            LocalDate d = LocalDate.parse(isoDate, ISO);
            String month = d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            return month + " " + d.getDayOfMonth() + ", " + d.getYear();
        } catch (RuntimeException e) {
            return isoDate;
        }
    }

    private JournalConfig parseConfig(DocumentDocument manifest) {
        try (InputStream in = documentService.loadContent(manifest)) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JournalConfig.parse(body);
        } catch (IOException | RuntimeException e) {
            throw new ToolException(
                    "Could not parse journal manifest '" + manifest.getPath() + "': "
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
}
