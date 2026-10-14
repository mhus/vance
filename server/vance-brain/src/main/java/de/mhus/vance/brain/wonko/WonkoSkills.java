package de.mhus.vance.brain.wonko;

import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SingleShotSkillComposer;
import de.mhus.vance.brain.skill.SkillLabelSelector;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The skills Wonko's model calls carry (skills.md §5): those the recipe
 * selects (param {@code skills} — names and {@code @label} selectors with
 * {@code !} exclusions, the {@code relevantSkills} grammar) and those
 * activated on the process ({@code /skill}, START guard). Wonko has no tool
 * loop, so a skill takes effect as prompt text in every single-shot call —
 * only skills that compose that way are taken, the others are named with
 * their reason instead of being dropped silently.
 */
@Component
@RequiredArgsConstructor
class WonkoSkills {

    /** The usable skill names, and why the others were left out. */
    record Selection(List<String> names, List<String> skipped) {}

    private final SkillResolver skillResolver;
    private final SingleShotSkillComposer composer;

    Selection select(ThinkProcessDocument process, @Nullable String userId, List<String> selectors) {
        SkillScopeContext scope = SkillScopeContext.of(process.getTenantId(), userId, process.getProjectId());
        Set<String> candidates = new LinkedHashSet<>();
        if (!selectors.isEmpty()) {
            for (ResolvedSkill skill : skillResolver.listAvailable(scope)) {
                if (SkillLabelSelector.relevant(selectors, skill)) {
                    candidates.add(skill.name());
                }
            }
        }
        for (ActiveSkillRefEmbedded active : process.getActiveSkills()) {
            candidates.add(active.getName());
        }
        Set<String> whitelist = process.getAllowedSkillsOverride();
        List<String> names = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : candidates) {
            if (whitelist != null && !whitelist.contains(name)) {
                skipped.add("skill '" + name + "' is not in the recipe's allowedSkills");
                continue;
            }
            try {
                composer.resolveComposable(scope, List.of(name));
                names.add(name);
            } catch (IllegalArgumentException e) {
                skipped.add(e.getMessage() == null ? "skill '" + name + "' cannot compose" : e.getMessage());
            }
        }
        return new Selection(names, skipped);
    }
}
