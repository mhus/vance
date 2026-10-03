package de.mhus.vance.shared.homes;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The project-scoped home directory a project owns: {@code <homes>/<tenant>/
 * <project-name>}. Not a Mongo entity — the maintenance seam answers the same
 * three questions for on-disk state, because a project delete that leaves its
 * home behind leaves the next project created under that name with the
 * previous one's caches and granted credentials.
 *
 * <p><b>Only the project-scoped home belongs to the project.</b> Under
 * {@code home-scope: tenant|user} the home lives under {@code _tenant} /
 * {@code _user_<login>} and is <em>shared</em> — deleting one project must not
 * delete it, so this handler reports what it deliberately keeps
 * ({@link #deleteNote}) instead of counting it as gone.
 */
@Component
public class HomeProjectDataHandler implements ProjectDataHandler {

    private final HomesService homes;

    public HomeProjectDataHandler(HomesService homes) {
        this.homes = homes;
    }

    @Override
    public String id() {
        return "homes";
    }

    /** Homes are filesystem state — no Mongo collection answers for them. */
    @Override
    public Set<String> collections() {
        return Set.of();
    }

    @Override
    public int order() {
        return 2700;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return homes.exists(tenantId, projectId) ? 1 : 0;
    }

    @Override
    public long delete(String tenantId, String projectId) {
        return homes.delete(tenantId, projectId);
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        return homes.rename(tenantId, projectId, newProjectId);
    }

    @Override
    public @Nullable String renameBlocker(String tenantId, String projectId, String newProjectId) {
        return homes.exists(tenantId, newProjectId)
                ? "a home directory already exists under the new project name '" + newProjectId + "'"
                : null;
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String projectId) {
        HomeScope scope = homes.scopeOf(tenantId, projectId);
        if (scope == HomeScope.PROJECT) {
            return null;
        }
        return "the shared "
                + scope.name().toLowerCase()
                + " home '"
                + homes.scopeKey(tenantId, projectId, null)
                + "' is kept — it is not owned by this project";
    }
}
