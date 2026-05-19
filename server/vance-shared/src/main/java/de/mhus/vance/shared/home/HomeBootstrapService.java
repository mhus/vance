package de.mhus.vance.shared.home;

import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import de.mhus.vance.shared.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Idempotently ensures the per-user Vance Hub infrastructure exists:
 * the tenant-level {@value #HOME_GROUP_NAME} project group and the
 * per-user {@link ProjectKind#SYSTEM} project named
 * {@value #HUB_PROJECT_NAME_PREFIX}{@code <userLogin>}, parented to
 * the {@value #HOME_GROUP_NAME} group.
 *
 * <p>Both names start with {@code "_"} — that prefix is reserved for
 * system-owned objects so they cannot collide with user-created
 * projects (see {@link ProjectService#SYSTEM_NAME_PREFIX}).
 *
 * <p>Callers invoke {@link #ensureHome(String, String)} on first login
 * (or service boot) and may call it repeatedly without side effects.
 * Handlers that would otherwise reject a session-create against an
 * unknown {@code _user_<login>} project can call
 * {@link #resolveOrAutoProvision(String, String)} to lazily provision
 * the Hub on demand.
 *
 * <p>See {@code specification/vance-engine.md} §2.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeBootstrapService {

    /**
     * Reserved name of the auto-created tenant-level Hub project group.
     * Starts with {@code "_"} to mark it as system-owned (same prefix
     * convention as the per-user Hub projects themselves).
     */
    public static final String HOME_GROUP_NAME = "_home";

    /** Display title of the Hub project group on first creation. */
    public static final String HOME_GROUP_TITLE = "Home";

    /**
     * Prefix of every per-user Hub project. Starts with {@code "_"} —
     * the underscore prefix is reserved for {@code SYSTEM}-kind
     * projects across the system, so a regular user project name can
     * never collide with a Hub project name (see
     * {@link ProjectService} for the validation that enforces this).
     */
    public static final String HUB_PROJECT_NAME_PREFIX = "_user_";

    /**
     * Reserved name of the tenant-wide system project — sibling to the
     * per-user {@code _user_<login>} projects, but tenant-scoped (one
     * per tenant, no user suffix). Holds the override layer for
     * system-wide defaults: configuration documents (recipes, skills,
     * server-tools, scheduler, …) live here unless a user project
     * shadows them by path. The document-path prefix {@code _vance/}
     * remains as a historical namespace for system-managed documents;
     * the project itself is named {@code _tenant} to avoid the
     * name collision with that prefix.
     */
    public static final String TENANT_PROJECT_NAME = "_tenant";

    /** Display title of the {@link #TENANT_PROJECT_NAME} project on first creation. */
    public static final String TENANT_PROJECT_TITLE = "Tenant Defaults";

    private final ProjectGroupService projectGroupService;
    private final ProjectService projectService;
    private final UserService userService;
    private final ProjectKitsCatalogService projectKitsCatalogService;

    /**
     * Builds the deterministic project name of the Hub project for
     * {@code userLogin}: {@value #HUB_PROJECT_NAME_PREFIX} + userLogin.
     */
    public static String hubProjectName(String userLogin) {
        return HUB_PROJECT_NAME_PREFIX + userLogin;
    }

    /**
     * Ensures the {@value #HOME_GROUP_NAME} project group exists in
     * {@code tenantId} and a {@link ProjectKind#SYSTEM} project named
     * {@value #HUB_PROJECT_NAME_PREFIX}{@code <userLogin>} exists
     * inside it. Idempotent: repeated calls return the existing rows
     * without modification.
     *
     * @return the per-user Hub project document
     */
    public ProjectDocument ensureHome(String tenantId, String userLogin) {
        ProjectGroupDocument group = ensureHomeGroup(tenantId);
        String projectName = hubProjectName(userLogin);
        return projectService.findByTenantAndName(tenantId, projectName)
                .orElseGet(() -> {
                    ProjectDocument created = projectService.create(
                            tenantId,
                            projectName,
                            "Vance — " + userLogin,
                            group.getName(),
                            null,
                            ProjectKind.SYSTEM);
                    log.info("Bootstrapped Vance Hub project tenantId='{}' userLogin='{}' project='{}'",
                            tenantId, userLogin, created.getName());
                    return created;
                });
    }

    private ProjectGroupDocument ensureHomeGroup(String tenantId) {
        return projectGroupService.findByTenantAndName(tenantId, HOME_GROUP_NAME)
                .orElseGet(() -> projectGroupService.create(
                        tenantId, HOME_GROUP_NAME, HOME_GROUP_TITLE));
    }

    /**
     * Ensures the tenant-wide {@value #TENANT_PROJECT_NAME} system project
     * exists in {@code tenantId}, creating it inside the {@value #HOME_GROUP_NAME}
     * project group on first call. Idempotent: repeated calls return the
     * existing row.
     *
     * <p>Intended use: a holding project for tenant-level overrides of
     * system defaults (documents, prompts, agent memos). The lookup
     * paths that consult it (documents under {@code _vance/…}, recipes,
     * skills, server-tools, …) are added by the resource-loader on top
     * of this layer — this method only provisions the slot.
     */
    public ProjectDocument ensureTenantProject(String tenantId) {
        ProjectGroupDocument group = ensureHomeGroup(tenantId);
        ProjectDocument project = projectService.findByTenantAndName(tenantId, TENANT_PROJECT_NAME)
                .orElseGet(() -> {
                    ProjectDocument created = projectService.create(
                            tenantId,
                            TENANT_PROJECT_NAME,
                            TENANT_PROJECT_TITLE,
                            group.getName(),
                            null,
                            ProjectKind.SYSTEM);
                    log.info("Bootstrapped tenant-wide system project tenantId='{}' project='{}'",
                            tenantId, created.getName());
                    return created;
                });
        // Seed project-kits catalog from the system tenant on first call.
        // Idempotent: skips when the target already has a catalog, when
        // the source is empty, or when the target is the system tenant
        // itself. Cheap (one document lookup) on repeat invocations.
        projectKitsCatalogService.seedFromSystemTenant(tenantId);
        return project;
    }

    /**
     * Lazy-provisioning hook for handlers that look up a project by
     * name and want to auto-create the Hub project if the caller
     * targeted {@code _user_<login>} for an existing user. Returns
     * the (possibly freshly bootstrapped) project, or
     * {@link Optional#empty()} if {@code projectName} doesn't match
     * the Hub pattern or the user doesn't exist.
     *
     * <p>Robust against the rare case that the Login-time bootstrap
     * (in {@code AccessController}) didn't fire — e.g. the JWT was
     * minted out-of-band, or the user was created after the last
     * login of an admin who already had a token.
     *
     * @param tenantId    tenant scope
     * @param projectName the {@code projectId} the caller asked for
     * @return the existing or freshly created Hub project, or empty
     *         if not applicable
     */
    public Optional<ProjectDocument> resolveOrAutoProvision(
            String tenantId, String projectName) {
        Optional<ProjectDocument> existing =
                projectService.findByTenantAndName(tenantId, projectName);
        if (existing.isPresent()) {
            return existing;
        }
        if (!projectName.startsWith(HUB_PROJECT_NAME_PREFIX)) {
            return Optional.empty();
        }
        String userLogin = projectName.substring(HUB_PROJECT_NAME_PREFIX.length());
        if (userLogin.isEmpty()) {
            return Optional.empty();
        }
        if (!userService.existsByTenantAndName(tenantId, userLogin)) {
            log.debug("Auto-provision skipped: user '{}' not found in tenant '{}'",
                    userLogin, tenantId);
            return Optional.empty();
        }
        log.info("Auto-provisioning Hub project tenantId='{}' userLogin='{}' "
                + "(triggered by session lookup, not login)",
                tenantId, userLogin);
        return Optional.of(ensureHome(tenantId, userLogin));
    }
}
