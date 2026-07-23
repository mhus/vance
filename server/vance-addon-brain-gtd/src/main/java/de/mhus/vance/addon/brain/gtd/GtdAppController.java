package de.mhus.vance.addon.brain.gtd;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the interactive GTD editor. Thin adapter over
 * {@link GtdApplication} + {@link GtdService} + {@link GtdFolderReader} +
 * {@link GtdStatsBuilder} — no business logic here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GtdAppController {

    private final GtdApplication application;
    private final GtdFolderReader folderReader;
    private final GtdService gtdService;
    private final GtdStatsBuilder statsBuilder;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/gtd/scan")
    public GtdView scan(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = GtdFolderReader.normaliseFolder(folder);
        GtdFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        LocalDate today = LocalDate.now();

        Map<GtdBucket, List<GtdAction>> grouped = gtdService.computeBuckets(scan, today);
        List<GtdBucketView> buckets = new ArrayList<>();
        for (GtdBucket b : statsBuilder.orderedBuckets()) {
            List<GtdActionView> views = new ArrayList<>();
            for (GtdAction a : grouped.get(b)) views.add(toActionView(a, b, today));
            buckets.add(new GtdBucketView(b.wireName(), bucketTitle(b), views));
        }

        GtdStatsBuilder.Stats stats = statsBuilder.build(scan, today);
        List<GtdProjectView> projects = new ArrayList<>();
        for (Map.Entry<String, Integer> e : stats.projectCounts().entrySet()) {
            projects.add(new GtdProjectView(e.getKey(), e.getValue()));
        }
        Set<String> contexts = new LinkedHashSet<>(scan.config().suggestedContexts());
        contexts.addAll(stats.contextCounts().keySet());

        String title = firstNonBlank(scan.config().title(), scan.manifest().getTitle());
        return new GtdView(normalised, title, scan.config().description(),
                new ArrayList<>(contexts), buckets, projects, toStatsView(stats));
    }

    @PostMapping("/brain/{tenant}/addon/gtd/capture")
    public GtdActionContentView capture(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody GtdCaptureRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ToolException("title is required");
        }
        String normalised = GtdFolderReader.normaliseFolder(folder);
        GtdConfig config = configOf(tenant, projectId, normalised);
        DocumentDocument doc = gtdService.capture(tenant, projectId, normalised, config,
                request.title(), request.note(), currentUser(httpRequest));
        log.info("GtdAppController.capture tenant='{}' path='{}'", tenant, doc.getPath());
        return toContentView(doc);
    }

    @GetMapping("/brain/{tenant}/addon/gtd/action")
    public GtdActionContentView getAction(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No action at '" + path + "'"));
        return toContentView(doc);
    }

    @PostMapping("/brain/{tenant}/addon/gtd/action")
    public GtdActionContentView createAction(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody GtdActionRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ToolException("title is required");
        }
        String normalised = GtdFolderReader.normaliseFolder(folder);
        GtdConfig config = configOf(tenant, projectId, normalised);
        DocumentDocument doc = gtdService.createAction(tenant, projectId, normalised, config,
                request.title(), request.when(), request.deadline(), request.contexts(),
                request.project(), request.body(), currentUser(httpRequest));
        return toContentView(doc);
    }

    @PatchMapping("/brain/{tenant}/addon/gtd/action")
    public GtdActionContentView patchAction(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            @RequestBody GtdActionRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = gtdService.updateAction(tenant, projectId, path,
                request != null ? request.when() : null,
                request != null ? request.deadline() : null,
                request != null ? request.contexts() : null,
                request != null ? request.done() : null,
                request != null ? request.title() : null,
                request != null ? request.body() : null);
        return toContentView(doc);
    }

    @PostMapping("/brain/{tenant}/addon/gtd/move")
    public GtdActionContentView move(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            @RequestBody GtdMoveRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        GtdBucket bucket = GtdBucket.fromWire(request != null ? request.bucket() : null);
        if (bucket == null) throw new ToolException("Unknown bucket '" + (request != null ? request.bucket() : null) + "'");
        String normalised = GtdFolderReader.normaliseFolder(folder);
        GtdConfig config = configOf(tenant, projectId, normalised);
        DocumentDocument doc = gtdService.move(tenant, projectId, normalised, config, path,
                bucket, request.date(), currentUser(httpRequest));
        return toContentView(doc);
    }

    @DeleteMapping("/brain/{tenant}/addon/gtd/action")
    public ResponseEntity<Void> deleteAction(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.DELETE);
        gtdService.trash(tenant, projectId, path, currentUser(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brain/{tenant}/addon/gtd/search")
    public GtdSearchResponse search(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam(value = "q", required = false) @Nullable String query,
            @RequestParam(value = "context", required = false) @Nullable String context,
            @RequestParam(value = "size", defaultValue = "40") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = GtdFolderReader.normaliseFolder(folder);
        DocumentService.DocumentMetaListing listing = gtdService.search(
                tenant, projectId, normalised, query, context, size);
        List<GtdHitView> items = new ArrayList<>(listing.items().size());
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            items.add(new GtdHitView(m.id(), m.path(), m.title(), m.snippet(), m.score()));
        }
        return new GtdSearchResponse(items, listing.total());
    }

    @PostMapping("/brain/{tenant}/addon/gtd/rebuild")
    public GtdRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = GtdFolderReader.normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = application.refresh(rc);

        GtdFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);
        GtdStatsBuilder.Stats stats = statsBuilder.build(scan, LocalDate.now());
        return new GtdRebuildResponse(normalised,
                artefactPath(result, "today"), artefactPath(result, "upcoming"),
                artefactPath(result, "stats"),
                stats.totalOpen(), stats.bucketCounts().getOrDefault("inbox", 0));
    }

    // ── Helpers ───────────────────────────────────────────────────

    private GtdConfig configOf(String tenant, String projectId, String folder) {
        return folderReader.scan(tenant, projectId, folder).config();
    }

    private GtdActionView toActionView(GtdAction a, GtdBucket bucket, LocalDate today) {
        boolean overdue = !a.inInbox()
                && gtdService.bucketResolver().isOverdue(a.when(), a.deadline(), today);
        return new GtdActionView(a.doc().getId(), a.doc().getPath(), a.title(), a.when(),
                a.deadline(), a.contexts(), a.done(), bucket.wireName(), a.project(), overdue);
    }

    private GtdActionContentView toContentView(DocumentDocument doc) {
        GtdActionDocument action = gtdService.readAction(doc);
        String project = projectOf(doc.getPath());
        return new GtdActionContentView(doc.getId(), doc.getPath(), action.title(),
                action.when(), action.deadline(), action.contexts(), action.done(),
                project, action.body());
    }

    private static @Nullable String projectOf(String path) {
        int idx = path.indexOf("/projects/");
        if (idx < 0) return null;
        String after = path.substring(idx + "/projects/".length());
        int slash = after.indexOf('/');
        return slash > 0 ? after.substring(0, slash) : null;
    }

    private static GtdStatsView toStatsView(GtdStatsBuilder.Stats s) {
        return new GtdStatsView(s.totalOpen(), s.done(), s.overdue(),
                s.bucketCounts(), s.contextCounts(), s.projectCounts());
    }

    private static String bucketTitle(GtdBucket b) {
        return switch (b) {
            case INBOX -> "Inbox";
            case TODAY -> "Today";
            case UPCOMING -> "Upcoming";
            case ANYTIME -> "Anytime";
            case SOMEDAY -> "Someday";
        };
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
