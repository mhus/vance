package de.mhus.vance.brain.skill;

import org.jspecify.annotations.Nullable;

/**
 * The minimal scope tuple needed to resolve a skill in the cascade
 * USER → PROJECT → TENANT → BUNDLED. {@code userId} and
 * {@code projectId} may be null; {@code tenantId} is required (system
 * spawns without a tenant cannot use skills — they fall back to
 * bundled lookups via {@link BundledSkillRegistry} directly).
 */
public record SkillScopeContext(
        String tenantId,
        @Nullable String userId,
        @Nullable String projectId) {

    public static SkillScopeContext of(
            String tenantId, @Nullable String userId, @Nullable String projectId) {
        return new SkillScopeContext(tenantId, userId, projectId);
    }
}
