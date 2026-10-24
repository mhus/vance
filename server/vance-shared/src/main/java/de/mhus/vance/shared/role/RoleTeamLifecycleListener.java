package de.mhus.vance.shared.role;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamLifecycleListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Keeps team-subject role assignments in step with the teams they name — the
 * team twin of {@link RoleUserLifecycleListener}, same reasoning: clear on
 * delete, and clear again on create so a reused name never inherits a role
 * that outlived its team.
 */
@Service
@RequiredArgsConstructor
public class RoleTeamLifecycleListener implements TeamLifecycleListener {

    private final RoleBootstrap bootstrap;

    @Override
    public void onTeamCreated(TeamDocument team) {
        bootstrap.revokeAllOfTeam(team.getTenantId(), team.getName());
    }

    @Override
    public void onTeamDeleted(String tenantId, String name) {
        bootstrap.revokeAllOfTeam(tenantId, name);
    }
}
