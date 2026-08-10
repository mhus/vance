package de.mhus.vance.simpleauth;

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
 * Owns the {@code permission_requests} collection: pending grant changes
 * that an LLM asked for and only a human can release.
 *
 * <p>The service never touches {@link PermissionGrantService} — writing
 * the grant is the job of the inbox effect, which runs after a human
 * decision. Keeping the two apart is what makes "asking" structurally
 * unable to become "doing".
 *
 * <p>See {@code planning/permission-request-inbox.md} §5, §11.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionRequestService {

    /** How long a request may wait for a decision before it lapses (§11.2). */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final PermissionRequestRepository repository;

    /**
     * Creates a pending request, or returns the existing one when an
     * identical request is already waiting.
     *
     * <p>Reuse is not just tidiness: it is the brake against an agent in a
     * loop filling somebody's inbox with the same ask. A repeated request
     * refreshes the reason (the newer wording is likely the better one)
     * but keeps the original item, decider routing and creation time.
     */
    public PermissionRequestDocument request(
            String tenantId,
            PermissionRequestOperation operation,
            GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId,
            @Nullable GrantRole role,
            @Nullable String reason,
            String requestedBy,
            @Nullable String requestedByProcessId) {

        Optional<PermissionRequestDocument> duplicate =
                findPending(tenantId, operation, scopeType, scopeId, subjectType, subjectId, role);
        if (duplicate.isPresent()) {
            PermissionRequestDocument existing = duplicate.get();
            if (reason != null && !reason.equals(existing.getReason())) {
                existing.setReason(reason);
                repository.save(existing);
            }
            log.info("permission-request reused id='{}' tenant='{}' {} {}:{} for {}:{}",
                    existing.getId(), tenantId, operation, scopeType, scopeId,
                    subjectType, subjectId);
            return existing;
        }

        PermissionRequestDocument doc = repository.save(PermissionRequestDocument.builder()
                .tenantId(tenantId)
                .operation(operation)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .role(role)
                .reason(reason)
                .requestedBy(requestedBy)
                .requestedByProcessId(requestedByProcessId)
                .status(PermissionRequestStatus.PENDING)
                .build());
        log.info("permission-request created id='{}' tenant='{}' {} {}:{} for {}:{} role={} by='{}'",
                doc.getId(), tenantId, operation, scopeType, scopeId,
                subjectType, subjectId, role, requestedBy);
        return doc;
    }

    /** Links the inbox item that carries this request to its decider. */
    public void attachInboxItem(String requestId, String inboxItemId) {
        repository.findById(requestId).ifPresent(doc -> {
            doc.setInboxItemId(inboxItemId);
            repository.save(doc);
        });
    }

    public Optional<PermissionRequestDocument> findById(String requestId) {
        return repository.findById(requestId);
    }

    /**
     * Marks the request as carried out. Only a {@code PENDING} request can
     * transition — a second call is a no-op, so a duplicated effect can
     * never double-apply.
     */
    public Optional<PermissionRequestDocument> markApproved(String requestId, String decidedBy) {
        return transition(requestId, PermissionRequestStatus.APPROVED, decidedBy, null);
    }

    public Optional<PermissionRequestDocument> markRejected(String requestId, String decidedBy) {
        return transition(requestId, PermissionRequestStatus.REJECTED, decidedBy, null);
    }

    public Optional<PermissionRequestDocument> markFailed(
            String requestId, String decidedBy, String failureReason) {
        return transition(requestId, PermissionRequestStatus.FAILED, decidedBy, failureReason);
    }

    /**
     * Expires every pending request naming {@code subjectId}. Called when
     * the subject is deleted: an ephemeral service account can vanish long
     * before anyone decides, and a request left pending could otherwise be
     * approved onto a later account that reuses the name.
     *
     * @return how many were expired
     */
    public int expireForSubject(
            String tenantId, GrantSubjectType subjectType, String subjectId) {
        List<PermissionRequestDocument> pending =
                repository.findByTenantIdAndSubjectTypeAndSubjectIdAndStatus(
                        tenantId, subjectType, subjectId, PermissionRequestStatus.PENDING);
        for (PermissionRequestDocument doc : pending) {
            doc.setStatus(PermissionRequestStatus.EXPIRED);
            doc.setDecidedAt(Instant.now());
            repository.save(doc);
        }
        if (!pending.isEmpty()) {
            log.info("Expired {} pending permission-request(s) of {}:{} in tenant '{}'",
                    pending.size(), subjectType, subjectId, tenantId);
        }
        return pending.size();
    }

    /** Expires requests that have waited longer than {@link #DEFAULT_TTL}. */
    public int expireStale(Instant now) {
        List<PermissionRequestDocument> stale = repository.findByStatusAndCreatedAtBefore(
                PermissionRequestStatus.PENDING, now.minus(DEFAULT_TTL));
        for (PermissionRequestDocument doc : stale) {
            doc.setStatus(PermissionRequestStatus.EXPIRED);
            doc.setDecidedAt(now);
            repository.save(doc);
        }
        if (!stale.isEmpty()) {
            log.info("Expired {} stale permission-request(s)", stale.size());
        }
        return stale.size();
    }

    private Optional<PermissionRequestDocument> transition(
            String requestId, PermissionRequestStatus target,
            String decidedBy, @Nullable String failureReason) {
        Optional<PermissionRequestDocument> found = repository.findById(requestId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PermissionRequestDocument doc = found.get();
        if (doc.getStatus() != PermissionRequestStatus.PENDING) {
            log.info("permission-request '{}' already {} — {} ignored",
                    requestId, doc.getStatus(), target);
            return Optional.of(doc);
        }
        doc.setStatus(target);
        doc.setDecidedBy(decidedBy);
        doc.setDecidedAt(Instant.now());
        doc.setFailureReason(failureReason);
        PermissionRequestDocument saved = repository.save(doc);
        log.info("permission-request '{}' -> {} by='{}'{}", requestId, target, decidedBy,
                failureReason == null ? "" : " reason=" + failureReason);
        return Optional.of(saved);
    }

    private Optional<PermissionRequestDocument> findPending(
            String tenantId, PermissionRequestOperation operation,
            GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId,
            @Nullable GrantRole role) {
        return repository
                .findByTenantIdAndStatusAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        tenantId, PermissionRequestStatus.PENDING,
                        scopeType, scopeId, subjectType, subjectId)
                .stream()
                .filter(d -> d.getOperation() == operation && Objects.equals(d.getRole(), role))
                .findFirst();
    }
}
