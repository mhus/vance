package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Skill persistence + cascade lookup limited to the Mongo-stored tiers
 * (user, project, tenant). Bundled defaults from the classpath are
 * owned by the brain-side {@code BundledSkillRegistry} and combined
 * with this service by the brain-side {@code SkillResolver}.
 *
 * <p>Cascade order on lookup is USER → PROJECT → TENANT;
 * first-hit-wins. Listing operations return the union with
 * deduplication by skill name (more specific scope wins).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillService {

    private final SkillRepository repository;

    /**
     * Find a skill by name, walking user → project → tenant. Returns
     * empty if no Mongo tier matches — the caller is then expected to
     * fall back to bundled defaults (or fail).
     */
    public Optional<SkillDocument> find(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            String name) {
        if (userId != null) {
            Optional<SkillDocument> user =
                    repository.findByTenantIdAndUserIdAndName(tenantId, userId, name);
            if (user.isPresent() && user.get().isEnabled()) {
                return user;
            }
        }
        if (projectId != null) {
            Optional<SkillDocument> project =
                    repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name);
            if (project.isPresent() && project.get().isEnabled()) {
                return project;
            }
        }
        Optional<SkillDocument> tenant =
                repository.findByTenantIdAndScopeAndName(tenantId, SkillScope.TENANT, name);
        if (tenant.isPresent() && tenant.get().isEnabled()) {
            return tenant;
        }
        return Optional.empty();
    }

    /**
     * List the union of all skills visible in the given scope. The
     * cascade USER → PROJECT → TENANT is applied to deduplicate by
     * skill name — a user-private skill named {@code foo} hides the
     * project-/tenant-level {@code foo}. Disabled skills are skipped.
     *
     * <p>Bundled defaults are not included; the brain-side resolver
     * unions them on top.
     */
    public List<SkillDocument> listAvailable(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId) {
        Map<String, SkillDocument> byName = new LinkedHashMap<>();

        if (userId != null) {
            for (SkillDocument doc : repository.findByTenantIdAndUserId(tenantId, userId)) {
                if (doc.isEnabled()) {
                    byName.putIfAbsent(doc.getName(), doc);
                }
            }
        }
        if (projectId != null) {
            for (SkillDocument doc : repository.findByTenantIdAndProjectId(tenantId, projectId)) {
                if (doc.isEnabled()) {
                    byName.putIfAbsent(doc.getName(), doc);
                }
            }
        }
        for (SkillDocument doc : repository.findByTenantIdAndScope(tenantId, SkillScope.TENANT)) {
            if (doc.isEnabled()) {
                byName.putIfAbsent(doc.getName(), doc);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** List all tenant-scoped skills (admin / management view). */
    public List<SkillDocument> listTenant(String tenantId) {
        return repository.findByTenantIdAndScope(tenantId, SkillScope.TENANT);
    }

    /** List all project-scoped skills (admin / management view). */
    public List<SkillDocument> listProject(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    /** List all user-private skills. Visible only to the owning user. */
    public List<SkillDocument> listUser(String tenantId, String userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId);
    }

    public Optional<SkillDocument> findById(String id) {
        return repository.findById(id);
    }

    public SkillDocument save(SkillDocument doc) {
        validateScope(doc);
        SkillDocument saved = repository.save(doc);
        log.info(
                "Saved skill tenant='{}' scope={} owner='{}' name='{}' id='{}'",
                saved.getTenantId(),
                saved.getScope(),
                ownerOf(saved),
                saved.getName(),
                saved.getId());
        return saved;
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    private static void validateScope(SkillDocument doc) {
        SkillScope scope = doc.getScope();
        if (scope == SkillScope.BUNDLED) {
            throw new IllegalArgumentException(
                    "BUNDLED skills are classpath-only and cannot be persisted");
        }
        if (scope == SkillScope.USER && (doc.getUserId() == null || doc.getUserId().isBlank())) {
            throw new IllegalArgumentException("USER-scoped skill requires userId");
        }
        if (scope == SkillScope.PROJECT
                && (doc.getProjectId() == null || doc.getProjectId().isBlank())) {
            throw new IllegalArgumentException("PROJECT-scoped skill requires projectId");
        }
        if (scope == SkillScope.TENANT && doc.getProjectId() != null) {
            throw new IllegalArgumentException("TENANT-scoped skill must not carry projectId");
        }
        if (doc.getTenantId() == null || doc.getTenantId().isBlank()) {
            throw new IllegalArgumentException("Skill requires tenantId");
        }
    }

    private static String ownerOf(SkillDocument doc) {
        return switch (doc.getScope()) {
            case USER -> "user:" + doc.getUserId();
            case PROJECT -> "project:" + doc.getProjectId();
            case TENANT -> "tenant";
            case BUNDLED -> "bundled";
        };
    }
}
