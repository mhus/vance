package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the system-prompt extension for a list of active skills and
 * computes the union tool-whitelist contributed by them. Stateless.
 *
 * <p>The composed text is appended <em>after</em> the engine-default
 * system prompt and the recipe's {@code promptPrefix} (see
 * {@code Ford.runTurn} and {@code SystemPrompts.compose}).
 */
@Service
public class SkillPromptComposer {

    /**
     * Renders the active skills into a single markdown block ready to
     * append to the system prompt. Returns {@code null} if {@code skills}
     * is empty — callers should treat that as "no skill section".
     *
     * <p>Reference docs with {@link SkillReferenceDocLoadMode#INLINE}
     * are embedded verbatim under {@code --- Reference Doc: ... ---}.
     * {@link SkillReferenceDocLoadMode#ON_DEMAND} docs are listed as
     * pull-targets under "On-demand references — load via
     * {@code manual_read}:" so the model can fetch them via the manual
     * tools without paying token cost up-front. The {@code title}
     * doubles as the {@code manual_read} argument; an optional
     * {@code summary} is appended after a dash for a one-line teaser.
     */
    public @Nullable String compose(List<ResolvedSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append("\n\n## Active Skills\n");
        out.append("The following skills are active for this turn. ")
                .append("Follow their guidance and use the tools they grant.\n");
        for (ResolvedSkill skill : skills) {
            out.append("\n--- Skill: ").append(skill.name()).append(" ---\n");
            if (skill.description() != null && !skill.description().isBlank()) {
                out.append(skill.description().trim()).append("\n");
            }
            if (skill.promptExtension() != null && !skill.promptExtension().isBlank()) {
                out.append("\n").append(skill.promptExtension().trim()).append("\n");
            }
            List<ResolvedSkill.ReferenceDoc> onDemand = new ArrayList<>();
            for (ResolvedSkill.ReferenceDoc doc : skill.referenceDocs()) {
                switch (doc.loadMode()) {
                    case INLINE -> {
                        out.append("\n--- Reference Doc: ").append(doc.title()).append(" ---\n");
                        out.append(doc.content().trim()).append("\n");
                    }
                    case ON_DEMAND -> onDemand.add(doc);
                }
            }
            if (!onDemand.isEmpty()) {
                out.append("\nOn-demand references — load via `manual_read`:\n");
                for (ResolvedSkill.ReferenceDoc doc : onDemand) {
                    out.append("- ").append(doc.title());
                    if (doc.summary() != null && !doc.summary().isBlank()) {
                        out.append(" — ").append(doc.summary().trim());
                    }
                    out.append("\n");
                }
            }
        }
        return out.toString();
    }

    /**
     * Returns the union of tool names contributed by all active skills.
     * Skills can only <em>add</em> tools (never remove); the returned
     * set is meant to be unioned with the engine/recipe whitelist by
     * the spawn / lane-turn pipeline.
     */
    public Set<String> mergedTools(List<ResolvedSkill> skills) {
        Set<String> out = new LinkedHashSet<>();
        if (skills == null) return out;
        for (ResolvedSkill skill : skills) {
            if (skill.tools() == null) continue;
            for (String tool : skill.tools()) {
                if (tool != null && !tool.isBlank()) {
                    out.add(tool);
                }
            }
        }
        return out;
    }

}
