package de.mhus.vance.brain.applications;

import de.mhus.vance.api.applications.ApplicationEntryDto;
import de.mhus.vance.api.applications.ApplicationListResponse;
import de.mhus.vance.api.applications.ApplicationTargetDto;
import de.mhus.vance.api.applications.ApplicationTargetsResponse;
import de.mhus.vance.brain.applications.VanceApplication.AppTarget;
import de.mhus.vance.brain.applications.VanceApplication.DescribeContext;
import de.mhus.vance.brain.applications.VanceApplication.TargetPurpose;
import de.mhus.vance.brain.applications.VanceApplication.TargetsContext;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.ApplicationCodec;
import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.starred.StarredItem;
import de.mhus.vance.shared.starred.StarredService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Which apps can I link to, and what places do they have."
 *
 * <p>Read-only and generic — the consumers are link pickers, and there is one
 * per editor family. Lives in brain rather than in an addon because
 * {@link VanceApplication} does: an addon-hosted route would make every picker
 * depend on whichever addon happened to own it, which is exactly the mistake
 * four addons made with their own {@code documents/search}.
 *
 * <p>Two routes, on purpose. Listing an app is cheap (a manifest read); listing
 * its <em>places</em> costs a folder scan. Merging them would scan every app in
 * the project to draw a list that is shown before anyone has chosen anything.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ApplicationsController {

    /** Kind discriminator of an app manifest, as {@code DocumentService} indexes it. */
    private static final String APPLICATION_KIND = "application";
    private static final String MANIFEST_SUFFIX = "/" + VanceApplication.APP_MANIFEST;

    private final DocumentService documentService;
    private final StarredService starredService;
    private final VanceApplicationRegistry registry;
    private final RequestAuthority authority;
    private final PermissionService permissionService;

    /**
     * The apps a link can point at: the caller's starred ones (across projects)
     * and every app in {@code projectId}.
     *
     * <p>Favourites are a shortcut, not a filter — unlike the Milliways share
     * dialog, where the starred list <em>is</em> the whole choice because
     * sending something somewhere is a deliberate act. Linking is not: the app
     * you are linking to is usually the one you are working in, and that is
     * rarely starred.
     */
    @GetMapping("/brain/{tenant}/applications")
    public ApplicationListResponse list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        SecurityContext ctx = authority.contextOf(httpRequest);

        List<ApplicationEntryDto> starred = starredApps(tenant, ctx);
        Set<String> seen = new HashSet<>();
        for (ApplicationEntryDto e : starred) seen.add(key(e.project(), e.path()));

        // Everything in this project, minus what is already up in the favourites:
        // one row per app, and the shortcut wins because it is the shorter list.
        List<ApplicationEntryDto> project = new ArrayList<>();
        for (DocumentDocument doc : documentService.listByKind(tenant, projectId, APPLICATION_KIND)) {
            String path = doc.getPath();
            if (path == null || !path.endsWith(MANIFEST_SUFFIX)) continue;
            if (seen.contains(key(projectId, path))) continue;
            ApplicationEntryDto entry = entryOf(tenant, projectId, doc);
            if (entry != null) project.add(entry);
        }
        project.sort(Comparator.comparing(ApplicationEntryDto::path));

        log.debug("applications list tenant='{}' project='{}' → {} starred, {} in project",
                tenant, projectId, starred.size(), project.size());
        return new ApplicationListResponse(List.copyOf(starred), List.copyOf(project));
    }

    /**
     * The places one app instance offers, for the second step of a picker.
     *
     * <p>{@code projectId} is the <em>app's</em> project, which is not
     * necessarily the caller's: a starred app lives wherever it lives. Hence a
     * READ check against that project rather than against the one the caller
     * came from.
     *
     * <p>An app that has no places answers with an empty list. A folder without
     * a manifest, or one carrying an unknown {@code app:}, is a 404: the caller
     * named something that is not there, and returning "no places" would read
     * as "this app has none".
     */
    @GetMapping("/brain/{tenant}/applications/targets")
    public ApplicationTargetsResponse targets(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam("path") String path,
            @RequestParam(value = "purpose", defaultValue = "NAVIGATE") String purpose,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        String user = authority.contextOf(httpRequest).subjectId();

        DocumentDocument doc = documentService.findByPath(tenant, projectId, path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No application manifest at '" + path + "'"));
        ApplicationDocument manifest = parseQuietly(doc);
        String appType = manifest == null ? null : manifest.app();
        if (appType == null || appType.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Not an application manifest: '" + path + "'");
        }
        VanceApplication app = registry.find(appType).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Unknown application type '" + appType + "'"));

        TargetPurpose parsed = parsePurpose(purpose);
        String folder = folderOf(path);
        List<AppTarget> targets;
        try {
            targets = app.targets(new TargetsContext(
                    tenant, projectId, folder, user, parsed, configBlock(manifest, appType)));
        } catch (RuntimeException e) {
            // A broken folder is the app's problem to report where it is edited.
            // Here it must not take down the picker: no places is a usable answer,
            // "link to the app itself" still works.
            log.warn("applications targets: app='{}' folder='{}' failed: {}",
                    appType, folder, e.toString());
            return new ApplicationTargetsResponse(List.of());
        }
        List<ApplicationTargetDto> out = new ArrayList<>(targets.size());
        for (AppTarget t : targets) out.add(new ApplicationTargetDto(t.handle(), t.label(), t.group()));
        return new ApplicationTargetsResponse(List.copyOf(out));
    }

    // ──────────────────── internals ────────────────────

    /**
     * Starred entries that are applications, highlighted first.
     *
     * <p>{@code listResolvable} filters by <em>visibility</em> (enabled/hidden),
     * not by permission — the list is a file in the user's own hub project and
     * can name a project they have since lost access to. So each entry is
     * checked, and one that fails is dropped silently: it is the user's own
     * bookmark going stale, not an error to report at them.
     */
    private List<ApplicationEntryDto> starredApps(String tenant, SecurityContext ctx) {
        List<StarredItem> items = starredService.listResolvable(tenant, ctx.subjectId()).stream()
                .filter(i -> i.type() != null)
                // Stable: only highlight decides, equal ones keep file order.
                .sorted(Comparator.comparing(StarredItem::highlight).reversed())
                .toList();
        List<ApplicationEntryDto> out = new ArrayList<>(items.size());
        for (StarredItem item : items) {
            if (!permissionService.check(
                    ctx, new Resource.Project(tenant, item.project()), Action.READ)) {
                continue;
            }
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenant, item.project(), item.path());
            if (doc.isEmpty()) continue;
            ApplicationEntryDto entry = entryOf(tenant, item.project(), doc.get());
            if (entry == null) continue;
            // The user's own label for the entry wins over the manifest title.
            String title = item.title() != null && !item.title().isBlank()
                    ? item.title() : entry.title();
            out.add(new ApplicationEntryDto(
                    entry.project(), entry.path(), entry.app(), title, entry.icon()));
        }
        return out;
    }

    /** {@code null} when the document is not a usable app manifest. */
    private @Nullable ApplicationEntryDto entryOf(
            String tenant, String project, DocumentDocument doc) {

        String path = doc.getPath();
        if (path == null) return null;
        ApplicationDocument manifest = parseQuietly(doc);
        String appType = manifest == null ? null : manifest.app();
        if (appType == null || appType.isBlank()) return null;

        String folder = folderOf(path);
        String title = manifest.title() != null && !manifest.title().isBlank()
                ? manifest.title() : leaf(folder);
        return new ApplicationEntryDto(project, path, appType, title,
                iconOf(tenant, project, folder, appType, manifest));
    }

    /**
     * The app's own icon. {@code describe()} is contractually cheap
     * (manifest-level reads, no folder scan), which is what makes it usable in
     * a listing at all; an app that throws anyway just loses its icon.
     */
    private @Nullable String iconOf(String tenant, String project, String folder,
                                    String appType, ApplicationDocument manifest) {
        Optional<VanceApplication> app = registry.find(appType);
        if (app.isEmpty()) return null;
        try {
            return app.get().describe(new DescribeContext(
                    tenant, project, folder, null, configBlock(manifest, appType))).icon();
        } catch (RuntimeException e) {
            log.debug("applications list: describe() failed for app='{}' folder='{}': {}",
                    appType, folder, e.toString());
            return null;
        }
    }

    private static TargetPurpose parsePurpose(String raw) {
        try {
            return TargetPurpose.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown purpose '" + raw + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configBlock(ApplicationDocument manifest, String appType) {
        Object raw = manifest.config().get(appType);
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new LinkedHashMap<>();
    }

    private @Nullable ApplicationDocument parseQuietly(DocumentDocument doc) {
        String mime = doc.getMimeType();
        if (!ApplicationCodec.supports(mime)) return null;
        try {
            return ApplicationCodec.parse(loadAsText(doc), mime);
        } catch (RuntimeException e) {
            log.warn("applications: could not parse manifest '{}': {}",
                    doc.getPath(), e.toString());
            return null;
        }
    }

    private String loadAsText(DocumentDocument doc) {
        String cached = documentService.readContent(doc);
        if (cached != null) return cached;
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read manifest '" + doc.getPath() + "'", e);
        }
    }

    /** {@code <folder>/_app.yaml} → {@code <folder>}. */
    private static String folderOf(String path) {
        return path.endsWith(MANIFEST_SUFFIX)
                ? path.substring(0, path.length() - MANIFEST_SUFFIX.length())
                : path;
    }

    private static String leaf(String folder) {
        int i = folder.lastIndexOf('/');
        return i < 0 ? folder : folder.substring(i + 1);
    }

    private static String key(String project, String path) {
        return project + " " + path;
    }
}
