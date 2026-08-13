package de.mhus.vance.simpleauth.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.simpleauth.GrantRole;
import de.mhus.vance.simpleauth.GrantScopeType;
import de.mhus.vance.simpleauth.GrantSubjectType;
import de.mhus.vance.simpleauth.PermissionGrantService;
import de.mhus.vance.simpleauth.PermissionRequestDocument;
import de.mhus.vance.simpleauth.PermissionRequestOperation;
import de.mhus.vance.simpleauth.PermissionRequestService;
import de.mhus.vance.simpleauth.PermissionRequestStatus;
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
 * The effect is the only path from an LLM-raised intent to an actual
 * grant change. These tests pin that it walks that path only on a human
 * yes from someone who still holds ADMIN.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionRequestEffectTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "_trillian-03725";
    private static final String DECIDER = "marvin.acme";

    @Mock
    PermissionRequestService requests;
    @Mock
    PermissionGrantService grants;
    @Mock
    PermissionService permissionService;
    @Mock
    SecurityContextFactory contextFactory;
    @Mock
    ProcessEventEmitter eventEmitter;

    @InjectMocks
    PermissionRequestEffect effect;

    @BeforeEach
    void setUp() {
        when(contextFactory.forToolSubject(any(), any()))
                .thenReturn(SecurityContext.user(DECIDER, TENANT, List.of()));
    }

    @Test
    void approvedGrant_writesTheGrantAndMarksApproved() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onApproved(item("req-1"), answer());

        verify(grants).set(TENANT, GrantScopeType.PROJECT, "test1",
                GrantSubjectType.USER, SUBJECT, GrantRole.WRITER, "inbox-approval");
        verify(requests).markApproved("req-1", DECIDER);
    }

    @Test
    void approvedRevoke_removesTheGrant() {
        PermissionRequestDocument revoke = pending();
        revoke.setOperation(PermissionRequestOperation.REVOKE);
        revoke.setRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(revoke));

        effect.onApproved(item("req-1"), answer());

        verify(grants).remove(TENANT, GrantScopeType.PROJECT, "test1",
                GrantSubjectType.USER, SUBJECT);
        verify(requests).markApproved("req-1", DECIDER);
    }

    @Test
    void responderWithoutAdmin_changesNothingAndFailsTheRequest() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));
        doThrow(new SecurityException("denied"))
                .when(permissionService).enforce(any(), any(), eq(Action.ADMIN));

        assertThatThrownBy(() -> effect.onApproved(item("req-1"), answer()))
                .isInstanceOf(SecurityException.class);

        // Rights are re-checked at decision time, not trusted from when
        // the request was raised.
        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
        verify(requests).markFailed(eq("req-1"), eq(DECIDER), any());
        verify(requests, never()).markApproved(any(), any());
    }

    @Test
    void enforceRunsAgainstTheTargetScope_notTheRespondersOwn() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onApproved(item("req-1"), answer());

        verify(permissionService).enforce(any(),
                eq(new Resource.Project(TENANT, "test1")), eq(Action.ADMIN));
    }

    @Test
    void tenantScopedRequest_enforcesOnTheTenant() {
        PermissionRequestDocument tenantWide = pending();
        tenantWide.setScopeType(GrantScopeType.TENANT);
        tenantWide.setScopeId(TENANT);
        when(requests.findById("req-1")).thenReturn(Optional.of(tenantWide));

        effect.onApproved(item("req-1"), answer());

        verify(permissionService).enforce(any(),
                eq(new Resource.Tenant(TENANT)), eq(Action.ADMIN));
    }

    @Test
    void rejection_changesNothingAndMarksRejected() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onRejected(item("req-1"), answer());

        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
        verify(grants, never()).remove(any(), any(), any(), any(), any());
        verify(requests).markRejected("req-1", DECIDER);
    }

    @Test
    void rejection_doesNotRequireAdmin() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onRejected(item("req-1"), answer());

        // Declining changes no rights — whoever was routed the item,
        // delegate included, may say no.
        verify(permissionService, never()).enforce(any(), any(), any());
    }

    @Test
    void alreadyDecidedRequest_isLeftAlone() {
        PermissionRequestDocument decided = pending();
        decided.setStatus(PermissionRequestStatus.APPROVED);
        when(requests.findById("req-1")).thenReturn(Optional.of(decided));

        effect.onApproved(item("req-1"), answer());

        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
        verify(requests, never()).markApproved(any(), any());
    }

    @Test
    void vanishedRequest_isNotAnError() {
        when(requests.findById("req-1")).thenReturn(Optional.empty());

        // A stale item is not a failure the responder should see as one.
        assertThatCode(() -> effect.onApproved(item("req-1"), answer()))
                .doesNotThrowAnyException();
        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void itemWithoutEffectRef_changesNothing() {
        InboxItemDocument orphan = item("req-1");
        orphan.setEffectRef(null);

        assertThatCode(() -> effect.onApproved(orphan, answer())).doesNotThrowAnyException();
        verify(grants, never()).set(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void grantRequestWithoutRole_failsInsteadOfGuessing() {
        PermissionRequestDocument broken = pending();
        broken.setRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> effect.onApproved(item("req-1"), answer()))
                .isInstanceOf(IllegalStateException.class);
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
                .contains("Operation=Grant access",
                        "Scope=PROJECT 'test1'",
                        "Subject=USER '" + SUBJECT + "'",
                        "Role=WRITER",
                        "Requested by=road.runner");
    }

    @Test
    void describe_ofARevoke_saysRemoveAndOmitsRole() {
        PermissionRequestDocument revoke = pending();
        revoke.setOperation(PermissionRequestOperation.REVOKE);
        revoke.setRole(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(revoke));

        var facts = effect.describe(item("req-1")).orElseThrow().facts();

        assertThat(facts).extracting(f -> f.label()).doesNotContain("Role");
        assertThat(facts).extracting(f -> f.value()).contains("Remove access");
    }

    @Test
    void describe_ofAFailedRequest_carriesTheReason() {
        PermissionRequestDocument failed = pending();
        failed.setStatus(PermissionRequestStatus.FAILED);
        failed.setFailureReason("responder lacks ADMIN on the target scope");
        failed.setDecidedBy(DECIDER);
        when(requests.findById("req-1")).thenReturn(Optional.of(failed));

        var description = effect.describe(item("req-1")).orElseThrow();

        // "Approved but nothing happened" has to be visible where the
        // decision was made.
        assertThat(description.status()).isEqualTo("FAILED");
        assertThat(description.statusDetail()).contains("lacks ADMIN");
    }

    @Test
    void describe_withoutRefOrRequest_isEmpty() {
        InboxItemDocument orphan = item("req-1");
        orphan.setEffectRef(null);
        assertThat(effect.describe(orphan)).isEmpty();

        when(requests.findById("req-1")).thenReturn(Optional.empty());
        assertThat(effect.describe(item("req-1"))).isEmpty();
    }

    @Test
    void approval_tellsTheRequestingProcessItMayProceed() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onApproved(item("req-1"), answer());

        // The moment of the decision is known exactly here, so the waiting
        // agent is pushed to rather than left to poll — every poll would
        // cost an LLM turn to discover "not yet".
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(eventEmitter).notifyParent(eq("proc-1"), eq("proc-1"),
                eq(ProcessEventType.SUMMARY), summary.capture(), any(), isNull());
        assertThat(summary.getValue()).contains("approved").contains("test1");
    }

    @Test
    void rejection_tellsTheRequesterNotToRetry() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));

        effect.onRejected(item("req-1"), answer());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(eventEmitter).notifyParent(any(), any(), any(), summary.capture(), any(), any());
        assertThat(summary.getValue()).contains("declined").contains("Do not retry");
    }

    @Test
    void requestWithoutAnOriginProcess_notifiesNobody() {
        PermissionRequestDocument orphan = pending();
        orphan.setRequestedByProcessId(null);
        when(requests.findById("req-1")).thenReturn(Optional.of(orphan));

        effect.onApproved(item("req-1"), answer());

        verify(eventEmitter, never()).notifyParent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failingNotification_doesNotUndoTheGrant() {
        when(requests.findById("req-1")).thenReturn(Optional.of(pending()));
        when(eventEmitter.notifyParent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("peer pod unreachable"));

        // A lost notification costs a wait, not the grant.
        assertThatCode(() -> effect.onApproved(item("req-1"), answer()))
                .doesNotThrowAnyException();
        verify(grants).set(any(), any(), any(), any(), any(), any(), any());
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static InboxItemDocument item(String requestId) {
        return InboxItemDocument.builder()
                .id("item-1")
                .tenantId(TENANT)
                .type(InboxItemType.APPROVAL)
                .effectType(PermissionRequestEffect.EFFECT_TYPE)
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

    private static PermissionRequestDocument pending() {
        return PermissionRequestDocument.builder()
                .id("req-1")
                .tenantId(TENANT)
                .operation(PermissionRequestOperation.GRANT)
                .scopeType(GrantScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(GrantSubjectType.USER)
                .subjectId(SUBJECT)
                .role(GrantRole.WRITER)
                .reason("worker needs access")
                .requestedBy("road.runner")
                .requestedByProcessId("proc-1")
                .status(PermissionRequestStatus.PENDING)
                .build();
    }
}
