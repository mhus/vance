package de.mhus.vance.store.brain;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The store addon's surface.
 *
 * <p>Everything a store credential touches happens on this side of the
 * wire. The browser posts an email and a password once and never sees a
 * token in return — what comes back is an account id, which is not a
 * secret and is what the screen needs to say who is signed in.
 *
 * <p>Spec: {@code planning/kit-store.md} §7 Phase S3.
 */
@RestController
@RequestMapping("/brain/{tenant}/addon/store")
@RequiredArgsConstructor
@Slf4j
public class StoreAddonController {

    private final KitSourceRegistry sources;
    private final StoreOverviewService overview;
    private final StoreConnectionService connections;
    private final KitService kitService;
    private final RequestAuthority authority;

    public record ConnectRequest(
            String sourceId, String email, String password, @Nullable String label) {}

    public record DisconnectRequest(String sourceId) {}

    public record InstallRequest(String sourceId, String path) {}

    /** The four lists, per configured library. */
    @GetMapping("/{projectId}/overview")
    public List<StoreOverviewService.SourceView> overview(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        return overview.overview(tenant, projectId, actor(request));
    }

    /**
     * Sign in to a store.
     *
     * <p>A POST with a password in the body, which is why it is not a
     * query anywhere. The password is used once against the store and
     * discarded; what is kept is the link token it produced.
     */
    @PostMapping("/{projectId}/connect")
    public StoreConnectionService.Connection connect(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody ConnectRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return connections.connect(
                    tenant, actor(request), library(tenant, body.sourceId()),
                    body.email(), body.password(), body.label(), projectId);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /** Forget the credential here. The link at the store survives. */
    @PostMapping("/{projectId}/disconnect")
    public StoreConnectionService.Connection disconnect(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody DisconnectRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        connections.disconnect(tenant, actor(request), source);
        return new StoreConnectionService.Connection(source.getId(), null);
    }

    /**
     * Install or update one kit from a library.
     *
     * <p>Delegates to the ordinary kit path — the same install a person
     * would run from the scopes screen, with the same policy, the same
     * signature check and the same licence gate. This addon adds a button,
     * not a second way in.
     *
     * <p>{@code UPDATE} when it is already installed, {@code INSTALL}
     * otherwise: the install path refuses to install over an existing
     * record on purpose, and deciding here saves the screen from having to
     * know which verb applies.
     */
    @PostMapping("/{projectId}/install")
    public KitOperationResultDto install(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody InstallRequest body,
            HttpServletRequest request) {

        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitSourceDto source = library(tenant, body.sourceId());
        boolean installed = overview.overview(tenant, projectId, actor(request)).stream()
                .filter(view -> view.sourceId().equals(source.getId()))
                .flatMap(view -> view.entries().stream())
                .anyMatch(entry -> entry.path().equals(body.path())
                        && entry.installedVersion() != null);

        KitImportRequestDto importRequest = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitInheritDto.builder()
                        .url(source.getUrl())
                        .path(body.path())
                        .build())
                .mode(installed ? KitImportMode.UPDATE : KitImportMode.INSTALL)
                .build();
        try {
            return kitService.importKit(
                    tenant, importRequest, actor(request), SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw storeError(e);
        }
    }

    /**
     * Resolve a configured library by id.
     *
     * <p>By id and not by url from the body: a url out of a request would
     * let a caller point this at a host of their choosing and have the
     * brain sign in there with credentials a person typed for a different
     * one.
     */
    private KitSourceDto library(String tenantId, String sourceId) {
        return sources.configuredSources(tenantId).stream()
                .filter(source -> source.getId().equals(sourceId))
                .filter(source -> source.getType() == KitSourceType.LIBRARY)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no library source '" + sourceId + "'"));
    }

    private static ResponseStatusException storeError(KitException e) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
    }

    /**
     * The signed-in brain user, whose settings the credential belongs to.
     *
     * <p>Required rather than optional here, unlike in the ordinary kit
     * controller where it only labels a write: a store credential belongs
     * to a person, and there is nowhere to put one that belongs to nobody.
     */
    private static String actor(HttpServletRequest request) {
        Object user = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        String actor = user == null ? null : user.toString();
        if (actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no user in this request");
        }
        return actor;
    }
}
