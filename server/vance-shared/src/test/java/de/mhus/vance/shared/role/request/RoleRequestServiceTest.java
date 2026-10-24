package de.mhus.vance.shared.role.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleRequestServiceTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "_trillian-03725";

    @Mock
    RoleRequestRepository repository;

    @InjectMocks
    RoleRequestService service;

    @Test
    void request_firstTime_createsPendingRequest() {
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleRequestDocument created = request("please let me work there");

        assertThat(created.getStatus()).isEqualTo(RoleRequestStatus.PENDING);
        assertThat(created.getProjectRole()).isEqualTo(ProjectRole.MEMBER);
        assertThat(created.getReason()).isEqualTo("please let me work there");
    }

    @Test
    void request_identicalPendingOne_isReusedNotDuplicated() {
        RoleRequestDocument existing = pending("req-1", "first wording");
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleRequestDocument result = request("second wording");

        // Same request — an agent in a loop must not be able to flood an
        // inbox with the same ask.
        assertThat(result.getId()).isEqualTo("req-1");
        ArgumentCaptor<RoleRequestDocument> saved = ArgumentCaptor.forClass(RoleRequestDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("second wording");
    }

    @Test
    void request_differentRole_isNotTreatedAsDuplicate() {
        RoleRequestDocument existing = pending("req-1", "x");
        existing.setProjectRole(ProjectRole.READER);
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleRequestDocument result = request("x");

        // Asking for MEMBER when READER is pending is a different ask.
        assertThat(result.getId()).isNull();
        assertThat(result.getProjectRole()).isEqualTo(ProjectRole.MEMBER);
    }

    @Test
    void request_revokeVsGrant_areNotConfused() {
        RoleRequestDocument existingRevoke = pending("req-1", "x");
        existingRevoke.setOperation(RoleRequestOperation.REVOKE);
        existingRevoke.setProjectRole(null);
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(existingRevoke));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoleRequestDocument result = request("x");

        assertThat(result.getId()).isNull();
        assertThat(result.getOperation()).isEqualTo(RoleRequestOperation.GRANT);
    }

    @Test
    void markApproved_onPendingRequest_transitionsAndRecordsDecider() {
        when(repository.findById("req-1")).thenReturn(Optional.of(pending("req-1", "x")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<RoleRequestDocument> result = service.markApproved("req-1", "marvin.acme");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(RoleRequestStatus.APPROVED);
        assertThat(result.get().getDecidedBy()).isEqualTo("marvin.acme");
        assertThat(result.get().getDecidedAt()).isNotNull();
    }

    @Test
    void markApproved_onAlreadyDecidedRequest_isNoop() {
        RoleRequestDocument decided = pending("req-1", "x");
        decided.setStatus(RoleRequestStatus.REJECTED);
        when(repository.findById("req-1")).thenReturn(Optional.of(decided));

        Optional<RoleRequestDocument> result = service.markApproved("req-1", "marvin.acme");

        // Terminal states never re-open — a repeated effect cannot
        // double-apply the mutation.
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(RoleRequestStatus.REJECTED);
        verify(repository, never()).save(any());
    }

    @Test
    void markFailed_recordsWhy() {
        when(repository.findById("req-1")).thenReturn(Optional.of(pending("req-1", "x")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<RoleRequestDocument> result = service.markFailed("req-1", "marvin.acme", "responder lacks authority");

        assertThat(result.get().getStatus()).isEqualTo(RoleRequestStatus.FAILED);
        assertThat(result.get().getFailureReason()).isEqualTo("responder lacks authority");
    }

    @Test
    void expireForSubject_expiresEveryPendingRequestOfThatSubject() {
        when(repository.findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
                        TENANT, RoleSubjectType.USER, SUBJECT, RoleRequestStatus.PENDING))
                .thenReturn(List.of(pending("req-1", "a"), pending("req-2", "b")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int expired = service.expireForSubject(TENANT, RoleSubjectType.USER, SUBJECT);

        assertThat(expired).isEqualTo(2);
        ArgumentCaptor<RoleRequestDocument> saved = ArgumentCaptor.forClass(RoleRequestDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(d -> d.getStatus() == RoleRequestStatus.EXPIRED);
    }

    @Test
    void expireStale_usesTheSevenDayTtl() {
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        when(repository.findByStatusAndCreatedAtBefore(RoleRequestStatus.PENDING, now.minus(7, ChronoUnit.DAYS)))
                .thenReturn(List.of(pending("req-1", "a")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.expireStale(now)).isEqualTo(1);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private RoleRequestDocument request(String reason) {
        return service.request(
                TENANT,
                RoleRequestOperation.GRANT,
                RoleRequestService.Target.projectGrant("test1", RoleSubjectType.USER, SUBJECT, ProjectRole.MEMBER),
                reason,
                "road.runner",
                "proc-1");
    }

    private static RoleRequestDocument pending(String id, String reason) {
        return RoleRequestDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .operation(RoleRequestOperation.GRANT)
                .scopeType(RoleScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(RoleSubjectType.USER)
                .subjectId(SUBJECT)
                .projectRole(ProjectRole.MEMBER)
                .reason(reason)
                .requestedBy("road.runner")
                .status(RoleRequestStatus.PENDING)
                .build();
    }
}
