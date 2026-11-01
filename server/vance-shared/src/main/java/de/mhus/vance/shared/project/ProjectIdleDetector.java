package de.mhus.vance.shared.project;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Decides whether a project has gone stale: nobody who uses it has been around for
 * longer than the project allows. Read-only — it never changes a project, it only
 * answers; stopping one is {@code ProjectLifecycleService#idle}.
 *
 * <h2>What "nobody" means</h2>
 *
 * The criterion is about <em>people</em>, not about the project: the people who hold a
 * role in it, directly (a team in between does not count — teams are organisational,
 * not a source of rights), and the owner of a user's hub. For each of them the last sign-in counts, and so does the
 * newest session activity in the project. A user who never signed in since the field
 * existed is not "never seen": the creation date of the account stands in, so the first
 * scan after the field was introduced does not stop everything at once.
 *
 * <p>Service accounts never sign in interactively and are left out. A project whose
 * only members are service accounts has no one to wait for and is reported as such,
 * never as stale.
 *
 * <h2>What keeps a project awake regardless</h2>
 *
 * A client bound to a session in the project, and a think process in the middle of a
 * turn. Both mean somebody or something is using it right now, whatever the dates say.
 *
 * <h2>How long</h2>
 *
 * {@value #KEY_AFTER_DAYS}, read through the setting cascade project → tenant, with
 * the operator's default behind it. {@code 0} switches the project out of the policy.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectIdleDetector {

    /** Days without a user before a project goes idle; {@code 0} = never. Cascade project → tenant. */
    public static final String KEY_AFTER_DAYS = "project.idle.after-days";

    /** Most processes of a project looked at when asking whether one is mid-turn — newest first. */
    private static final int PROCESS_WINDOW = 200;

    private final ProjectService projectService;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final RoleAssignmentService roleAssignmentService;
    private final UserService userService;
    private final SettingService settingService;

    /** Why a project is, or is not, a candidate. */
    public enum Verdict {
        /** Nobody was around for longer than allowed. */
        STALE,
        /** Somebody was around recently enough. */
        ACTIVE,
        /** A client is connected or a process is mid-turn right now. */
        IN_USE,
        /** The setting is {@code 0}: this project is out of the policy. */
        DISABLED,
        /** No human holds a role in it — there is nobody to wait for. */
        NO_HUMAN_USER,
        /** Not running and not meant to — idle, suspended, archived, or the tenant project. */
        NOT_APPLICABLE
    }

    /**
     * One project's assessment.
     *
     * @param lastSeen the newest sign-in or session activity of its users; {@code null}
     *     when the verdict was reached without looking
     * @param afterDays the limit that applied, {@code 0} when none
     * @param detail what decided it, for the operator
     */
    public record Assessment(
            String tenantId,
            String projectName,
            Verdict verdict,
            @Nullable Instant lastSeen,
            int afterDays,
            String detail) {

        public boolean stale() {
            return verdict == Verdict.STALE;
        }
    }

    /** Assesses every project of the tenant. */
    public List<Assessment> scan(String tenantId, int defaultAfterDays, Instant now) {
        List<Assessment> out = new ArrayList<>();
        for (ProjectDocument project : projectService.all(tenantId)) {
            out.add(assess(project, defaultAfterDays, now));
        }
        return out;
    }

    /**
     * Assesses one project.
     *
     * @param defaultAfterDays the operator's limit when no setting says otherwise
     */
    public Assessment assess(ProjectDocument project, int defaultAfterDays, Instant now) {
        String tenantId = project.getTenantId();
        String name = project.getName();
        if (ProjectService.isHomeless(project) || !ProjectService.wantsToRun(project.getStatus())) {
            return verdict(project, Verdict.NOT_APPLICABLE, null, 0, "desired state " + project.getStatus());
        }
        int afterDays = afterDays(tenantId, name, defaultAfterDays);
        if (afterDays <= 0) {
            return verdict(project, Verdict.DISABLED, null, 0, KEY_AFTER_DAYS + " is 0");
        }

        List<SessionDocument> sessions = sessionService.listForProject(tenantId, name);
        String inUse = inUseReason(tenantId, name, sessions);
        if (inUse != null) {
            return verdict(project, Verdict.IN_USE, null, afterDays, inUse);
        }

        List<UserDocument> people = people(tenantId, name);
        if (people.isEmpty()) {
            return verdict(project, Verdict.NO_HUMAN_USER, null, afterDays, "only service accounts or nobody");
        }
        Instant lastSeen = lastSeen(people, sessions, now);
        Duration quiet = Duration.between(lastSeen, now);
        if (quiet.compareTo(Duration.ofDays(afterDays)) > 0) {
            return verdict(project, Verdict.STALE, lastSeen, afterDays, quiet.toDays() + " days without a user");
        }
        return verdict(project, Verdict.ACTIVE, lastSeen, afterDays, quiet.toDays() + " days since a user was here");
    }

    /**
     * Whether somebody or something is using the project <em>right now</em>: a client is connected
     * to one of its sessions, or a think process is in the middle of a turn. A project in use must
     * not be stopped or moved, whatever the dates say — both would cut the user off mid-sentence.
     */
    public boolean inUse(String tenantId, String projectName) {
        return inUseReason(tenantId, projectName, sessionService.listForProject(tenantId, projectName)) != null;
    }

    private @Nullable String inUseReason(String tenantId, String projectName, List<SessionDocument> sessions) {
        if (sessions.stream().anyMatch(s -> s.getBoundConnectionId() != null)) {
            return "a client is connected";
        }
        boolean midTurn = thinkProcessService.findByProject(tenantId, projectName, PROCESS_WINDOW).stream()
                .anyMatch(p -> p.getStatus() == ThinkProcessStatus.RUNNING);
        return midTurn ? "a process is running" : null;
    }

    private Assessment verdict(
            ProjectDocument project, Verdict verdict, @Nullable Instant lastSeen, int afterDays, String detail) {
        return new Assessment(project.getTenantId(), project.getName(), verdict, lastSeen, afterDays, detail);
    }

    /** The limit for this project: setting cascade project → tenant, else the operator's default. */
    private int afterDays(String tenantId, String projectName, int defaultAfterDays) {
        String configured = settingService.getStringValueCascade(tenantId, projectName, null, KEY_AFTER_DAYS);
        if (configured == null || configured.isBlank()) {
            return defaultAfterDays;
        }
        try {
            return Integer.parseInt(configured.strip());
        } catch (NumberFormatException e) {
            log.warn(
                    "Setting {} of project '{}/{}' is not a number ('{}') — using the default {}",
                    KEY_AFTER_DAYS,
                    tenantId,
                    projectName,
                    configured,
                    defaultAfterDays);
            return defaultAfterDays;
        }
    }

    /**
     * The people the project belongs to: users, not teams; no service accounts. Those with a role in
     * it, and — for a user's hub — the user the hub is named after: a hub carries no role assignment
     * for its owner, the name {@code _user_<login>} is the assignment.
     */
    private List<UserDocument> people(String tenantId, String projectName) {
        List<UserDocument> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (projectName.startsWith(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX)) {
            names.add(projectName.substring(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX.length()));
        }
        for (RoleAssignmentDocument assignment :
                roleAssignmentService.forScope(tenantId, RoleScopeType.PROJECT, projectName)) {
            if (assignment.getSubjectType() == RoleSubjectType.USER && !names.contains(assignment.getSubjectId())) {
                names.add(assignment.getSubjectId());
            }
        }
        for (String name : names) {
            userService
                    .findByTenantAndName(tenantId, name)
                    .filter(user -> !user.isServiceAccount())
                    .ifPresent(out::add);
        }
        return out;
    }

    /**
     * The newest sign-in of the people, the newest session activity, whichever is later.
     * An account without a recorded sign-in stands in with its creation date, and one
     * that has neither counts as just seen: a project is never stopped on a date nobody
     * can name.
     */
    private static Instant lastSeen(List<UserDocument> people, List<SessionDocument> sessions, Instant now) {
        Instant newest = Instant.EPOCH;
        for (UserDocument user : people) {
            Instant seen = user.getLastLoginAt() != null ? user.getLastLoginAt() : user.getCreatedAt();
            if (seen == null) {
                seen = now;
            }
            if (seen.isAfter(newest)) {
                newest = seen;
            }
        }
        for (SessionDocument session : sessions) {
            if (session.getLastActivityAt().isAfter(newest)) {
                newest = session.getLastActivityAt();
            }
        }
        return newest;
    }
}
