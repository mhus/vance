package de.mhus.vance.shared.role.request;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code role_requests} collection: pending role changes that an
 * agent asked for and only a human can release.
 *
 * <p>The service never touches role assignments — writing the role is the job
 * of the inbox effect, which runs after a human decision. Keeping the two apart
 * is what makes "asking" structurally unable to become "doing".
 *
 * <p>See {@code planning/permission-request-inbox.md} §5, §11.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleRequestService {

    /** How long a request may wait for a decision before it lapses (§11.2). */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final RoleRequestRepository repository;

    /** What is being asked for: one scope, one subject, and the role for a grant. */
    public record Target(
            RoleScopeType scopeType,
            String scopeId,
            RoleSubjectType subjectType,
            String subjectId,
            @Nullable TenantRole tenantRole,
            @Nullable ProjectRole projectRole) {

        /** Grant of a project membership. */
        public static Target projectGrant(
                String project, RoleSubjectType subjectType, String subjectId, ProjectRole role) {
            return new Target(RoleScopeType.PROJECT, project, subjectType, subjectId, null, role);
        }

        /** Grant of a tenant role — users only. */
        public static Target tenantGrant(String tenantId, String username, TenantRole role) {
            return new Target(RoleScopeType.TENANT, tenantId, RoleSubjectType.USER, username, role, null);
        }

        /** Removal of whatever the subject holds on the scope. */
        public static Target revoke(
                RoleScopeType scopeType, String scopeId, RoleSubjectType subjectType, String subjectId) {
            return new Target(scopeType, scopeId, subjectType, subjectId, null, null);
        }
    }

    /**
     * Creates a pending request, or returns the existing one when an identical
     * request is already waiting.
     *
     * <p>Reuse is not just tidiness: it is the brake against an agent in a loop
     * filling somebody's inbox with the same ask. A repeated request refreshes
     * the reason (the newer wording is likely the better one) but keeps the
     * original item, decider routing and creation time.
     */
    public RoleRequestDocument request(
            String tenantId,
            RoleRequestOperation operation,
            Target target,
            @Nullable String reason,
            String requestedBy,
            @Nullable String requestedByProcessId) {

        Optional<RoleRequestDocument> duplicate = findPending(tenantId, operation, target);
        if (duplicate.isPresent()) {
            RoleRequestDocument existing = duplicate.get();
            if (reason != null && !reason.equals(existing.getReason())) {
                existing.setReason(reason);
                repository.save(existing);
            }
            log.info(
                    "role-request reused id='{}' tenant='{}' {} {}:{} for {}:{}",
                    existing.getId(),
                    tenantId,
                    operation,
                    target.scopeType(),
                    target.scopeId(),
                    target.subjectType(),
                    target.subjectId());
            return existing;
        }

        RoleRequestDocument doc = repository.save(RoleRequestDocument.builder()
                .tenantId(tenantId)
                .operation(operation)
                .scopeType(target.scopeType())
                .scopeId(target.scopeId())
                .subjectType(target.subjectType())
                .subjectId(target.subjectId())
                .tenantRole(operation == RoleRequestOperation.GRANT ? target.tenantRole() : null)
                .projectRole(operation == RoleRequestOperation.GRANT ? target.projectRole() : null)
                .reason(reason)
                .requestedBy(requestedBy)
                .requestedByProcessId(requestedByProcessId)
                .status(RoleRequestStatus.PENDING)
                .build());
        log.info(
                "role-request created id='{}' tenant='{}' {} {}:{} for {}:{} role={} by='{}'",
                doc.getId(),
                tenantId,
                operation,
                target.scopeType(),
                target.scopeId(),
                target.subjectType(),
                target.subjectId(),
                doc.roleName(),
                requestedBy);
        return doc;
    }

    /** Links the inbox item that carries this request to its decider. */
    public void attachInboxItem(String requestId, String inboxItemId) {
        repository.findById(requestId).ifPresent(doc -> {
            doc.setInboxItemId(inboxItemId);
            repository.save(doc);
        });
    }

    public Optional<RoleRequestDocument> findById(String requestId) {
        return repository.findById(requestId);
    }

    /**
     * Marks the request as carried out. Only a {@code PENDING} request can
     * transition — a second call is a no-op, so a duplicated effect can never
     * double-apply.
     */
    public Optional<RoleRequestDocument> markApproved(String requestId, String decidedBy) {
        return transition(requestId, RoleRequestStatus.APPROVED, decidedBy, null);
    }

    public Optional<RoleRequestDocument> markRejected(String requestId, String decidedBy) {
        return transition(requestId, RoleRequestStatus.REJECTED, decidedBy, null);
    }

    public Optional<RoleRequestDocument> markFailed(String requestId, String decidedBy, String failureReason) {
        return transition(requestId, RoleRequestStatus.FAILED, decidedBy, failureReason);
    }

    /**
     * Expires every pending request naming {@code subjectId}. Called when the
     * subject is deleted: an ephemeral service account can vanish long before
     * anyone decides, and a request left pending could otherwise be approved
     * onto a later account that reuses the name.
     *
     * @return how many were expired
     */
    public int expireForSubject(String tenantId, RoleSubjectType subjectType, String subjectId) {
        List<RoleRequestDocument> pending = repository.findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
                tenantId, subjectType, subjectId, RoleRequestStatus.PENDING);
        for (RoleRequestDocument doc : pending) {
            doc.setStatus(RoleRequestStatus.EXPIRED);
            doc.setDecidedAt(Instant.now());
            repository.save(doc);
        }
        if (!pending.isEmpty()) {
            log.info(
                    "Expired {} pending role-request(s) of {}:{} in tenant '{}'",
                    pending.size(),
                    subjectType,
                    subjectId,
                    tenantId);
        }
        return pending.size();
    }

    /** Expires requests that have waited longer than {@link #DEFAULT_TTL}. */
    public int expireStale(Instant now) {
        List<RoleRequestDocument> stale =
                repository.findByStatusAndCreatedAtBefore(RoleRequestStatus.PENDING, now.minus(DEFAULT_TTL));
        for (RoleRequestDocument doc : stale) {
            doc.setStatus(RoleRequestStatus.EXPIRED);
            doc.setDecidedAt(now);
            repository.save(doc);
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale role-request(s)", stale.size());
        }
        return stale.size();
    }

    private Optional<RoleRequestDocument> transition(
            String requestId, RoleRequestStatus target, String decidedBy, @Nullable String failureReason) {
        Optional<RoleRequestDocument> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RoleRequestDocument doc = found.get();
        if (doc.getStatus() != RoleRequestStatus.PENDING) {
            log.info("role-request '{}' already {} — {} ignored", requestId, doc.getStatus(), target);
            return Optional.of(doc);
        }
        doc.setStatus(target);
        doc.setDecidedBy(decidedBy);
        doc.setDecidedAt(Instant.now());
        doc.setFailureReason(failureReason);
        RoleRequestDocument saved = repository.save(doc);
        log.info(
                "role-request '{}' -> {} by='{}'{}",
                requestId,
                target,
                decidedBy,
                failureReason == null ? "" : " reason=" + failureReason);
        return Optional.of(saved);
    }

    private Optional<RoleRequestDocument> findPending(String tenantId, RoleRequestOperation operation, Target target) {
        return repository
                .findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        tenantId,
                        RoleRequestStatus.PENDING,
                        target.scopeType(),
                        target.scopeId(),
                        target.subjectType(),
                        target.subjectId())
                .stream()
                .filter(d -> d.getOperation() == operation
                        && (operation == RoleRequestOperation.REVOKE
                                || (Objects.equals(d.getTenantRole(), target.tenantRole())
                                        && Objects.equals(d.getProjectRole(), target.projectRole()))))
                .findFirst();
    }
}
