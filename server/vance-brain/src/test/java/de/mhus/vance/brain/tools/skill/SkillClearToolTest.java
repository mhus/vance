package de.mhus.vance.brain.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent-side clear entry: thin adapter, so the contract here is that
 * it forwards to the /skill-clear funnel verbatim and reports the states
 * the funnel resolves to — cleared, recipe-bound-kept (skills.md §7a)
 * and not-active — as honest notes instead of silent no-ops.
 */
class SkillClearToolTest {

    private ThinkProcessService thinkProcessService;
    private SkillSteerProcessor steerProcessor;
    private SkillClearTool tool;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        steerProcessor = mock(SkillSteerProcessor.class);
        tool = new SkillClearTool(thinkProcessService, steerProcessor);
    }

    private ToolInvocationContext ctx(String processId) {
        return new ToolInvocationContext("acme", "web", null, processId, "alice");
    }

    private ThinkProcessDocument process(ActiveSkillRefEmbedded... active) {
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .sessionId("s1")
                .activeSkills(List.of(active))
                .build();
    }

    private ActiveSkillRefEmbedded ref(String name, boolean fromRecipe) {
        return ActiveSkillRefEmbedded.builder()
                .name(name)
                .fromRecipe(fromRecipe)
                .build();
    }

    @Test
    void invoke_namedClearForwardsToTheFunnelAndReportsCleared() {
        when(thinkProcessService.findById("p1"))
                .thenReturn(Optional.of(process(ref("design-blueprint", false), ref("other", false))));
        when(steerProcessor.clear(any(), anyString())).thenReturn(List.of(ref("other", false)));

        Map<String, Object> out = tool.invoke(Map.of("name", "design-blueprint"), ctx("p1"));

        verify(steerProcessor).clear(any(), eq("design-blueprint"));
        assertThat(out.get("cleared")).isEqualTo(List.of("design-blueprint"));
        assertThat(out.get("keptRecipeBound")).isEqualTo(List.of());
        assertThat(out.get("activeSkills")).isEqualTo(List.of("other"));
        assertThat((String) out.get("note"))
                .contains("cleared")
                .contains("design-blueprint")
                .contains("next turn");
    }

    @Test
    void invoke_recipeBoundSkillIsKeptAndReportedNotCleared() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process(ref("typescript-style", true))));
        when(steerProcessor.clear(any(), anyString())).thenReturn(List.of(ref("typescript-style", true)));

        Map<String, Object> out = tool.invoke(Map.of("name", "typescript-style"), ctx("p1"));

        assertThat(out.get("cleared")).isEqualTo(List.of());
        assertThat((String) out.get("note")).contains("recipe-bound").contains("process lifetime");
    }

    @Test
    void invoke_notActiveSkillIsAnHonestNoOp() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process(ref("other", false))));
        when(steerProcessor.clear(any(), anyString())).thenReturn(List.of(ref("other", false)));

        Map<String, Object> out = tool.invoke(Map.of("name", "ghost"), ctx("p1"));

        assertThat(out.get("cleared")).isEqualTo(List.of());
        assertThat((String) out.get("note")).contains("was not active");
    }

    @Test
    void invoke_withoutNameClearsAllNonRecipeSkills() {
        when(thinkProcessService.findById("p1"))
                .thenReturn(Optional.of(process(ref("design-blueprint", false), ref("typescript-style", true))));
        when(steerProcessor.clearAll(any())).thenReturn(List.of(ref("typescript-style", true)));

        Map<String, Object> out = tool.invoke(Map.of(), ctx("p1"));

        verify(steerProcessor).clearAll(any());
        verify(steerProcessor, never()).clear(any(), anyString());
        assertThat(out.get("cleared")).isEqualTo(List.of("design-blueprint"));
        assertThat(out.get("keptRecipeBound")).isEqualTo(List.of("typescript-style"));
        assertThat((String) out.get("note"))
                .contains("design-blueprint")
                .contains("Recipe-bound (kept): typescript-style");
    }

    @Test
    void invoke_clearAllWithoutClearableSkillsSaysSo() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.of(process()));
        when(steerProcessor.clearAll(any())).thenReturn(List.of());

        Map<String, Object> out = tool.invoke(Map.of(), ctx("p1"));

        assertThat((String) out.get("note")).isEqualTo("No clearable skills were active.");
    }

    @Test
    void invoke_withoutProcessIdRefuses() {
        assertThatThrownBy(() -> tool.invoke(Map.of("name", "s"), ctx(null)))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("think-process");
        verify(steerProcessor, never()).clear(any(), anyString());
        verify(steerProcessor, never()).clearAll(any());
    }
}
