package de.mhus.vance.brain.webdav;

import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import io.milton.http.Auth;
import io.milton.http.HttpManager;
import io.milton.http.Request;
import io.milton.http.http11.auth.DigestResponse;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Bridges HTTP Basic-Auth into the Vance identity + permission stack.
 *
 * <p>WebDAV clients (macOS Finder, Obsidian) speak Basic-Auth, not the
 * bearer-JWT the rest of Brain uses — so {@code BrainAccessFilter} bypasses the
 * {@code /webdav/} path and authentication happens here instead. The username
 * is verified against the same {@link PasswordService} as the token-mint login;
 * the tenant is taken from the request URL (a user only ever authenticates for
 * the tenant in the path). Authorisation reuses {@link PermissionService} with
 * the same {@link Resource}/{@link Action} model the REST controllers use. See
 * {@code planning/webdav-support.md} §3–§4.
 */
@Slf4j
public class VanceWebDavSecurityManager implements io.milton.http.SecurityManager {

    private final PasswordService passwordService;
    private final UserService userService;
    private final TeamService teamService;
    private final PermissionService permissionService;
    private final WebDavProperties properties;

    public VanceWebDavSecurityManager(
            PasswordService passwordService,
            UserService userService,
            TeamService teamService,
            PermissionService permissionService,
            WebDavProperties properties) {
        this.passwordService = passwordService;
        this.userService = userService;
        this.teamService = teamService;
        this.permissionService = permissionService;
        this.properties = properties;
    }

    @Override
    public @Nullable Object authenticate(DigestResponse digestRequest) {
        // Digest auth is not offered — Basic over TLS only.
        return null;
    }

    @Override
    public @Nullable Object authenticate(String user, String password) {
        String tenantId = currentTenant();
        if (tenantId == null) {
            return null;
        }
        return authenticate(tenantId, user, password);
    }

    /**
     * Tenant-explicit authentication — used by {@code WebDavLockService}, which
     * handles LOCK/UNLOCK outside the milton request context (where
     * {@link #currentTenant()} would be unavailable). Returns the principal or
     * {@code null} on any failure.
     */
    public @Nullable DavPrincipal authenticate(String tenantId, String user, String password) {
        Optional<UserDocument> userOpt = userService.findByTenantAndName(tenantId, user);
        if (userOpt.isEmpty()) {
            log.debug("webdav auth: unknown user tenant='{}' name='{}'", tenantId, user);
            // Match the BCrypt cost of a real check so timing can't enumerate users.
            passwordService.verifyDecoy(password);
            return null;
        }
        UserDocument doc = userOpt.get();
        if (doc.getStatus() != UserStatus.ACTIVE || !doc.isLoginEnabled()) {
            log.debug("webdav auth: user not eligible tenant='{}' name='{}' status={} loginEnabled={}",
                    tenantId, user, doc.getStatus(), doc.isLoginEnabled());
            passwordService.verifyDecoy(password);
            return null;
        }
        String hash = doc.getPasswordHash();
        if (hash == null) {
            passwordService.verifyDecoy(password);
            return null;
        }
        if (!passwordService.verify(password, hash)) {
            log.debug("webdav auth: bad password tenant='{}' name='{}'", tenantId, user);
            return null;
        }
        return new DavPrincipal(tenantId, user, resolveTeams(tenantId, user));
    }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth, io.milton.resource.Resource resource) {
        if (auth == null || !(auth.getTag() instanceof DavPrincipal principal)) {
            return false;
        }
        if (!(resource instanceof AbstractDavResource dav)) {
            return false;
        }
        Action action;
        if (!method.isWrite) {
            action = Action.READ;
        } else if (dav.isCollection()
                && (method == Request.Method.PUT || method == Request.Method.MKCOL)) {
            // PUT/MKCOL against a collection creates a child.
            action = Action.CREATE;
        } else {
            action = Action.WRITE;
        }
        return check(principal, dav, action);
    }

    /**
     * Per-resource authorisation reusing the same {@link PermissionService}
     * model as the REST controllers: collections map to {@link Resource.Project},
     * files to {@link Resource.Document}. Also exposed to {@code WebDavLockService}
     * for the LOCK/UNLOCK path.
     */
    public boolean check(DavPrincipal principal, AbstractDavResource dav, Action action) {
        // A user authenticated for tenant A must never act on tenant B's tree.
        if (!principal.tenantId().equals(dav.coords().tenantId())) {
            return false;
        }
        SecurityContext ctx = SecurityContext.user(
                principal.username(), principal.tenantId(), principal.teams());
        Resource target = dav.isCollection()
                ? new Resource.Project(dav.coords().tenantId(), requireProject(dav))
                : new Resource.Document(dav.coords().tenantId(), requireProject(dav), dav.coords().path());
        return permissionService.check(ctx, target, action);
    }

    /**
     * Authorise {@code action} against a target identified only by its
     * {@link WebDavPaths.Coords} — for paths that do not yet resolve to a
     * resource, notably the LOCK-null case where macOS locks a document before
     * its first PUT. Lets {@code WebDavLockService} gate CREATE <em>before</em>
     * materialising an empty target.
     */
    public boolean check(DavPrincipal principal, WebDavPaths.Coords coords,
            boolean collection, Action action) {
        if (coords.project() == null || !principal.tenantId().equals(coords.tenantId())) {
            return false;
        }
        SecurityContext ctx = SecurityContext.user(
                principal.username(), principal.tenantId(), principal.teams());
        Resource target = collection
                ? new Resource.Project(coords.tenantId(), coords.project())
                : new Resource.Document(coords.tenantId(), coords.project(), coords.path());
        return permissionService.check(ctx, target, action);
    }

    @Override
    public String getRealm(String host) {
        return properties.getRealm();
    }

    @Override
    public boolean isDigestAllowed() {
        return false;
    }

    private static String requireProject(AbstractDavResource dav) {
        String project = dav.coords().project();
        if (project == null) {
            // Should never happen — the factory refuses to resolve a resource
            // without a project. Kept as a hard guard rather than an NPE.
            throw new IllegalStateException("WebDAV resource has no project: " + dav.coords());
        }
        return project;
    }

    private List<String> resolveTeams(String tenantId, String username) {
        return teamService.byMember(tenantId, username).stream()
                .map(TeamDocument::getName)
                .toList();
    }

    private static @Nullable String currentTenant() {
        Request request = HttpManager.request();
        if (request == null) {
            return null;
        }
        return WebDavPaths.parse(request.getAbsolutePath())
                .map(WebDavPaths.Coords::tenantId)
                .orElse(null);
    }
}
