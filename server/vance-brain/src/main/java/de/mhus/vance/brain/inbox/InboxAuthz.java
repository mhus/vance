package de.mhus.vance.brain.inbox;

import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The inbox visibility rule in one place: a user may see/touch an item when
 * they are its assignee, or share a team with the assignee.
 *
 * <p>Extracted from {@code InboxController} so the REST surface and the
 * permission provider's {@code Resource.InboxItem} rule (R5) evaluate
 * <em>identical</em> semantics — the WS handlers already enforce through the
 * abstract {@code Resource.InboxItem} gate; the provider resolves it with this
 * helper. See {@code planning/permission-system-concept.md} §4.1 (R5).
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
