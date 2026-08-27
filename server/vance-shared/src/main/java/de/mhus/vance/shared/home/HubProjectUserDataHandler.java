package de.mhus.vance.shared.home;

import de.mhus.vance.shared.maintenance.MaintenanceReport;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectMaintenanceService;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The account's own project — {@code _user_<login>}, its persona, its facts,
 * its starred list, and whatever else it kept there.
 *
 * <p>The one handler that does not touch a collection itself: it hands the hub
 * to {@link ProjectMaintenanceService} and lets the whole project machinery run
 * on it. That is not delegation for tidiness — a hub contains sessions,
 * documents, memories and possibly Trillian service accounts of its own, and
 * re-deriving any of that here would be a second, worse copy of a sweep that
 * already exists and is drift-tested.
 *
 * <h2>Why it runs first</h2>
 *
 * <p>At {@value #ORDER}, before every other user handler. The hub is the
 * largest thing going, and taking it out first means every handler after it
 * only has to deal with what the account left in <em>other people's</em>
 * projects — which is both less work and a truer count. It also avoids
 * tombstoning authorship inside documents that are about to be deleted anyway.
 *
 * <h2>The narrow door past the SYSTEM guard</h2>
 *
 * <p>A hub is a {@link de.mhus.vance.shared.project.ProjectKind#SYSTEM} project
 * and the ordinary project delete refuses those — rightly, {@code _vance} must
 * never go. {@code deleteUserHub} is the exception, and it is an exception with
 * a shape rather than a flag: it accepts nothing that is not named
 * {@code _user_<login>}. On a rename the hub moves with the login for the same
 * reason it exists — its name <em>is</em> the login, and a hub under the old
 * one is a hub nobody looks for.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HubProjectUserDataHandler implements UserDataHandler {

    /** Before everything: the biggest piece, and it shrinks all the rest. */
    public static final int ORDER = 50;

    private final ProjectMaintenanceService projectMaintenanceService;
    private final ProjectService projectService;

    @Override
    public String id() {
        return "hub-project";
    }

    /**
     * The project row itself. Everything <em>inside</em> the hub is claimed by
     * the project handlers, so naming their collections here would say this
     * handler answers for them in a user run too — which it does not; it only
     * starts the run that does.
     */
    @Override
    public Set<String> collections() {
        return Set.of("projects");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public long count(String tenantId, String userName) {
        return hubExists(tenantId, userName) ? 1 : 0;
    }

    @Override
    public long delete(String tenantId, String userName) {
        String hub = HomeBootstrapService.hubProjectName(userName);
        if (!hubExists(tenantId, userName)) {
            return 0;
        }
        MaintenanceReport report = projectMaintenanceService.deleteUserHub(tenantId, hub);
        log.info("User '{}': deleted hub project '{}' ({} row(s))",
                userName, hub, report.total());
        return 1;
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String userName) {
        if (!hubExists(tenantId, userName)) {
            return null;
        }
        return "the account's hub project '" + HomeBootstrapService.hubProjectName(userName)
                + "' goes with it — run 'project inspect' on it first to see what that is";
    }

    @Override
    public long rename(String tenantId, String userName, String newUserName) {
        if (!hubExists(tenantId, userName)) {
            return 0;
        }
        projectMaintenanceService.renameUserHub(
                tenantId,
                HomeBootstrapService.hubProjectName(userName),
                HomeBootstrapService.hubProjectName(newUserName));
        return 1;
    }

    /**
     * Refuses when a hub already sits under the new login.
     *
     * <p>Asked before anything is written: finding this out halfway would leave
     * the account renamed and its hub stranded under the old name.
     */
    @Override
    public @Nullable String renameBlocker(
            String tenantId, String userName, String newUserName) {
        if (hubExists(tenantId, userName) && hubExists(tenantId, newUserName)) {
            return "a hub project '" + HomeBootstrapService.hubProjectName(newUserName)
                    + "' already exists — merging two hubs is not a rename";
        }
        return null;
    }

    private boolean hubExists(String tenantId, String userName) {
        return projectService.existsByTenantAndName(
                tenantId, HomeBootstrapService.hubProjectName(userName));
    }
}
