package de.mhus.vance.brain.inbox;

import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Who may do what with an inbox thread, in two questions that used to be one.
 *
 * <ul>
 *   <li>{@link #mayDecide} — answer, dismiss, delegate, archive. Unchanged
 *       rule, unchanged population: the assignee, or someone sharing a team
 *       with the assignee.</li>
 *   <li>{@link #maySee} — read the thread and contribute to it. The same
 *       population <em>plus</em> the thread's participants and its declared
 *       team.</li>
 * </ul>
 *
 * <p><b>Splitting them is what keeps the decision safe.</b> Threads let people
 * be invited into a discussion; if visibility and authority stayed one
 * predicate, every invited participant would silently gain the right to answer
 * — including the right to fire the item's {@code effectType}, which grants
 * permissions. Widening happens only on the seeing side.
 *
 * <p>Archiving counts as deciding, not as seeing: {@code status} is a property
 * of the shared thread, so a participant archiving it would clear it off the
 * assignee's desk.
 *
 * <p><b>The same rule exists a second time</b>, in
 * {@code MongoPermissionResolver#inboxAllowed} (rule R5), which is what the WS
 * handlers hit through the abstract {@code Resource.InboxItem} gate. The
 * duplication is forced by the module boundary — the resolver ships in
 * {@code vance-addon-shared-simpleauth}, which builds on {@code vance-shared}
 * and must not depend on {@code vance-brain}, so it cannot call this class.
 * (An earlier plan was to extract this helper into shared for exactly that
 * reason; it ended up in brain instead.) That copy mirrors {@link #mayDecide};
 * the participant/team widening of {@link #maySee} is <b>not</b> in it, because
 * participation is a property of the document rather than a permission — see
 * {@code planning/maximegalon.md} §5.
 *
 * <p><b>Change both or neither.</b> The two copies of the decide rule are only
 * useful while they agree: REST and WS would otherwise authorize the same
 * request differently. See
 * {@code planning/archive/permission-system-concept.md} §4.1 and §4.3 (R5).
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
     * True iff {@code currentUser} may <b>decide</b> on an item assigned to
     * {@code assignee} — own inbox or a shared-team assignee. An item with no
     * assignee is never freely accessible.
     *
     * <p>Deliberately unchanged, down to the population it admits: this is the
     * rule that gates answering, dismissing, delegating and archiving, and the
     * thread work must not widen it.
     */
    public boolean mayDecide(String tenant, String currentUser, @Nullable String assignee) {
        return assignee != null
                && (assignee.equals(currentUser) || sharesTeam(tenant, currentUser, assignee));
    }

    /**
     * True iff {@code currentUser} may <b>see</b> the thread and contribute to
     * it: everyone who {@link #mayDecide}, plus its participants, plus its
     * declared team.
     *
     * <p>Participation is checked as a property of the document rather than
     * asked of the permission provider — an invitation is authorized when it
     * happens ({@code Resource.InboxItem} + {@code WRITE} on the invitee's
     * inbox), and from then on membership is the answer. That keeps
     * {@code Resource.InboxItem} unchanged, which matters because every
     * resolver implements it, including the EE governor.
     *
     * <p>A declared {@code teamId} is <b>additional</b>, not a replacement: with
     * none set the derived rule applies unchanged, so existing threads behave
     * exactly as before. Where one is set, visibility stops travelling with the
     * assignee — delegating no longer hands the thread from one team to another
     * behind everyone's back.
     */
    public boolean maySee(String tenant, String currentUser, MaximegalonDocument doc) {
        List<String> participants = doc.getParticipants();
        if (participants != null && participants.contains(currentUser)) {
            return true;
        }
        String teamId = doc.getTeamId();
        if (teamId != null && !teamId.isBlank() && isTeamMember(tenant, currentUser, teamId)) {
            return true;
        }
        return mayDecide(tenant, currentUser, doc.getAssignedToUserId());
    }

    /** True iff {@code user} is a member of the team named {@code teamName}. */
    private boolean isTeamMember(String tenant, String user, String teamName) {
        for (TeamDocument t : teamService.byMember(tenant, user)) {
            if (teamName.equals(t.getName())) {
                return true;
            }
        }
        return false;
    }
}
