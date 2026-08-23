package de.mhus.vance.brain.inbox;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The inbox visibility rule for the REST surface: a user may see/touch an item
 * when they are its assignee, or share a team with the assignee. Extracted from
 * {@code InboxController} so the rule lives in one place per module.
 *
 * <p><b>The same rule exists a second time</b>, in
 * {@code MongoPermissionResolver#inboxAllowed} (rule R5), which is what the WS
 * handlers hit through the abstract {@code Resource.InboxItem} gate. The
 * duplication is forced by the module boundary — the resolver ships in
 * {@code vance-addon-shared-simpleauth}, which builds on {@code vance-shared}
 * and must not depend on {@code vance-brain}, so it cannot call this class.
 * (An earlier plan was to extract this helper into shared for exactly that
 * reason; it ended up in brain instead.)
 *
 * <p><b>Change both or neither.</b> The two copies are only useful while they
 * agree: REST and WS would otherwise authorize the same request differently.
 * See {@code planning/archive/permission-system-concept.md} §4.1 and §4.3 (R5).
 */
@Component
@RequiredArgsConstructor
public class InboxAuthz {

    private final TeamService teamService;

    /** True iff {@code userA} and {@code userB} are the same or share a team. */
    public boolean sharesTeam(String tenant, String userA, String userB) {
        if (userA.equals(userB)) {
            return true;
        }
        for (TeamDocument t : teamService.byMember(tenant, userA)) {
            if (t.getMembers() != null && t.getMembers().contains(userB)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff {@code currentUser} may access an item assigned to
     * {@code assignee} — own inbox or a shared-team assignee. An item with no
     * assignee is never freely accessible.
     */
    public boolean isAuthorized(String tenant, String currentUser, @Nullable String assignee) {
        return assignee != null
                && (assignee.equals(currentUser) || sharesTeam(tenant, currentUser, assignee));
    }
}
