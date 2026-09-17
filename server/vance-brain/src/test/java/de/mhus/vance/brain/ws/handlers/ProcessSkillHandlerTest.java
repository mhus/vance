package de.mhus.vance.brain.ws.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ScriptTarget;
import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillLifecycle;
import de.mhus.vance.brain.skill.SkillRun;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DTO projection of the {@code process-skill} channel — the two
 * package-private mappers of {@link ProcessSkillHandler}. These tests pin
 * the wire contract clients render against: {@code fromRecipe} (drives the
 * deactivate control's disabled state, skills.md §7a) and the full summary
 * projection (triggers, lifecycle, tools, arguments, reference docs,
 * scripts, command sequences — drives the skill panel's info view).
 */
class ProcessSkillHandlerTest {

    @Test
    void toActiveDtoList_carriesFromRecipeAndOneShot() {
        ActiveSkillRefEmbedded userSkill = ActiveSkillRefEmbedded.builder()
                .name("code-review")
                .resolvedFromScope(SkillScope.USER)
                .oneShot(false)
                .activatedAt(Instant.parse("2026-09-15T10:00:00Z"))
                .build();
        ActiveSkillRefEmbedded recipeSkill = ActiveSkillRefEmbedded.builder()
                .name("typescript-style")
                .resolvedFromScope(SkillScope.PROJECT)
                .fromRecipe(true)
                .build();

        List<ActiveSkillRefDto> out = ProcessSkillHandler.toActiveDtoList(List.of(userSkill, recipeSkill));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getName()).isEqualTo("code-review");
        assertThat(out.get(0).isFromRecipe()).isFalse();
        assertThat(out.get(0).isOneShot()).isFalse();
        assertThat(out.get(0).getActivatedAt()).isEqualTo(Instant.parse("2026-09-15T10:00:00Z"));
        assertThat(out.get(1).getName()).isEqualTo("typescript-style");
        assertThat(out.get(1).isFromRecipe()).isTrue();
    }

    @Test
    void toSummary_projectsTheFullSkillMetadata() {
        ResolvedSkill skill = new ResolvedSkill(
                "code-review",
                "Code Review",
                "Reviews code",
                "1.2.0",
                List.of(
                        new ResolvedSkill.Trigger(SkillTriggerType.KEYWORDS, null, List.of("review", "code review")),
                        new ResolvedSkill.Trigger(SkillTriggerType.PATTERN, "\\brefactor\\b", List.of())),
                "body",
                List.of("file_read", "exec_check"),
                List.of("_vance/manuals/code-review.md"),
                List.of(new ResolvedSkill.ReferenceDoc(
                        "Checklist", "content", SkillReferenceDocLoadMode.ON_DEMAND, "The review checklist")),
                List.of(new ResolvedSkill.Script(
                        "run-lint",
                        ScriptTarget.BRAIN,
                        "Runs the linter",
                        List.of(new ResolvedSkill.Script.ScriptParam("fix", "boolean", "Apply fixes", false)),
                        "return 'ok'")),
                List.of("code"),
                "coding",
                true,
                SkillScope.PROJECT,
                List.of(EngineCommand.parse("guard script _vance/guards/review.js")),
                List.of(EngineCommand.parse("guard clear")),
                SkillLifecycle.STICKY,
                true,
                List.of(
                        new ResolvedSkill.Argument("language", "string", "Language to review", false),
                        new ResolvedSkill.Argument("focus", "string", "Focus area", true)),
                null,
                SkillRun.INLINE);

        SkillSummaryDto dto = ProcessSkillHandler.toSummary(skill);

        assertThat(dto.getName()).isEqualTo("code-review");
        assertThat(dto.getCategory()).isEqualTo("coding");
        assertThat(dto.isEnabled()).isTrue();
        assertThat(dto.getSource()).isEqualTo(SkillScope.PROJECT);
        assertThat(dto.getTriggers()).hasSize(2);
        assertThat(dto.getTriggers().get(0).getType()).isEqualTo(SkillTriggerType.KEYWORDS);
        assertThat(dto.getTriggers().get(0).getKeywords()).containsExactly("review", "code review");
        assertThat(dto.getTriggers().get(1).getType()).isEqualTo(SkillTriggerType.PATTERN);
        assertThat(dto.getTriggers().get(1).getPattern()).isEqualTo("\\brefactor\\b");
        assertThat(dto.getLifecycle()).isEqualTo("sticky");
        assertThat(dto.getTools()).containsExactly("file_read", "exec_check");
        assertThat(dto.getManualPaths()).containsExactly("_vance/manuals/code-review.md");
        assertThat(dto.getArguments()).hasSize(2);
        assertThat(dto.getArguments().get(0).getName()).isEqualTo("language");
        assertThat(dto.getArguments().get(0).getType()).isEqualTo("string");
        assertThat(dto.getArguments().get(1).isRequired()).isTrue();
        assertThat(dto.getReferenceDocs()).hasSize(1);
        assertThat(dto.getReferenceDocs().get(0).getTitle()).isEqualTo("Checklist");
        assertThat(dto.getReferenceDocs().get(0).getSummary()).isEqualTo("The review checklist");
        assertThat(dto.getReferenceDocs().get(0).getLoadMode()).isEqualTo(SkillReferenceDocLoadMode.ON_DEMAND);
        assertThat(dto.getScripts()).hasSize(1);
        assertThat(dto.getScripts().get(0).getName()).isEqualTo("run-lint");
        assertThat(dto.getScripts().get(0).getTarget()).isEqualTo(ScriptTarget.BRAIN);
        assertThat(dto.getScripts().get(0).getParams()).hasSize(1);
        assertThat(dto.getScripts().get(0).getParams().get(0).getName()).isEqualTo("fix");
        // Commands render back to the canonical author string, not the parsed tuple.
        assertThat(dto.getActivate()).containsExactly("guard script _vance/guards/review.js");
        assertThat(dto.getDeactivate()).containsExactly("guard clear");
    }

    @Test
    void toSummary_shotLifecycleIsSignalledAndDeactivateStaysEmpty() {
        ResolvedSkill skill = new ResolvedSkill(
                "code-guard",
                "Code Guard",
                "Config macro",
                "1.0.0",
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                SkillScope.RESOURCE,
                List.of(EngineCommand.parse("guard script g.js")),
                List.of(),
                SkillLifecycle.SHOT,
                false,
                List.of(),
                null);

        SkillSummaryDto dto = ProcessSkillHandler.toSummary(skill);

        // A shot skill never becomes active — the lifecycle string is the
        // only signal a client has to explain that ▶ fires a macro.
        assertThat(dto.getLifecycle()).isEqualTo("shot");
        assertThat(dto.getDeactivate()).isEmpty();
        assertThat(dto.getActivate()).containsExactly("guard script g.js");
    }

    @Test
    void toSummary_skillWithoutOptionalMetadata_yieldsEmptyCollections() {
        ResolvedSkill skill = new ResolvedSkill(
                "plain-skill",
                "Plain",
                "No optional metadata",
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null);

        SkillSummaryDto dto = ProcessSkillHandler.toSummary(skill);

        assertThat(dto.getTriggers()).isEmpty();
        assertThat(dto.getTools()).isEmpty();
        assertThat(dto.getManualPaths()).isEmpty();
        assertThat(dto.getArguments()).isEmpty();
        assertThat(dto.getReferenceDocs()).isEmpty();
        assertThat(dto.getScripts()).isEmpty();
        assertThat(dto.getActivate()).isEmpty();
        assertThat(dto.getDeactivate()).isEmpty();
    }
}
