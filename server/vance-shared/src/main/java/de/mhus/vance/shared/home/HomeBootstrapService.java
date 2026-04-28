package de.mhus.vance.shared.home;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectKind;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.projectgroup.ProjectGroupDocument;
import de.mhus.vance.shared.projectgroup.ProjectGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Idempotently ensures the per-user Vance Hub infrastructure exists:
 * the tenant-level {@value #HOME_GROUP_NAME} project group and the
 * per-user {@link ProjectKind#SYSTEM} project named
 * {@code vance-<userLogin>}.
 *
 * <p>Callers invoke {@link #ensureHome(String, String)} on first login
 * (or service boot) and may call it repeatedly without side effects.
 * The trigger wiring is defined by the brain layer; this service only
 * owns the data-shape contract.
 *
 * <p>See {@code specification/vance-engine.md} §2.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeBootstrapService {

    /** Reserved name of the auto-created tenant-level Hub project group. */
    public static final String HOME_GROUP_NAME = "home";

    /** Display title of the Hub project group on first creation. */
    public static final String HOME_GROUP_TITLE = "Home";

    /** Prefix of every per-user Hub project. */
    public static final String HUB_PROJECT_NAME_PREFIX = "vance-";

    private final ProjectGroupService projectGroupService;
    private final ProjectService projectService;

    /**
     * Builds the deterministic project name of the Hub project for
     * {@code userLogin}: {@code "vance-" + userLogin}.
     */
    public static String hubProjectName(String userLogin) {
        return HUB_PROJECT_NAME_PREFIX + userLogin;
    }

    /**
     * Ensures the {@code Home} project group exists in {@code tenantId}
     * and a {@link ProjectKind#SYSTEM} project named
     * {@code vance-<userLogin>} exists inside it. Idempotent: repeated
     * calls return the existing rows without modification.
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
}
