package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Effective activation level of a skill on one process
 * (planning/skill-activation-levels.md §7.4) — the one place that folds
 * the two sides together:
 *
 * <ol>
 *   <li><b>Skill side</b> — the mode: frontmatter {@code activation:} (or
 *       its {@code triggers:} derivation) with the scope overrides from
 *       {@code _vance/config/skill_activation.yaml}
 *       ({@link SkillActivationService});</li>
 *   <li><b>Recipe side</b> — relevance: the process recipe's
 *       {@code relevantSkills} selectors ({@link SkillLabelSelector}).</li>
 * </ol>
 *
 * The effective level is {@link SkillActivation#HIDDEN} when the recipe
 * does not select the skill, else the mode. Every consumer reads it from
 * here: the trigger matcher arms only {@code trigger}, the "Available
 * skills" section lists only {@code agent}, the skill panel shows the
 * level and its source.
 *
 * <p>Read once per consumer call ({@link #forProcess}) — the override
 * documents and the recipe are looked up once, the per-skill step is
 * in-memory. The recipe is resolved per call, like the tool filter, so a
 * recipe edit or a newly tagged skill takes effect on the next turn
 * without a respawn. A recipe that no longer resolves makes every skill
 * relevant (logged) — relevance is presentation, it must never fail a
 * turn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillLevelResolver {

    private final SkillActivationService activationService;
    private final SessionService sessionService;
    private final SkillResolver skillResolver;
    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy: the recipe layer reaches into the engine registry, which
     * reaches the engines that use the skill beans.
     */
    private final ObjectProvider<RecipeResolver> recipeResolverProvider;

    /** One skill's level on a process, with the parts it was made of. */
    public record Level(
            SkillActivation level, SkillActivationService.ActivationResolution activation, boolean relevant) {}

    /** The per-process inputs, read once; {@link #of} is the cheap per-skill step. */
    public final class Levels {

        private final SkillActivationService.ActivationOverrides overrides;
        private final @Nullable List<String> relevantSelectors;

        private Levels(SkillActivationService.ActivationOverrides overrides, @Nullable List<String> relevantSelectors) {
            this.overrides = overrides;
            this.relevantSelectors = relevantSelectors;
        }

        public Level of(ResolvedSkill skill) {
            SkillActivationService.ActivationResolution activation = activationService.resolve(overrides, skill);
            boolean relevant = SkillLabelSelector.relevant(relevantSelectors, skill);
            return new Level(relevant ? activation.mode() : SkillActivation.HIDDEN, activation, relevant);
        }

        public SkillActivation levelOf(ResolvedSkill skill) {
            return of(skill).level();
        }
    }

    /** Levels for a process whose scope the caller already derived. */
    public Levels forProcess(ThinkProcessDocument process, SkillScopeContext scope) {
        return new Levels(activationService.overrides(scope), recipeRelevantSkills(process));
    }

    /** Levels for a process, deriving the scope from its session. */
    public Levels forProcess(ThinkProcessDocument process) {
        return forProcess(process, scopeFor(process));
    }

    /**
     * Names of the enabled skills whose effective level on the process is
     * {@code hidden} — for surfaces that rank rather than filter
     * ({@code how_do_i} lists them after the relevant ones). Empty when the
     * process is unknown: ranking is best-effort.
     */
    public Set<String> hiddenSkillNames(@Nullable String processId) {
        if (processId == null || processId.isBlank()) return Set.of();
        ThinkProcessDocument process = thinkProcessService.findById(processId).orElse(null);
        if (process == null) return Set.of();
        SkillScopeContext scope = scopeFor(process);
        Levels levels = forProcess(process, scope);
        Set<String> hidden = new LinkedHashSet<>();
        for (ResolvedSkill skill : skillResolver.listAvailable(scope)) {
            if (skill.enabled() && levels.levelOf(skill) == SkillActivation.HIDDEN) hidden.add(skill.name());
        }
        return hidden;
    }

    /**
     * The process recipe's {@code relevantSkills}; {@code null} ("all
     * relevant") when the process has no recipe, the recipe lacks the
     * field, or it no longer resolves.
     */
    private @Nullable List<String> recipeRelevantSkills(ThinkProcessDocument process) {
        String recipeName = process.getRecipeName();
        if (recipeName == null || recipeName.isBlank()) return null;
        RecipeResolver resolver = recipeResolverProvider.getIfAvailable();
        if (resolver == null) return null;
        try {
            return resolver.resolve(process.getTenantId(), process.getProjectId(), recipeName)
                    .map(ResolvedRecipe::relevantSkills)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn(
                    "Skill relevance: recipe '{}' of process '{}' did not resolve, treating every skill as relevant: {}",
                    recipeName,
                    process.getId(),
                    e.toString());
            return null;
        }
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session =
                sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null
                        && session.getUserId() != null
                        && !session.getUserId().isBlank()
                ? session.getUserId()
                : null;
        String projectId = session != null
                        && session.getProjectId() != null
                        && !session.getProjectId().isBlank()
                ? session.getProjectId()
                : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }
}
