package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.brain.prompt.PromptTemplateException;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Builds the system-prompt extension for a list of active skills and
 * computes the union tool-whitelist contributed by them. Stateless.
 *
 * <p>The composed text is appended <em>after</em> the engine-default
 * system prompt and the recipe's {@code promptPrefix} (see
 * {@code Ford.runTurn} and {@code SystemPrompts.compose}).
 *
 * <p>Skill bodies are rendered as Pebble templates against the same
 * variable map ({@code tier}, {@code model}, {@code provider},
 * {@code mode}, {@code profile}, {@code recipe}, {@code engine},
 * {@code lang}, {@code params}) that engine-default prompts and recipe
 * {@code promptPrefix} use — see
 * {@link de.mhus.vance.brain.prompt.PromptContextBuilder}. A skill with
 * syntactically invalid Pebble is skipped with a {@code WARN} log and
 * does not crash the turn; the rest of the skill list composes
 * normally.
 */
@Service
@Slf4j
public class SkillPromptComposer {

    private final PromptTemplateRenderer templateRenderer;

    public SkillPromptComposer(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

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
        return compose(skills, Map.of());
    }

    /**
     * Renders {@code skills} with Pebble against {@code pebbleContext}.
     * Use the {@code Map.of()}-form when no per-turn context is
     * available; a body without {@code {% %}} or {@code {{ }}} tokens
     * round-trips through Pebble unchanged.
     */
    public @Nullable String compose(
            List<ResolvedSkill> skills, Map<String, Object> pebbleContext) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append("\n\n## Active Skills\n");
        out.append("The following skills are active for this turn. ")
                .append("Follow their guidance and use the tools they grant.\n");
        for (ResolvedSkill skill : skills) {
            String renderedBody;
            try {
                renderedBody = renderBody(skill, pebbleContext);
            } catch (PromptTemplateException e) {
                log.warn("Skill '{}' has invalid Pebble template — skipping: {}",
                        skill.name(), e.getMessage());
                continue;
            }
            out.append("\n--- Skill: ").append(skill.name()).append(" ---\n");
            if (skill.description() != null && !skill.description().isBlank()) {
                out.append(skill.description().trim()).append("\n");
            }
            if (renderedBody != null && !renderedBody.isBlank()) {
                out.append("\n").append(renderedBody.trim()).append("\n");
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
     * Renders the skill body through Pebble. {@code null} / blank
     * bodies round-trip unchanged. Reference-doc {@code content} is
     * intentionally <em>not</em> rendered — references are author-
     * controlled data, not templates, and rendering them would surprise
     * skills that include literal {@code {% %}} text in a reference.
     */
    private @Nullable String renderBody(
            ResolvedSkill skill, Map<String, Object> pebbleContext) {
        String body = skill.promptExtension();
        if (body == null || body.isBlank()) return body;
        return templateRenderer.render(body, pebbleContext);
    }

    /**
     * Returns the union of tool names contributed by all active skills.
     * Skills can only <em>add</em> tools (never remove); the returned
     * set is meant to be unioned with the engine/recipe whitelist by
     * the spawn / lane-turn pipeline.
     *
     * <p>The result includes two contributions per skill:
     * <ol>
     *   <li>Explicit {@code tools:} list entries — names of pre-existing
     *       tools the skill wants to whitelist (e.g. {@code manual_read}).
     *   <li>Implicit {@code skill_<skill>__<script>} entries for every
     *       {@code scripts:} frontmatter declaration — per
     *       {@code specification/skills.md} §13.2 each script gets
     *       mounted as a virtual tool when the skill is active. Without
     *       this union, the {@code SkillScriptToolSource} would emit
     *       the tool but the engine's allow-filter would drop it before
     *       the LLM ever sees it.
     * </ol>
     */
    public Set<String> mergedTools(List<ResolvedSkill> skills) {
        Set<String> out = new LinkedHashSet<>();
        if (skills == null) return out;
        for (ResolvedSkill skill : skills) {
            if (skill.tools() != null) {
                for (String tool : skill.tools()) {
                    if (tool != null && !tool.isBlank()) {
                        out.add(tool);
                    }
                }
            }
            if (skill.scripts() != null) {
                for (ResolvedSkill.Script script : skill.scripts()) {
                    if (script.name() == null || script.name().isBlank()) continue;
                    out.add("skill_" + skill.name() + "__" + script.name());
                }
            }
        }
        return out;
    }

}
