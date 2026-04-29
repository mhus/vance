package de.mhus.vance.brain.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Cascade resolver for skill names — USER → PROJECT → VANCE → RESOURCE,
 * implemented by {@link SkillLoader} on top of
 * {@code DocumentService.lookupCascade}. This service is a thin
 * facade so callers (Ford, SkillPromptComposer, Arthur's
 * auto-trigger detector) remain decoupled from the loader.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillResolver {

    private final SkillLoader loader;

    /**
     * Resolves {@code name} in the cascade. Returns empty if no tier
     * carries the name, or if the matched skill is disabled.
     */
    public Optional<ResolvedSkill> resolve(SkillScopeContext ctx, String name) {
        return loader.load(ctx.tenantId(), ctx.userId(), ctx.projectId(), name)
                .filter(ResolvedSkill::enabled);
    }

    /**
     * Resolves multiple skill names in order. Unknown names throw —
     * partial activation would be confusing for users explicitly
     * requesting a skill set (Recipe-bound skills, /skill spam).
     */
    public List<ResolvedSkill> resolveAll(SkillScopeContext ctx, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<ResolvedSkill> out = new ArrayList<>(names.size());
        for (String name : names) {
            ResolvedSkill skill = resolve(ctx, name)
                    .orElseThrow(() -> new UnknownSkillException(name));
            out.add(skill);
        }
        return out;
    }

    /**
     * Lists the union of all skills visible in the given scope, with
     * cascade-deduplication by name (most specific scope wins).
     * Disabled skills are skipped.
     */
    public List<ResolvedSkill> listAvailable(SkillScopeContext ctx) {
        return loader.listAvailable(ctx.tenantId(), ctx.userId(), ctx.projectId());
    }
}
