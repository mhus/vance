package de.mhus.vance.shared.homes;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The user-scoped home {@code <homes>/<tenant>/_user_<login>} — what a user's
 * agent processes accumulate under {@code home-scope: user}. It exists because
 * of the account, so a user delete removes it ({@code user-maintenance.md}:
 * OWNED gets deleted, RECORD gets tombstoned) and a user rename carries it
 * over: same person, new login, same home.
 */
@Component
public class HomeUserDataHandler implements UserDataHandler {

    private final HomesService homes;

    public HomeUserDataHandler(HomesService homes) {
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
        return 1100;
    }

    @Override
    public long count(String tenantId, String userName) {
        return homes.exists(tenantId, HomeBootstrapService.hubProjectName(userName)) ? 1 : 0;
    }

    @Override
    public long delete(String tenantId, String userName) {
        return homes.delete(tenantId, HomeBootstrapService.hubProjectName(userName));
    }

    @Override
    public long rename(String tenantId, String userName, String newUserName) {
        return homes.rename(
                tenantId,
                HomeBootstrapService.hubProjectName(userName),
                HomeBootstrapService.hubProjectName(newUserName));
    }
}
