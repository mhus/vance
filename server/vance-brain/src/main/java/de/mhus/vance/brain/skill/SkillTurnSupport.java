package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What an engine needs from the skill subsystem at turn time, in one
 * bean: resolve the process's active skills through the cascade, compose
 * their system-prompt section (with each skill's invocation arguments),
 * and collect the tools they contribute.
 *
 * <p>Exists because every engine that supports skills needs the same
 * three steps, and each copy is a chance to drift — Ford and Frankie had
 * a private {@code resolveActiveSkills} each, while Arthur and Eddie ran
 * the trigger matcher but never composed anything, so an active skill's
 * body silently never reached the model in the default chat engine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillTurnSupport {

    /** Default of {@code vance.skills.max-advertised}. */
    static final int DEFAULT_MAX_ADVERTISED = 12;

    private final SkillResolver skillResolver;
    private final SkillPromptComposer composer;
    private final SessionService sessionService;
    private final SkillActivationService activationService;
    /**
     * Reads the process recipe's {@code advertisedSkills} per turn — the
     * same per-turn recipe lookup the tool filter does, so a recipe edit
     * or a newly tagged skill shows up without a respawn. Lazy: the recipe
     * layer reaches into the engine registry, which reaches the engines
     * that use this bean.
     */
    private final ObjectProvider<RecipeResolver> recipeResolverProvider;

    /**
     * Cap on advertised skill cards per turn (planning/
     * skill-activation-levels.md §3.2): overflow is signalled with a
     * "+M more — use how_do_i" note, never silently cut. Field-injected so
     * hand-built instances (tests) keep the default.
     */
    @Value("${vance.skills.max-advertised:" + DEFAULT_MAX_ADVERTISED + "}")
    private int maxAdvertised = DEFAULT_MAX_ADVERTISED;

    /**
     * Resolves the process's persisted {@link ActiveSkillRefEmbedded}s
     * into ready-to-use {@link ResolvedSkill}s through the
     * user/project/tenant/bundled cascade. Skills that no longer resolve
     * (e.g. the user deleted their private skill mid-session) are
     * skipped with a warning rather than failing the turn.
     */
    public List<ResolvedSkill> resolveActive(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.getName() == null || ref.getName().isBlank()) continue;
            try {
                skillResolver
                        .resolve(scope, ref.getName())
                        .ifPresentOrElse(
                                out::add,
                                () -> log.warn(
                                        "id='{}' active skill '{}' no longer resolves — skipping",
                                        process.getId(),
                                        ref.getName()));
            } catch (UnknownSkillException e) {
                log.warn("id='{}' active skill '{}' unknown — skipping", process.getId(), ref.getName());
            }
        }
        return out;
    }

    /**
     * Composes the {@code ## Active Skills} system-prompt section for
     * {@code skills}, rendering each body against {@code pebbleContext}
     * plus the arguments that skill was activated with. {@code null} when
     * nothing is active.
     */
    public @Nullable String composeSection(
            ThinkProcessDocument process, List<ResolvedSkill> skills, Map<String, Object> pebbleContext) {
        return composer.compose(skills, pebbleContext, rawArgsByName(process));
    }

    /** Tool names contributed by the active skills (add-only). */
    public Set<String> mergedTools(List<ResolvedSkill> skills) {
        return composer.mergedTools(skills);
    }

    /**
     * Maps skill name → the raw trailing text it was activated with.
     * Skills activated without arguments are absent from the map.
     */
    public static Map<String, String> rawArgsByName(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref.getName() == null || ref.getArgs() == null) continue;
            out.put(ref.getName(), ref.getArgs());
        }
        return out;
    }

    /**
     * Builds the cascade lookup scope from the process's session —
     * tenant plus the session's user and project, so a user's private
     * skill overrides the project's and the project's the tenant's.
     */
    public SkillScopeContext scopeFor(ThinkProcessDocument process) {
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

    /**
     * Composes the "Available skills" section — one line per skill the agent
     * may activate itself via {@code skill_activate} (name and description;
     * never the body), so it needs no {@code how_do_i} round-trip
     * (planning/skill-activation-levels.md §3.2, skills.md §7e).
     *
     * <p>A skill is listed when it is <b>enabled</b>, passes the process's
     * {@code allowedSkills} whitelist, is <b>not already active</b>, and is
     * offered by either side: its effective activation mode is
     * {@code agent} (frontmatter or scope override), or the process recipe's
     * {@code advertisedSkills} selects it by name or {@code @label}. Listing
     * is discovery only — nothing here activates a skill. Stable name order
     * so the same skill set renders byte-identically across turns.
     *
     * <p>{@code null} when nothing is listed — the caller adds no message at
     * all, keeping the prompt stable for skills-free scopes.
     */
    public @Nullable String advertiseSection(ThinkProcessDocument process, List<ResolvedSkill> activeSkills) {
        Set<String> whitelist = process.getAllowedSkillsOverride();
        Set<String> activeNames = new HashSet<>();
        for (ResolvedSkill s : activeSkills) {
            activeNames.add(s.name());
        }
        List<String> recipeSelectors = recipeAdvertisedSkills(process);
        SkillScopeContext scope = scopeFor(process);
        SkillActivationService.ActivationOverrides overrides = activationService.overrides(scope);
        List<ResolvedSkill> candidates = new ArrayList<>();
        for (ResolvedSkill s : skillResolver.listAvailable(scope)) {
            if (!s.enabled()) continue;
            if (activeNames.contains(s.name())) continue;
            if (whitelist != null && !whitelist.contains(s.name())) continue;
            boolean offered = SkillLabelSelector.selects(recipeSelectors, s)
                    || activationService.resolve(overrides, s).mode() == SkillActivation.AGENT;
            if (!offered) continue;
            candidates.add(s);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(ResolvedSkill::name, String.CASE_INSENSITIVE_ORDER));

        StringBuilder out = new StringBuilder("## Available skills — activate with the skill_activate tool\n\n");
        int shown = 0;
        int hidden = 0;
        for (ResolvedSkill s : candidates) {
            if (shown >= maxAdvertised) {
                hidden++;
                continue;
            }
            String description = s.description();
            out.append("- ")
                    .append(s.name())
                    .append(": ")
                    .append(description != null && !description.isBlank() ? description.strip() : s.title())
                    .append('\n');
            shown++;
        }
        if (hidden > 0) {
            out.append("\n+").append(hidden).append(" more — use `how_do_i` to discover them.\n");
            log.trace("Skill advertising capped at {} shown, {} hidden", shown, hidden);
        }
        return out.toString();
    }

    /**
     * The process recipe's {@code advertisedSkills} selectors, empty when the
     * process has no recipe or the recipe no longer resolves — advertising
     * is best-effort and must never fail a turn.
     */
    private List<String> recipeAdvertisedSkills(ThinkProcessDocument process) {
        String recipeName = process.getRecipeName();
        if (recipeName == null || recipeName.isBlank()) return List.of();
        RecipeResolver resolver = recipeResolverProvider.getIfAvailable();
        if (resolver == null) return List.of();
        try {
            return resolver.resolve(process.getTenantId(), process.getProjectId(), recipeName)
                    .map(ResolvedRecipe::advertisedSkills)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.warn(
                    "Skill advertising: recipe '{}' of process '{}' did not resolve: {}",
                    recipeName,
                    process.getId(),
                    e.toString());
            return List.of();
        }
    }
}
