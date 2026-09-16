package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.api.skills.SkillTriggerDto;
import de.mhus.vance.api.skills.SkillTriggerType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-renderer tests for the {@code /skill} line output and the
 * {@code /ui-skill} row assembly — the sentences the foot prints must
 * carry the same distinctions the web composer renders: "already
 * active" vs "fired (macro)" vs fresh sticky activation, and
 * recipe-bound skills that stay on clear (skills.md §7a). The WS
 * round-trips themselves need a live connection — covered by the
 * ai-test lane.
 */
class UiSkillRenderTest {

    // ── SkillCommandHelper renderers ────────────────────────────────────

    @Test
    void activationMessage_freshStickyActivation() {
        ProcessSkillResponse response = ProcessSkillResponse.builder()
                .newlyActivated(true)
                .lifecycle("sticky")
                .activeSkills(List.of(ref("code-review", false)))
                .build();

        assertThat(SkillCommandHelper.activationMessage("code-review", response, false, true))
                .isEqualTo("→ skill 'code-review' with args activated"
                        + " — in effect from the next turn. Active skills: 1");
    }

    @Test
    void activationMessage_alreadyActive() {
        ProcessSkillResponse response =
                ProcessSkillResponse.builder().newlyActivated(false).build();

        assertThat(SkillCommandHelper.activationMessage("code-review", response, false, false))
                .isEqualTo("→ skill 'code-review' is already active (arguments updated at most)");
    }

    @Test
    void activationMessage_shotSkillFiresAsMacro() {
        ProcessSkillResponse response = ProcessSkillResponse.builder()
                .newlyActivated(true)
                .lifecycle("shot")
                .build();

        assertThat(SkillCommandHelper.activationMessage("code-guard", response, false, false))
                .isEqualTo("→ skill 'code-guard' fired (macro)" + " — one-shot prompt/config, never becomes active");
    }

    @Test
    void clearMessage_recipeBoundSkillIsKeptNotCleared() {
        ProcessSkillResponse response = ProcessSkillResponse.builder()
                .activeSkills(List.of(ref("typescript-style", true)))
                .build();

        assertThat(SkillCommandHelper.clearMessage("typescript-style", response))
                .isEqualTo("kept 'typescript-style' — bundled by the recipe," + " stays active for the process");
    }

    @Test
    void clearMessage_clearedSkillLeavesTheList() {
        ProcessSkillResponse response =
                ProcessSkillResponse.builder().activeSkills(List.of()).build();

        assertThat(SkillCommandHelper.clearMessage("code-review", response))
                .isEqualTo("cleared 'code-review'. active skills now: 0");
    }

    @Test
    void clearMessage_clearAllNamesTheKeptRecipeBoundOnes() {
        ProcessSkillResponse response = ProcessSkillResponse.builder()
                .activeSkills(List.of(ref("typescript-style", true)))
                .build();

        assertThat(SkillCommandHelper.clearMessage(null, response))
                .isEqualTo("cleared all except recipe-bound (typescript-style)." + " active skills now: 1");
    }

    @Test
    void autoTriggerSuffix_listsKeywordsAndPatterns() {
        SkillSummaryDto dto = SkillSummaryDto.builder()
                .triggers(List.of(
                        SkillTriggerDto.builder()
                                .type(SkillTriggerType.KEYWORDS)
                                .keywords(List.of("review", "code review"))
                                .build(),
                        SkillTriggerDto.builder()
                                .type(SkillTriggerType.PATTERN)
                                .pattern("\\brefactor\\b")
                                .build()))
                .build();

        assertThat(SkillCommandHelper.autoTriggerSuffix(dto))
                .isEqualTo("  [auto: review, code review, /\\brefactor\\b/]");
    }

    @Test
    void autoTriggerSuffix_emptyForSkillsWithoutTriggers() {
        assertThat(SkillCommandHelper.autoTriggerSuffix(
                        SkillSummaryDto.builder().build()))
                .isEmpty();
    }

    // ── UiSkillCommand row assembly and formatting ──────────────────────

    @Test
    void mergeRows_pairsActiveRefsAndKeepsUnlistedActiveSkills() {
        SkillSummaryDto summary = summary("code-review", "sticky");
        ActiveSkillRefDto active = ref("code-review", false);
        ActiveSkillRefDto unlisted = ref("legacy-skill", false);

        List<UiSkillCommand.Row> rows = UiSkillCommand.mergeRows(List.of(summary), List.of(active, unlisted));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).summary().getName()).isEqualTo("code-review");
        assertThat(rows.get(0).ref()).isSameAs(active);
        // Active but not in the cascade listing any more — still a row.
        assertThat(rows.get(1).summary().getName()).isEqualTo("legacy-skill");
        assertThat(rows.get(1).ref()).isSameAs(unlisted);
    }

    @Test
    void formatRow_carriesActiveOnceRecipeAndMacroMarkers() {
        UiSkillCommand.Row plain = new UiSkillCommand.Row(summary("plain", "sticky"), null);
        UiSkillCommand.Row active = new UiSkillCommand.Row(summary("styled", "sticky"), ref("styled", false));
        UiSkillCommand.Row recipeBound = new UiSkillCommand.Row(summary("guarded", "sticky"), ref("guarded", true));
        UiSkillCommand.Row macro = new UiSkillCommand.Row(summary("macro-skill", "shot"), null);

        assertThat(UiSkillCommand.formatRow(plain)).startsWith("  plain");
        assertThat(UiSkillCommand.formatRow(active)).startsWith("✓ styled");
        assertThat(UiSkillCommand.formatRow(recipeBound)).contains("(recipe)");
        assertThat(UiSkillCommand.formatRow(macro)).contains("(macro)");
    }

    @Test
    void renderDetails_showsTheMetadataSections() {
        SkillSummaryDto skill = SkillSummaryDto.builder()
                .name("code-review")
                .title("Code Review")
                .description("Reviews code")
                .lifecycle("shot")
                .tools(List.of("file_read"))
                .activate(List.of("guard script g.js"))
                .build();

        String details = UiSkillCommand.renderDetails(skill);

        assertThat(details).contains("Title: Code Review");
        assertThat(details).contains("shot — fires once as a prompt/config macro");
        assertThat(details).contains("Description:\n  Reviews code");
        assertThat(details).contains("Tools:\n  file_read");
        assertThat(details).contains("On activation:\n  guard script g.js");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static ActiveSkillRefDto ref(String name, boolean fromRecipe) {
        return ActiveSkillRefDto.builder().name(name).fromRecipe(fromRecipe).build();
    }

    private static SkillSummaryDto summary(String name, String lifecycle) {
        return SkillSummaryDto.builder()
                .name(name)
                .title(name)
                .lifecycle(lifecycle)
                .enabled(true)
                .source(SkillScope.PROJECT)
                .build();
    }
}
