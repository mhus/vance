package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Skill composition for single-shot LLM calls — no tool loop, no lane
 * (planning/skill-activation-levels.md §3.6). Only a skill's body and its
 * INLINE reference docs can take effect there; {@code tools:},
 * {@code manualPaths:}, {@code ON_DEMAND} docs and the control-plane
 * fields have nothing to attach to.
 *
 * <p>One validation for two call sites: {@code LightLlmService} checks on
 * every call, Benjy checks its stage skills once at process start so a
 * misconfigured recipe fails before the first controller call instead of
 * in the middle of a run.
 */
@Service
@RequiredArgsConstructor
public class SingleShotSkillComposer {

    private final SkillResolver skillResolver;
    private final SkillPromptComposer skillPromptComposer;

    /**
     * Resolves the named skills and checks each is composable into a
     * single-shot prompt. Fail-fast, never silent.
     *
     * @throws IllegalArgumentException naming the first skill that does not
     *     resolve, is disabled, declares {@code ON_DEMAND} reference docs, or
     *     has neither a body nor INLINE reference docs
     */
    public List<ResolvedSkill> resolveComposable(SkillScopeContext scope, List<String> names) {
        List<ResolvedSkill> resolved = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            ResolvedSkill skill = skillResolver
                    .resolve(scope, name)
                    .orElseThrow(
                            () -> new IllegalArgumentException("skill '" + name + "' does not resolve in this scope"));
            String problem = unsuitability(skill);
            if (problem != null) {
                throw new IllegalArgumentException("skill '" + name + "' " + problem);
            }
            resolved.add(skill);
        }
        return resolved;
    }

    /**
     * Why a skill cannot take effect in a single-shot call, or {@code null}
     * when it can.
     */
    private static @Nullable String unsuitability(ResolvedSkill skill) {
        if (!skill.enabled()) {
            return "is disabled";
        }
        if (hasDocs(skill, SkillReferenceDocLoadMode.ON_DEMAND)) {
            return "declares ON_DEMAND reference docs — there is no manual_read in a single-shot call";
        }
        boolean hasBody =
                skill.promptExtension() != null && !skill.promptExtension().isBlank();
        if (!hasBody && !hasDocs(skill, SkillReferenceDocLoadMode.INLINE)) {
            return "has no body and no INLINE reference docs"
                    + " — tools/manualPaths/activate alone have no effect in a single-shot call";
        }
        return null;
    }

    /** The prompt section for already-validated skills; {@code null} when there is nothing to add. */
    public @Nullable String compose(List<ResolvedSkill> skills) {
        if (skills.isEmpty()) return null;
        return skillPromptComposer.compose(skills);
    }

    private static boolean hasDocs(ResolvedSkill skill, SkillReferenceDocLoadMode mode) {
        return skill.referenceDocs() != null && skill.referenceDocs().stream().anyMatch(d -> d.loadMode() == mode);
    }
}
