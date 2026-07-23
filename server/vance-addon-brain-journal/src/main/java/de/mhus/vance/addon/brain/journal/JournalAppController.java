package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the interactive Journal editor in the Web-UI. Thin
 * adapter over {@link JournalApplication} + {@link JournalService} +
 * {@link JournalFolderReader} + {@link JournalStatsBuilder} — no business
 * logic here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class JournalAppController {

    private final JournalApplication application;
    private final JournalFolderReader folderReader;
    private final JournalService journalService;
    private final JournalStatsBuilder statsBuilder;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/journal/scan")
    public JournalView scan(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        JournalStatsView stats = toStatsView(statsBuilder.build(scanResult, LocalDate.now()));

        List<JournalEntryView> recent = new ArrayList<>();
        for (JournalEntry e : journalService.recent(scanResult, scanResult.config().indexLimit())) {
            recent.add(toEntryView(e));
        }
        String title = firstNonBlank(scanResult.config().title(), scanResult.manifest().getTitle());
        return new JournalView(
                normalised, title, scanResult.config().description(),
                scanResult.config().entriesDir(), scanResult.config().moodPresets(),
                stats, recent);
    }

    @GetMapping("/brain/{tenant}/addon/journal/month")
    public JournalMonthView month(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        return new JournalMonthView(year, month,
                new ArrayList<>(journalService.monthMask(scanResult, year, month)));
    }

    @GetMapping("/brain/{tenant}/addon/journal/entry")
    public JournalEntryContentView getEntry(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("date") String date,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalConfig config = configOf(tenant, projectId, normalised);
        DocumentDocument doc = journalService.findEntry(tenant, projectId, normalised, config, date)
                .orElseThrow(() -> new ToolException("No entry for '" + date + "'"));
        return toContentView(doc);
    }

    @PutMapping("/brain/{tenant}/addon/journal/entry")
    public JournalEntryContentView putEntry(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody JournalCreateEntryRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalConfig config = configOf(tenant, projectId, normalised);
        String date = request != null && request.date() != null && !request.date().isBlank()
                ? request.date().trim()
                : LocalDate.now().toString();
        DocumentDocument stored = journalService.upsertEntry(
                tenant, projectId, normalised, config, date,
                request != null ? request.body() : null,
                request != null ? request.title() : null,
                request != null ? request.mood() : null,
                request != null ? request.tags() : null,
                currentUser(httpRequest));
        log.info("JournalAppController.putEntry tenant='{}' folder='{}' path='{}'",
                tenant, normalised, stored.getPath());
        return toContentView(stored);
    }

    @DeleteMapping("/brain/{tenant}/addon/journal/entry")
    public ResponseEntity<Void> deleteEntry(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("date") String date,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.DELETE);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalConfig config = configOf(tenant, projectId, normalised);
        Optional<DocumentDocument> doc = journalService.findEntry(
                tenant, projectId, normalised, config, date);
        if (doc.isEmpty()) return ResponseEntity.notFound().build();
        documentService.trash(doc.get().getId(), currentUser(httpRequest));
        log.info("JournalAppController.deleteEntry tenant='{}' folder='{}' path='{}'",
                tenant, normalised, doc.get().getPath());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brain/{tenant}/addon/journal/on-this-day")
    public JournalOnThisDayView onThisDay(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("date") String date,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        List<JournalEntryView> entries = new ArrayList<>();
        for (JournalEntry e : journalService.onThisDay(scanResult, date)) entries.add(toEntryView(e));
        return new JournalOnThisDayView(date, entries);
    }

    @PostMapping("/brain/{tenant}/addon/journal/rebuild")
    public JournalRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = application.refresh(rc);

        JournalFolderReader.Scan scanResult = folderReader.scan(tenant, projectId, normalised);
        JournalStatsBuilder.Stats stats = statsBuilder.build(scanResult, LocalDate.now());
        String indexPath = artefactPath(result, "index");
        String statsPath = artefactPath(result, "stats");
        return new JournalRebuildResponse(
                normalised, indexPath, statsPath,
                scanResult.entries().size(), stats.currentStreak(), stats.longestStreak());
    }

    @GetMapping("/brain/{tenant}/addon/journal/search")
    public JournalSearchResponse search(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam(value = "q", required = false) @Nullable String query,
            @RequestParam(value = "mood", required = false) @Nullable String mood,
            @RequestParam(value = "tag", required = false) @Nullable String tag,
            @RequestParam(value = "size", defaultValue = "40") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = JournalFolderReader.normaliseFolder(folder);
        JournalConfig config = configOf(tenant, projectId, normalised);
        DocumentService.DocumentMetaListing listing = journalService.search(
                tenant, projectId, normalised, config, query, mood, tag, size);
        List<JournalHitView> items = new ArrayList<>(listing.items().size());
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            String leaf = m.path().substring(m.path().lastIndexOf('/') + 1);
            items.add(new JournalHitView(
                    m.id(), m.path(), JournalFolderReader.dateFromLeaf(leaf),
                    m.title(), null, m.snippet(), m.score()));
        }
        return new JournalSearchResponse(items, listing.total());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private JournalConfig configOf(String tenant, String projectId, String folder) {
        return folderReader.scan(tenant, projectId, folder).config();
    }

    private JournalEntryView toEntryView(JournalEntry e) {
        return new JournalEntryView(
                e.doc().getId(), e.doc().getPath(), e.date(), e.title(), e.mood(), e.tags());
    }

    private JournalEntryContentView toContentView(DocumentDocument doc) {
        JournalEntryDocument entry = journalService.readEntry(doc);
        String date = !entry.date().isEmpty()
                ? entry.date()
                : JournalFolderReader.dateFromLeaf(doc.getPath().substring(doc.getPath().lastIndexOf('/') + 1));
        return new JournalEntryContentView(
                doc.getId(), doc.getPath(),
                date == null ? "" : date,
                entry.title(), entry.mood(), entry.tags(), entry.body());
    }

    private static JournalStatsView toStatsView(JournalStatsBuilder.Stats s) {
        return new JournalStatsView(
                s.totalEntries(), s.firstEntry(), s.lastEntry(),
                s.currentStreak(), s.longestStreak(),
                s.entriesThisMonth(), s.entriesThisYear(),
                s.moodDistribution(), s.tagHistogram());
    }

    private static @Nullable String artefactPath(VanceApplication.RefreshResult result, String name) {
        return result.artefacts().stream()
                .filter(a -> name.equals(a.name()))
                .map(VanceApplication.ArtefactResult::path)
                .findFirst().orElse(null);
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
