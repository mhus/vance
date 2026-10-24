package de.mhus.vance.brain.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.role.RoleAdministrationService;
import de.mhus.vance.shared.role.RoleAssignmentException;
import de.mhus.vance.shared.role.request.RoleRequestDocument;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.role.request.RoleRequestStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The effect is the only path from an agent-raised intent to an actual role
 * change. These tests pin that it walks that path only on a human yes, as the
 * responder, through the assignment rules.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleRequestEffectTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "_trillian-03725";
    private static final String DECIDER = "marvin.acme";
    private static final SecurityContext RESPONDER = SecurityContext.user(DECIDER, TENANT, List.of());

    @Mock
    RoleRequestService requests;

    @Mock
    RoleAdministrationService administration;

    @Mock
    SecurityContextFactory contextFactory;

    @Mock
    ProcessEventEmitter eventEmitter;

    @InjectMocks
    RoleRequestEffect effect;

    @BeforeEach
    void setUp() {
        when(contextFactory.forToolSubject(TENANT, DECIDER)).thenReturn(RESPONDER);
    }

    @Test
    void approvedProjectGrant_assignsAsTheResponderAndMarksApproved() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onApproved(item("req-1"), answer());

        verify(administration)
                .assignProjectRole(RESPONDER, TENANT, "test1", RoleSubjectType.USER, SUBJECT, ProjectRole.MEMBER);
        verify(requests).markApproved("req-1", DECIDER);
    }

    @Test
    void approvedProjectRevoke_removesTheMembership() {
        RoleRequestDocument revoke = pending();
        revoke.setOperation(RoleRequestOperation.REVOKE);
        revoke.setProjectRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(revoke));

        effect.onApproved(item("req-1"), answer());

        verify(administration).removeProjectRole(RESPONDER, TENANT, "test1", RoleSubjectType.USER, SUBJECT);
    }

    @Test
    void approvedTenantGrant_assignsTheTenantRole() {
        RoleRequestDocument tenantWide = pending();
        tenantWide.setScopeType(RoleScopeType.TENANT);
        tenantWide.setScopeId(TENANT);
        tenantWide.setProjectRole(null);
        tenantWide.setTenantRole(TenantRole.CREATOR);
        when(requests.findById("req-1")).thenReturn(Optional.of(tenantWide));

        effect.onApproved(item("req-1"), answer());

        verify(administration).assignTenantRole(RESPONDER, TENANT, SUBJECT, TenantRole.CREATOR);
    }

    @Test
    void approvedTenantRevoke_fallsBackToUser() {
        RoleRequestDocument tenantRevoke = pending();
        tenantRevoke.setScopeType(RoleScopeType.TENANT);
        tenantRevoke.setScopeId(TENANT);
        tenantRevoke.setOperation(RoleRequestOperation.REVOKE);
        tenantRevoke.setProjectRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(tenantRevoke));

        effect.onApproved(item("req-1"), answer());

        verify(administration).assignTenantRole(RESPONDER, TENANT, SUBJECT, TenantRole.USER);
    }

    @Test
    void responderWithoutAuthority_changesNothingAndFailsTheRequest() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));
        doThrow(new PermissionDeniedException(RESPONDER, new Resource.Project(TENANT, "test1"), Action.ADMIN))
                .when(administration)
                .assignProjectRole(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> effect.onApproved(item("req-1"), answer()))
                .isInstanceOf(PermissionDeniedException.class);

        // Rights are re-checked at decision time, not trusted from when the
        // request was raised.
        verify(requests).markFailed(eq("req-1"), eq(DECIDER), any());
        verify(requests, never()).markApproved(any(), any());
    }

    @Test
    void ruleViolation_failsTheRequestWithTheRule() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));
        doThrow(new RoleAssignmentException(RoleAssignmentException.Kind.REFUSED, "above your own role"))
                .when(administration)
                .assignProjectRole(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> effect.onApproved(item("req-1"), answer()))
                .isInstanceOf(RoleAssignmentException.class);
        verify(requests).markFailed("req-1", DECIDER, "above your own role");
    }

    @Test
    void rejection_changesNothingAndMarksRejected() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onRejected(item("req-1"), answer());

        verify(administration, never()).assignProjectRole(any(), any(), any(), any(), any(), any());
        verify(administration, never()).removeProjectRole(any(), any(), any(), any(), any());
        verify(requests).markRejected("req-1", DECIDER);
    }

    @Test
    void alreadyDecidedRequest_isLeftAlone() {
        RoleRequestDocument decided = pending();
        decided.setStatus(RoleRequestStatus.APPROVED);
        when(requests.findById("req-1")).thenReturn(Optional.of(decided));

        effect.onApproved(item("req-1"), answer());

        verify(administration, never()).assignProjectRole(any(), any(), any(), any(), any(), any());
        verify(requests, never()).markApproved(any(), any());
    }

    @Test
    void vanishedRequest_isNotAnError() {
        when(requests.findById("req-1")).thenReturn(Optional.empty());

        assertThatCode(() -> effect.onApproved(item("req-1"), answer())).doesNotThrowAnyException();
    }

    @Test
    void grantWithoutRole_failsInsteadOfGuessing() {
        RoleRequestDocument broken = pending();
        broken.setProjectRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> effect.onApproved(item("req-1"), answer())).isInstanceOf(NullPointerException.class);
        verify(requests).markFailed(eq("req-1"), eq(DECIDER), any());
    }

    @Test
    void describe_reportsTheFactsFromTheRequestNotTheItemBody() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        var description = effect.describe(item("req-1"));

        assertThat(description).isPresent();
        assertThat(description.get().status()).isEqualTo("PENDING");
        assertThat(description.get().facts())
                .extracting(f -> f.label() + "=" + f.value())
                .contains(
                        "Operation=Grant access",
                        "Scope=PROJECT 'test1'",
                        "Subject=USER '" + SUBJECT + "'",
                        "Role=MEMBER",
                        "Requested by=road.runner");
    }

    @Test
    void describe_ofARevoke_saysRemoveAndOmitsRole() {
        RoleRequestDocument revoke = pending();
        revoke.setOperation(RoleRequestOperation.REVOKE);
        revoke.setProjectRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(revoke));

        var facts = effect.describe(item("req-1")).orElseThrow().facts();

        assertThat(facts).extracting(f -> f.label()).doesNotContain("Role");
        assertThat(facts).extracting(f -> f.value()).contains("Remove access");
    }

    @Test
    void approval_tellsTheRequestingProcessItMayProceed() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onApproved(item("req-1"), answer());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(eventEmitter)
                .notifyParent(
                        eq("proc-1"), eq("proc-1"), eq(ProcessEventType.SUMMARY), summary.capture(), any(), isNull());
        assertThat(summary.getValue()).contains("approved").contains("test1");
    }

    @Test
    void failingNotification_doesNotUndoTheChange() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));
        when(eventEmitter.notifyParent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("peer pod unreachable"));

        assertThatCode(() -> effect.onApproved(item("req-1"), answer())).doesNotThrowAnyException();
        verify(requests).markApproved("req-1", DECIDER);
    }

    private static MaximegalonDocument item(String requestId) {
        return MaximegalonDocument.builder()
                .id("item-1")
                .tenantId(TENANT)
                .type(MaximegalonType.APPROVAL)
                .effectType(RoleRequestEffect.EFFECT_TYPE)
                .effectRef(requestId)
                .requiresAction(true)
                .build();
    }

    private static AnswerPayload answer() {
        return AnswerPayload.builder()
                .outcome(AnswerOutcome.DECIDED)
                .value(Map.of("approved", true))
                .answeredBy(DECIDER)
                .build();
    }

    private static RoleRequestDocument pending() {
        return RoleRequestDocument.builder()
                .id("req-1")
                .tenantId(TENANT)
                .operation(RoleRequestOperation.GRANT)
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(RoleSubjectType.USER)
                .subjectId(SUBJECT)
                .projectRole(ProjectRole.MEMBER)
                .reason("worker needs access")
                .requestedBy("road.runner")
                .requestedByProcessId("proc-1")
                .status(RoleRequestStatus.PENDING)
                .build();
    }
}
