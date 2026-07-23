package de.mhus.vance.simpleauth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Owns the {@code permission_grants} collection. The resolver reads exclusively
 * through {@link #forScope} (data sovereignty — no direct repository access
 * elsewhere).
 *
 * <p><b>Caching.</b> WS is the lead channel and a single frame can trigger
 * several checks, so {@link #forScope} is memoised in a bounded, short-TTL
 * Caffeine cache keyed by {@code (tenant, scopeType, scopeId)}. Grants change
 * rarely; the TTL bounds cross-pod drift (v1 is TTL-only — no Redis
 * invalidation, per the Redis-only-for-live-features rule). Every local
 * mutation invalidates the affected key immediately.
 */
@Service
@Slf4j
public class PermissionGrantService {

    private final PermissionGrantRepository repository;

    private final Cache<String, List<PermissionGrantDocument>> scopeCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(45))
            .build();

    public PermissionGrantService(PermissionGrantRepository repository) {
        this.repository = repository;
    }

    /** All grants on one scope — cached, immutable. Backs the resolver hot path. */
    public List<PermissionGrantDocument> forScope(String tenantId, GrantScopeType scopeType, String scopeId) {
        String key = cacheKey(tenantId, scopeType, scopeId);
        List<PermissionGrantDocument> cached = scopeCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<PermissionGrantDocument> fresh = List.copyOf(
                repository.findByTenantIdAndScopeTypeAndScopeId(tenantId, scopeType, scopeId));
        scopeCache.put(key, fresh);
        return fresh;
    }

    /** Every grant of one subject — for admin listing and cleanup on user/team deletion. */
    public List<PermissionGrantDocument> forSubject(String tenantId, GrantSubjectType subjectType, String subjectId) {
        return List.copyOf(
                repository.findByTenantIdAndSubjectTypeAndSubjectId(tenantId, subjectType, subjectId));
    }

    /**
     * Upsert the grant for {@code (scope, subject)} to {@code role} — idempotent,
     * a re-run just overwrites the role. Returns the persisted document.
     */
    public PermissionGrantDocument set(String tenantId, GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId, GrantRole role, String createdBy) {
        PermissionGrantDocument doc = repository
                .findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        tenantId, scopeType, scopeId, subjectType, subjectId)
                .orElseGet(PermissionGrantDocument::new);
        doc.setTenantId(tenantId);
        doc.setScopeType(scopeType);
        doc.setScopeId(scopeId);
        doc.setSubjectType(subjectType);
        doc.setSubjectId(subjectId);
        doc.setRole(role);
        if (doc.getId() == null) {
            doc.setCreatedBy(createdBy);
        }
        PermissionGrantDocument saved = repository.save(doc);
        scopeCache.invalidate(cacheKey(tenantId, scopeType, scopeId));
        log.info("permission-grant set tenant='{}' scope={}:{} subject={}:{} role={} by='{}'",
                tenantId, scopeType, scopeId, subjectType, subjectId, role, createdBy);
        return saved;
    }

    /** Remove the grant for {@code (scope, subject)}. No-op if absent. Returns true if one was removed. */
    public boolean remove(String tenantId, GrantScopeType scopeType, String scopeId,
            GrantSubjectType subjectType, String subjectId) {
        Optional<PermissionGrantDocument> existing = repository
                .findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                        tenantId, scopeType, scopeId, subjectType, subjectId);
        if (existing.isEmpty()) {
            return false;
        }
        repository.delete(existing.get());
        scopeCache.invalidate(cacheKey(tenantId, scopeType, scopeId));
        log.info("permission-grant removed tenant='{}' scope={}:{} subject={}:{}",
                tenantId, scopeType, scopeId, subjectType, subjectId);
        return true;
    }

    private static String cacheKey(String tenantId, GrantScopeType scopeType, String scopeId) {
        return tenantId + '\0' + scopeType + '\0' + scopeId;
    }
}
