package de.mhus.vance.addon.brain.slideshow;

import de.mhus.vance.brain.applications.VanceApplication;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the Slideshow viewer. Read-only adapter — the
 * planner is intentionally minimal: no inline mutation of the slide
 * order from the web (the manifest is small enough to edit in the
 * generic document viewer).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SlideshowController {

    private final SlideshowApplication slideshowApplication;
    private final SlideshowFolderReader folderReader;
    private final DocumentLinkBuilder linkBuilder;
    private final RequestAuthority authority;

    @GetMapping("/brain/{tenant}/addon/slideshow/show")
    public SlideshowView getShow(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String normalised = normaliseFolder(folder);
        SlideshowFolderReader.Scan scan = folderReader.scan(tenant, projectId, normalised);

        List<SlideView> slides = new ArrayList<>(scan.slides().size());
        for (SlideshowFolderReader.Slide s : scan.slides()) {
            slides.add(SlideView.builder()
                    .documentId(s.doc().getId())
                    .path(s.doc().getPath())
                    .relativePath(s.relativePath())
                    .caption(s.caption())
                    .width(s.width())
                    .height(s.height())
                    .sizeBytes(s.sizeBytes())
                    .mimeType(s.mimeType())
                    .build());
        }

        return SlideshowView.builder()
                .folder(scan.folder())
                .manifestPath(scan.manifestDoc() != null ? scan.manifestDoc().getPath() : null)
                .title(scan.manifest().title())
                .description(scan.manifest().description())
                .autoplaySeconds(scan.slideshowConfig().autoplaySeconds())
                .aspectRatio(scan.slideshowConfig().aspectRatio())
                .slides(slides)
                .build();
    }

    @PostMapping("/brain/{tenant}/addon/slideshow/rebuild")
    public SlideshowRebuildResponse rebuild(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("folder") String folder,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String normalised = normaliseFolder(folder);
        VanceApplication.RefreshContext rc = new VanceApplication.RefreshContext(
                tenant, projectId, normalised, currentUser(httpRequest), null);
        VanceApplication.RefreshResult result = slideshowApplication.refresh(rc);

        VanceApplication.ArtefactResult art = result.artefacts().isEmpty()
                ? null : result.artefacts().get(0);
        int slideCount = 0;
        if (art != null && art.stats().get("slideCount") instanceof Number n) {
            slideCount = n.intValue();
        }

        return SlideshowRebuildResponse.builder()
                .folder(normalised)
                .indexPath(art != null ? art.path() : null)
                .indexMarkdownLink(art != null ? art.markdownLink() : null)
                .slideCount(slideCount)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(@Nullable String folder) {
        if (folder == null || folder.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must be provided");
        }
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "folder must not be empty");
        }
        return f;
    }

    private static @Nullable String currentUser(HttpServletRequest httpRequest) {
        Object v = httpRequest.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return v instanceof String s ? s : null;
    }
}
