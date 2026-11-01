package de.mhus.vance.shared.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.project.ProjectIdleDetector.Assessment;
import de.mhus.vance.shared.project.ProjectIdleDetector.Verdict;
import de.mhus.vance.shared.role.RoleAssignmentDocument;
import de.mhus.vance.shared.role.RoleAssignmentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A project is stale when nobody who uses it has been around for longer than it
 * allows — and never while a client is connected or a process is mid-turn.
 */
class ProjectIdleDetectorTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "work";
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final int DEFAULT_DAYS = 30;

    private SessionService sessions;
    private ThinkProcessService processes;
    private RoleAssignmentService roles;
    private UserService users;
    private SettingService settings;
    private ProjectIdleDetector detector;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        processes = mock(ThinkProcessService.class);
        roles = mock(RoleAssignmentService.class);
        users = mock(UserService.class);
        settings = mock(SettingService.class);
        detector = new ProjectIdleDetector(mock(ProjectService.class), sessions, processes, roles, users, settings);
        when(sessions.listForProject(TENANT, PROJECT)).thenReturn(List.of());
        when(processes.findByProject(eq(TENANT), eq(PROJECT), anyInt())).thenReturn(List.of());
    }

    private static ProjectDocument project(ProjectStatus status) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name(PROJECT)
                .status(status)
                .build();
    }

    private void givenMember(String name, boolean serviceAccount, Instant lastLogin, Instant createdAt) {
        RoleAssignmentDocument assignment = new RoleAssignmentDocument();
        assignment.setTenantId(TENANT);
        assignment.setScopeType(RoleScopeType.PROJECT);
        assignment.setScopeId(PROJECT);
        assignment.setSubjectType(RoleSubjectType.USER);
        assignment.setSubjectId(name);
        List<RoleAssignmentDocument> existing =
                new java.util.ArrayList<>(roles.forScope(TENANT, RoleScopeType.PROJECT, PROJECT));
        existing.add(assignment);
        when(roles.forScope(TENANT, RoleScopeType.PROJECT, PROJECT)).thenReturn(existing);
        UserDocument user = new UserDocument();
        user.setTenantId(TENANT);
        user.setName(name);
        user.setServiceAccount(serviceAccount);
        user.setLastLoginAt(lastLogin);
        user.setCreatedAt(createdAt);
        when(users.findByTenantAndName(TENANT, name)).thenReturn(Optional.of(user));
    }

    private Assessment assess() {
        return detector.assess(project(ProjectStatus.RUNNING), DEFAULT_DAYS, NOW);
    }

    @Test
    void aUserWhoWasAway31Days_makesTheProjectStale() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(400)));

        Assessment a = assess();

        assertThat(a.verdict()).isEqualTo(Verdict.STALE);
        assertThat(a.stale()).isTrue();
        assertThat(a.lastSeen()).isEqualTo(NOW.minus(Duration.ofDays(31)));
    }

    @Test
    void aUserWhoWasHere29DaysAgo_keepsItActive() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(29)), NOW.minus(Duration.ofDays(400)));

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void oneActiveMemberIsEnough() {
        givenMember("old", false, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(400)));
        givenMember("new", false, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(50)));

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void anAccountWithoutARecordedLogin_standsInWithItsCreationDate() {
        // The field is new: nobody has a value yet, and the first scan must not stop everything.
        givenMember("mike", false, null, NOW.minus(Duration.ofDays(3)));

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void anAccountWithNeitherDate_countsAsJustSeen() {
        givenMember("mike", false, null, null);

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void sessionActivityCountsAsPresence() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(400)));
        SessionDocument session = new SessionDocument();
        session.setLastActivityAt(NOW.minus(Duration.ofDays(5)));
        when(sessions.listForProject(TENANT, PROJECT)).thenReturn(List.of(session));

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void aConnectedClient_keepsTheProjectInUse() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(400)));
        SessionDocument session = new SessionDocument();
        session.setBoundConnectionId("editor-1");
        session.setLastActivityAt(NOW.minus(Duration.ofDays(200)));
        when(sessions.listForProject(TENANT, PROJECT)).thenReturn(List.of(session));

        assertThat(assess().verdict()).isEqualTo(Verdict.IN_USE);
    }

    @Test
    void aProcessInTheMiddleOfATurn_keepsTheProjectInUse() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(400)));
        ThinkProcessDocument busy = new ThinkProcessDocument();
        busy.setStatus(ThinkProcessStatus.RUNNING);
        when(processes.findByProject(eq(TENANT), eq(PROJECT), anyInt())).thenReturn(List.of(busy));

        assertThat(assess().verdict()).isEqualTo(Verdict.IN_USE);
    }

    @Test
    void aWaitingProcess_doesNotKeepItAwake() {
        // A Trillian loop sits idle between wake-ups; that is exactly what goes down with the project.
        givenMember("mike", false, NOW.minus(Duration.ofDays(200)), NOW.minus(Duration.ofDays(400)));
        ThinkProcessDocument waiting = new ThinkProcessDocument();
        waiting.setStatus(ThinkProcessStatus.IDLE);
        when(processes.findByProject(eq(TENANT), eq(PROJECT), anyInt())).thenReturn(List.of(waiting));

        assertThat(assess().verdict()).isEqualTo(Verdict.STALE);
    }

    @Test
    void serviceAccountsAreNotPeopleToWaitFor() {
        givenMember("_vance-bot", true, NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(400)));

        Assessment a = assess();

        assertThat(a.verdict())
                .as("a project nobody human holds a role in is reported, never stopped")
                .isEqualTo(Verdict.NO_HUMAN_USER);
    }

    @Test
    void aTeamInBetween_isNotAPerson() {
        RoleAssignmentDocument team = new RoleAssignmentDocument();
        team.setSubjectType(RoleSubjectType.TEAM);
        team.setSubjectId("devs");
        when(roles.forScope(TENANT, RoleScopeType.PROJECT, PROJECT)).thenReturn(List.of(team));

        assertThat(assess().verdict()).isEqualTo(Verdict.NO_HUMAN_USER);
    }

    @Test
    void theSettingOverridesTheDefault() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(400)));
        when(settings.getStringValueCascade(TENANT, PROJECT, null, ProjectIdleDetector.KEY_AFTER_DAYS))
                .thenReturn("90");

        assertThat(assess().verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void zeroTakesTheProjectOutOfThePolicy() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(900)), NOW.minus(Duration.ofDays(900)));
        when(settings.getStringValueCascade(TENANT, PROJECT, null, ProjectIdleDetector.KEY_AFTER_DAYS))
                .thenReturn("0");

        assertThat(assess().verdict()).isEqualTo(Verdict.DISABLED);
    }

    @Test
    void aSettingThatIsNotANumber_fallsBackToTheDefault() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(400)));
        when(settings.getStringValueCascade(any(), any(), any(), eq(ProjectIdleDetector.KEY_AFTER_DAYS)))
                .thenReturn("soon");

        assertThat(assess().verdict()).isEqualTo(Verdict.STALE);
    }

    @Test
    void aDefaultOfZero_meansNoPolicyUnlessASettingSaysOtherwise() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(900)), NOW.minus(Duration.ofDays(900)));

        Assessment a = detector.assess(project(ProjectStatus.RUNNING), 0, NOW);

        assertThat(a.verdict()).isEqualTo(Verdict.DISABLED);
    }

    @Test
    void stoppedAndArchivedProjectsAreNotAssessed() {
        for (ProjectStatus status : List.of(ProjectStatus.IDLE, ProjectStatus.SUSPENDED, ProjectStatus.CLOSED)) {
            assertThat(detector.assess(project(status), DEFAULT_DAYS, NOW).verdict())
                    .as("%s", status)
                    .isEqualTo(Verdict.NOT_APPLICABLE);
        }
    }

    @Test
    void aProjectThatWasNeverBrought_isAssessedLikeARunningOne() {
        givenMember("mike", false, NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(400)));

        assertThat(detector.assess(project(ProjectStatus.INIT), DEFAULT_DAYS, NOW)
                        .verdict())
                .isEqualTo(Verdict.STALE);
    }

    // ─── a user's hub belongs to the user it is named after ──────────────────

    private ProjectDocument hub(String login) {
        return ProjectDocument.builder()
                .tenantId(TENANT)
                .name("_user_" + login)
                .status(ProjectStatus.RUNNING)
                .build();
    }

    private void givenUser(String name, boolean serviceAccount, Instant lastLogin) {
        UserDocument user = new UserDocument();
        user.setTenantId(TENANT);
        user.setName(name);
        user.setServiceAccount(serviceAccount);
        user.setLastLoginAt(lastLogin);
        user.setCreatedAt(NOW.minus(Duration.ofDays(900)));
        when(users.findByTenantAndName(TENANT, name)).thenReturn(Optional.of(user));
    }

    @Test
    void aHubHasNoRoleAssignmentForItsOwner_theNameIsTheAssignment() {
        String hubName = "_user_mike";
        when(sessions.listForProject(TENANT, hubName)).thenReturn(List.of());
        when(processes.findByProject(eq(TENANT), eq(hubName), anyInt())).thenReturn(List.of());
        givenUser("mike", false, NOW.minus(Duration.ofDays(60)));

        assertThat(detector.assess(hub("mike"), DEFAULT_DAYS, NOW).verdict())
                .as("the hub of a user who stayed away goes idle with the Trillian in it")
                .isEqualTo(Verdict.STALE);
    }

    @Test
    void aHubOfAUserWhoWasHereRecently_staysActive() {
        String hubName = "_user_mike";
        when(sessions.listForProject(TENANT, hubName)).thenReturn(List.of());
        when(processes.findByProject(eq(TENANT), eq(hubName), anyInt())).thenReturn(List.of());
        givenUser("mike", false, NOW.minus(Duration.ofDays(2)));

        assertThat(detector.assess(hub("mike"), DEFAULT_DAYS, NOW).verdict()).isEqualTo(Verdict.ACTIVE);
    }

    @Test
    void theHubOfAServiceAccount_hasNobodyToWaitFor() {
        String hubName = "_user__bot";
        when(sessions.listForProject(TENANT, hubName)).thenReturn(List.of());
        when(processes.findByProject(eq(TENANT), eq(hubName), anyInt())).thenReturn(List.of());
        givenUser("_bot", true, NOW.minus(Duration.ofDays(900)));

        assertThat(detector.assess(hub("_bot"), DEFAULT_DAYS, NOW).verdict()).isEqualTo(Verdict.NO_HUMAN_USER);
    }

    // ─── in use right now — also the question a rebalance asks ───────────────

    @Test
    void inUse_aConnectedClient() {
        SessionDocument session = new SessionDocument();
        session.setBoundConnectionId("editor-1");
        when(sessions.listForProject(TENANT, PROJECT)).thenReturn(List.of(session));

        assertThat(detector.inUse(TENANT, PROJECT)).isTrue();
    }

    @Test
    void inUse_aProcessInTheMiddleOfATurn() {
        ThinkProcessDocument busy = new ThinkProcessDocument();
        busy.setStatus(ThinkProcessStatus.RUNNING);
        when(processes.findByProject(eq(TENANT), eq(PROJECT), anyInt())).thenReturn(List.of(busy));

        assertThat(detector.inUse(TENANT, PROJECT)).isTrue();
    }

    @Test
    void inUse_nothingGoingOn_isNotInUse() {
        assertThat(detector.inUse(TENANT, PROJECT)).isFalse();
    }
}
