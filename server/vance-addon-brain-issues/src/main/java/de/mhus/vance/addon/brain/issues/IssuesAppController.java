package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentNote;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * REST endpoints for the interactive Issues editor. Thin adapter over
 * {@link IssuesApplication} + {@link IssuesService} + {@link IssuesFolderReader}
 * + {@link IssuesStatsBuilder} — no business logic here.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class IssuesAppController {

    private final IssuesApplication application;
    private final IssuesFolderReader folderReader;
    private final IssuesService issuesService;
    private final IssuesStatsBuilder statsBuilder;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/issues/scan")
    public IssuesView scan(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam(value = "state", required = false) @Nullable String state,
            @RequestParam(value = "archived", defaultValue = "false") boolean archived,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = IssuesFolderReader.normaliseFolder(folder);
        IssuesFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);

        List<Issue> source = archived
                ? folderReader.scanArchived(tenant, projectId, normalised, scan.config())
                : scan.issues();
        List<IssueView> issues = new ArrayList<>();
        for (Issue i : source) {
            if (!archived && state != null && !state.isBlank() && !state.equalsIgnoreCase(i.state())) continue;
            issues.add(toView(i));
        }
        IssueStatsView stats = toStatsView(statsBuilder.build(scan));
        String title = firstNonBlank(scan.config().title(), scan.manifest().getTitle());
        return new IssuesView(normalised, title, scan.config().description(),
                scan.config().suggestedLabels(), issues, stats, archived);
    }

    @GetMapping("/brain/{tenant}/addon/issues/issue")
    public IssueContentView getIssue(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        return toContentView(requireDoc(tenant, projectId, path));
    }

    @PostMapping("/brain/{tenant}/addon/issues/issue")
    public IssueContentView createIssue(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestBody IssueCreateRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.CREATE);
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ToolException("title is required");
        }
        DocumentDocument doc = issuesService.createIssue(tenant, projectId,
                IssuesFolderReader.normaliseFolder(folder), request.title(),
                request.labels(), request.assignee(), request.priority(), request.body(),
                currentUser(httpRequest));
        return toContentView(doc);
    }

    @PatchMapping("/brain/{tenant}/addon/issues/issue")
    public IssueContentView patchIssue(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            @RequestBody IssuePatchRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        DocumentDocument doc = issuesService.updateIssue(tenant, projectId, path,
                request != null ? request.state() : null,
                request != null ? request.labels() : null,
                request != null ? request.assignee() : null,
                request != null ? request.priority() : null,
                request != null ? request.title() : null,
                request != null ? request.body() : null);
        return toContentView(doc);
    }

    @PostMapping("/brain/{tenant}/addon/issues/issue/archive")
    public IssueContentView archive(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = IssuesFolderReader.normaliseFolder(folder);
        IssuesConfig config = folderReader.scan(tenant, projectId, normalised).config();
        return toContentView(issuesService.archive(tenant, projectId, normalised, config, path));
    }

    @PostMapping("/brain/{tenant}/addon/issues/issue/unarchive")
    public IssueContentView unarchive(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = IssuesFolderReader.normaliseFolder(folder);
        IssuesConfig config = folderReader.scan(tenant, projectId, normalised).config();
        return toContentView(issuesService.unarchive(tenant, projectId, normalised, config, path));
    }

    @PostMapping("/brain/{tenant}/addon/issues/issue/comment")
    public IssueContentView addComment(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            @RequestBody IssueCommentRequest request,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ToolException("comment text is required");
        }
        issuesService.addComment(tenant, projectId, path, request.text(), currentUser(httpRequest));
        return toContentView(requireDoc(tenant, projectId, path));
    }

    @DeleteMapping("/brain/{tenant}/addon/issues/issue/comment")
    public IssueContentView deleteComment(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            @RequestParam("commentId") String commentId,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        issuesService.deleteComment(tenant, projectId, path, commentId);
        return toContentView(requireDoc(tenant, projectId, path));
    }

    @DeleteMapping("/brain/{tenant}/addon/issues/issue")
    public ResponseEntity<Void> deleteIssue(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.DELETE);
        issuesService.trash(tenant, projectId, path, currentUser(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/brain/{tenant}/addon/issues/search")
    public IssuesSearchResponse search(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            @RequestParam(value = "q", required = false) @Nullable String query,
            @RequestParam(value = "label", required = false) @Nullable String label,
            @RequestParam(value = "size", defaultValue = "40") int size,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        DocumentService.DocumentMetaListing listing = issuesService.search(
                tenant, projectId, IssuesFolderReader.normaliseFolder(folder), query, label, size);
        List<IssueHitView> items = new ArrayList<>(listing.items().size());
        for (DocumentService.DocumentMetaMatch m : listing.items()) {
            items.add(new IssueHitView(m.id(), m.path(), m.title(), m.snippet(), m.score()));
        }
        return new IssuesSearchResponse(items, listing.total());
    }

    @PostMapping("/brain/{tenant}/addon/issues/rebuild")
    public IssuesRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = IssuesFolderReader.normaliseFolder(folder);
        VanceApplication.RefreshResult result = application.refresh(new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null));
        IssuesStatsBuilder.Stats stats = statsBuilder.build(folderReader.scan(tenant, projectId, normalised));
        return new IssuesRebuildResponse(normalised,
                artefactPath(result, "index"), artefactPath(result, "stats"),
                stats.open(), stats.closed());
    }

    // ── Helpers ───────────────────────────────────────────────────

    private DocumentDocument requireDoc(String tenant, String projectId, String path) {
        return documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ToolException("No issue at '" + path + "'"));
    }

    private IssueView toView(Issue i) {
        return new IssueView(i.doc().getId(), i.doc().getPath(), i.number(), i.title(),
                i.state(), i.labels(), i.assignee(), i.priority(), i.archived());
    }

    private IssueContentView toContentView(DocumentDocument doc) {
        IssueDocument issue = issuesService.readIssue(doc);
        boolean archived = doc.getPath().contains("/archive/");
        List<IssueCommentView> comments = new ArrayList<>();
        for (DocumentNote n : issuesService.listComments(doc)) {
            comments.add(new IssueCommentView(n.getId(), n.getText(), n.getUserId(),
                    n.getCreatedAt() != null ? n.getCreatedAt().toString() : null,
                    n.getUpdatedAt() != null ? n.getUpdatedAt().toString() : null));
        }
        return new IssueContentView(doc.getId(), doc.getPath(), issue.number(), issue.title(),
                issue.state(), issue.labels(), issue.assignee(), issue.priority(), archived,
                issue.body(), comments);
    }

    private static IssueStatsView toStatsView(IssuesStatsBuilder.Stats s) {
        return new IssueStatsView(s.open(), s.closed(), s.total(), s.byLabel(), s.byAssignee());
    }

    private static @Nullable String artefactPath(VanceApplication.RefreshResult result, String name) {
        return result.artefacts().stream().filter(a -> name.equals(a.name()))
                .map(VanceApplication.ArtefactResult::path).findFirst().orElse(null);
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
