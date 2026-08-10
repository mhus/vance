package de.mhus.vance.simpleauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class PermissionRequestServiceTest {

    private static final String TENANT = "acme";
    private static final String SUBJECT = "_trillian-03725";

    @Mock
    PermissionRequestRepository repository;

    @InjectMocks
    PermissionRequestService service;

    @Test
    void request_firstTime_createsPendingRequest() {
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionRequestDocument created = request("please let me work there");

        assertThat(created.getStatus()).isEqualTo(PermissionRequestStatus.PENDING);
        assertThat(created.getRole()).isEqualTo(GrantRole.WRITER);
        assertThat(created.getReason()).isEqualTo("please let me work there");
    }

    @Test
    void request_identicalPendingOne_isReusedNotDuplicated() {
        PermissionRequestDocument existing = pending("req-1", "first wording");
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionRequestDocument result = request("second wording");

        // Same request — an agent in a loop must not be able to flood an
        // inbox with the same ask.
        assertThat(result.getId()).isEqualTo("req-1");
        ArgumentCaptor<PermissionRequestDocument> saved =
                ArgumentCaptor.forClass(PermissionRequestDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("second wording");
    }

    @Test
    void request_differentRole_isNotTreatedAsDuplicate() {
        PermissionRequestDocument existing = pending("req-1", "x");
        existing.setRole(GrantRole.READER);
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionRequestDocument result = request("x");

        // Asking for WRITER when READER is pending is a different ask.
        assertThat(result.getId()).isNull();
        assertThat(result.getRole()).isEqualTo(GrantRole.WRITER);
    }

    @Test
    void request_revokeVsGrant_areNotConfused() {
        PermissionRequestDocument existingRevoke = pending("req-1", "x");
        existingRevoke.setOperation(PermissionRequestOperation.REVOKE);
        existingRevoke.setRole(null);
        when(repository.findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(existingRevoke));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionRequestDocument result = request("x");

        assertThat(result.getId()).isNull();
        assertThat(result.getOperation()).isEqualTo(PermissionRequestOperation.GRANT);
    }

    @Test
    void markApproved_onPendingRequest_transitionsAndRecordsDecider() {
        when(repository.findById("req-1")).thenReturn(Optional.of(pending("req-1", "x")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<PermissionRequestDocument> result = service.markApproved("req-1", "marvin.acme");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(PermissionRequestStatus.APPROVED);
        assertThat(result.get().getDecidedBy()).isEqualTo("marvin.acme");
        assertThat(result.get().getDecidedAt()).isNotNull();
    }

    @Test
    void markApproved_onAlreadyDecidedRequest_isNoop() {
        PermissionRequestDocument decided = pending("req-1", "x");
        decided.setStatus(PermissionRequestStatus.REJECTED);
        when(repository.findById("req-1")).thenReturn(Optional.of(decided));

        Optional<PermissionRequestDocument> result = service.markApproved("req-1", "marvin.acme");

        // Terminal states never re-open — a repeated effect cannot
        // double-apply the mutation.
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(PermissionRequestStatus.REJECTED);
        verify(repository, never()).save(any());
    }

    @Test
    void markFailed_recordsWhy() {
        when(repository.findById("req-1")).thenReturn(Optional.of(pending("req-1", "x")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<PermissionRequestDocument> result =
                service.markFailed("req-1", "marvin.acme", "responder lacks ADMIN");

        assertThat(result.get().getStatus()).isEqualTo(PermissionRequestStatus.FAILED);
        assertThat(result.get().getFailureReason()).isEqualTo("responder lacks ADMIN");
    }

    @Test
    void expireForSubject_expiresEveryPendingRequestOfThatSubject() {
        when(repository.findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
                TENANT, GrantSubjectType.USER, SUBJECT, PermissionRequestStatus.PENDING))
                .thenReturn(List.of(pending("req-1", "a"), pending("req-2", "b")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int expired = service.expireForSubject(TENANT, GrantSubjectType.USER, SUBJECT);

        assertThat(expired).isEqualTo(2);
        ArgumentCaptor<PermissionRequestDocument> saved =
                ArgumentCaptor.forClass(PermissionRequestDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allMatch(d -> d.getStatus() == PermissionRequestStatus.EXPIRED);
    }

    @Test
    void expireStale_usesTheSevenDayTtl() {
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        when(repository.findByStatusAndCreatedAtBefore(
                PermissionRequestStatus.PENDING, now.minus(7, ChronoUnit.DAYS)))
                .thenReturn(List.of(pending("req-1", "a")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.expireStale(now)).isEqualTo(1);
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private PermissionRequestDocument request(String reason) {
        return service.request(TENANT, PermissionRequestOperation.GRANT,
                GrantScopeType.PROJECT, "test1", GrantSubjectType.USER, SUBJECT,
                GrantRole.WRITER, reason, "road.runner", "proc-1");
    }

    private static PermissionRequestDocument pending(String id, String reason) {
        return PermissionRequestDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .operation(PermissionRequestOperation.GRANT)
                .scopeType(GrantScopeType.PROJECT)
                .scopeId("test1")
                .subjectType(GrantSubjectType.USER)
                .subjectId(SUBJECT)
                .role(GrantRole.WRITER)
                .reason(reason)
                .requestedBy("road.runner")
                .status(PermissionRequestStatus.PENDING)
                .build();
    }
}
