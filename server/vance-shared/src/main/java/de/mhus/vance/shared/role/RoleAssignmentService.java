package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import de.mhus.vance.shared.cache.TtlTenantCache;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code role_assignments} collection — plain storage, no rules. Who
 * may change what is {@link RoleAdministrationService}'s business; this
 * service is also what trusted code (bootstrap, lifecycle cleanup, the operator
 * shell) writes through.
 *
 * <p><b>Caching.</b> A single WS frame can trigger several permission checks,
 * so {@link #forScope} is memoised per {@code (tenant, scopeType, scopeId)}
 * with a short TTL. Roles change rarely; the TTL bounds cross-pod drift (no
 * cross-pod invalidation — Redis is for live features only). Every local
 * mutation invalidates the affected scope immediately.
 */
@Service
@Slf4j
public class RoleAssignmentService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(45);

    private final RoleAssignmentRepository repository;
    private final TtlTenantCache<String, List<RoleAssignmentDocument>> scopeCache = new TtlTenantCache<>(CACHE_TTL);

    public RoleAssignmentService(RoleAssignmentRepository repository) {
        this.repository = repository;
    }

    /** All assignments on one scope — cached, immutable. Backs the permission hot path. */
    public List<RoleAssignmentDocument> forScope(String tenantId, RoleScopeType scopeType, String scopeId) {
        List<RoleAssignmentDocument> cached = scopeCache.get(
                cacheKey(tenantId, scopeType, scopeId),
                () -> List.copyOf(repository.findByTenantIdAndScopeTypeAndScopeId(tenantId, scopeType, scopeId)));
        return Objects.requireNonNull(cached);
    }

    /** Every assignment of one subject — for listing and cleanup. */
    public List<RoleAssignmentDocument> forSubject(String tenantId, RoleSubjectType subjectType, String subjectId) {
        return List.copyOf(repository.findByTenantIdAndSubjectTypeAndSubjectId(tenantId, subjectType, subjectId));
    }

    /** Stored tenant role of {@code username}; {@link TenantRole#USER} when none is stored. */
    public TenantRole tenantRoleOf(String tenantId, String username) {
        for (RoleAssignmentDocument a : forScope(tenantId, RoleScopeType.TENANT, tenantId)) {
            if (a.getSubjectType() == RoleSubjectType.USER
                    && username.equals(a.getSubjectId())
                    && a.getTenantRole() != null) {
                return a.getTenantRole();
            }
        }
        return TenantRole.USER;
    }

    /** Users holding exactly {@code role} in the tenant. */
    public List<String> usersWithTenantRole(String tenantId, TenantRole role) {
        return forScope(tenantId, RoleScopeType.TENANT, tenantId).stream()
                .filter(a -> a.getSubjectType() == RoleSubjectType.USER && a.getTenantRole() == role)
                .map(RoleAssignmentDocument::getSubjectId)
                .toList();
    }

    /** The stored project role of one subject — no team folding. */
    public @Nullable ProjectRole projectRoleOf(
            String tenantId, String project, RoleSubjectType subjectType, String subjectId) {
        for (RoleAssignmentDocument a : forScope(tenantId, RoleScopeType.PROJECT, project)) {
            if (a.getSubjectType() == subjectType && subjectId.equals(a.getSubjectId())) {
                return a.getProjectRole();
            }
        }
        return null;
    }

    /**
     * Stores {@code role} as the tenant role of {@code username}. {@link
     * TenantRole#USER} is the implicit default and removes the stored row.
     */
    public void setTenantRole(String tenantId, String username, TenantRole role, String createdBy) {
        if (role == TenantRole.USER) {
            remove(tenantId, RoleScopeType.TENANT, tenantId, RoleSubjectType.USER, username);
            return;
        }
        upsert(tenantId, RoleScopeType.TENANT, tenantId, RoleSubjectType.USER, username, role, null, createdBy);
    }

    /** Stores {@code role} as the membership of a user or team in {@code project}. */
    public RoleAssignmentDocument setProjectRole(
            String tenantId,
            String project,
            RoleSubjectType subjectType,
            String subjectId,
            ProjectRole role,
            String createdBy) {
        return upsert(tenantId, RoleScopeType.PROJECT, project, subjectType, subjectId, null, role, createdBy);
    }

    /** The stored assignment of a subject on a scope, if any. */
    public Optional<RoleAssignmentDocument> find(
            String tenantId, RoleScopeType scopeType, String scopeId, RoleSubjectType subjectType, String subjectId) {
        return repository.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                tenantId, scopeType, scopeId, subjectType, subjectId);
    }

    /** Removes the assignment of a subject on a scope. No-op if absent; true if one was removed. */
    public boolean remove(
            String tenantId, RoleScopeType scopeType, String scopeId, RoleSubjectType subjectType, String subjectId) {
        Optional<RoleAssignmentDocument> existing = find(tenantId, scopeType, scopeId, subjectType, subjectId);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        scopeCache.invalidate(cacheKey(tenantId, scopeType, scopeId));
        log.info(
                "role assignment removed tenant='{}' scope={}:{} subject={}:{}",
                tenantId,
                scopeType,
                scopeId,
                subjectType,
                subjectId);
        return true;
    }

    /** Removes every assignment of a subject, at any scope. Returns how many went. */
    public int removeAll(String tenantId, RoleSubjectType subjectType, String subjectId) {
        int removed = 0;
        for (RoleAssignmentDocument a : forSubject(tenantId, subjectType, subjectId)) {
            if (remove(tenantId, a.getScopeType(), a.getScopeId(), subjectType, subjectId)) {
                removed++;
            }
        }
        return removed;
    }

    private RoleAssignmentDocument upsert(
            String tenantId,
            RoleScopeType scopeType,
            String scopeId,
            RoleSubjectType subjectType,
            String subjectId,
            @Nullable TenantRole tenantRole,
            @Nullable ProjectRole projectRole,
            String createdBy) {
        try {
            return save(tenantId, scopeType, scopeId, subjectType, subjectId, tenantRole, projectRole, createdBy);
        } catch (DuplicateKeyException e) {
            // Lost race for the same (scope, subject): our read found nothing,
            // then another writer inserted before our save and role_key_idx
            // rejected it. The normal case on a fresh database with two pods
            // booting at once — the admin seed runs on both. Retrying finds
            // their row and applies our role to it, which is what two
            // sequential calls would have done. A second failure is not a race
            // and keeps its exception.
            log.info(
                    "role assignment raced for scope={}:{} subject={}:{} — retrying",
                    scopeType,
                    scopeId,
                    subjectType,
                    subjectId);
            return save(tenantId, scopeType, scopeId, subjectType, subjectId, tenantRole, projectRole, createdBy);
        }
    }

    private RoleAssignmentDocument save(
            String tenantId,
            RoleScopeType scopeType,
            String scopeId,
            RoleSubjectType subjectType,
            String subjectId,
            @Nullable TenantRole tenantRole,
            @Nullable ProjectRole projectRole,
            String createdBy) {
        RoleAssignmentDocument doc =
                find(tenantId, scopeType, scopeId, subjectType, subjectId).orElseGet(RoleAssignmentDocument::new);
        doc.setTenantId(tenantId);
        doc.setScopeType(scopeType);
        doc.setScopeId(scopeId);
        doc.setSubjectType(subjectType);
        doc.setSubjectId(subjectId);
        doc.setTenantRole(tenantRole);
        doc.setProjectRole(projectRole);
        // createdBy is who made the assignment as it stands now — a bootstrap
        // seed that someone later changes on purpose is theirs from then on.
        doc.setCreatedBy(createdBy);
        RoleAssignmentDocument saved = repository.save(doc);
        scopeCache.invalidate(cacheKey(tenantId, scopeType, scopeId));
        log.info(
                "role assignment set tenant='{}' scope={}:{} subject={}:{} role={} by='{}'",
                tenantId,
                scopeType,
                scopeId,
                subjectType,
                subjectId,
                tenantRole != null ? tenantRole : projectRole,
                createdBy);
        return saved;
    }

    private static String cacheKey(String tenantId, RoleScopeType scopeType, String scopeId) {
        return tenantId + '\0' + scopeType + '\0' + scopeId;
    }
}
