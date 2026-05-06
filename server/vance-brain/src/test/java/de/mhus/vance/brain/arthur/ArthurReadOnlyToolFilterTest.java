package de.mhus.vance.brain.arthur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Validates {@link ArthurEngine#filterAllowedToolsForMode}: in
 * {@code EXPLORING} / {@code PLANNING} the tool set drops to the
 * read-only subset; in {@code NORMAL} / {@code EXECUTING} it is
 * passed through unchanged.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §15.1
 * "ArthurReadOnlyToolFilterTest".
 */
class ArthurReadOnlyToolFilterTest {

    private static final Set<String> WRITE_TOOLS = Set.of(
            "process_create",
            "process_create_delegate",
            "process_steer",
            "process_stop",
            "process_pause",
            "process_resume",
            "inbox_post");

    private static final Set<String> READ_TOOLS_SAMPLE = Set.of(
            "whoami",
            "current_time",
            "find_tools",
            "describe_tool",
            "doc_read",
            "doc_list",
            "doc_find",
            "scratchpad_get",
            "web_search",
            "process_list",
            "process_status",
            "recipe_list",
            "recipe_describe");

    private final ArthurEngine engine = newEngine();

    @Test
    void exploringMode_dropsAllWriteTools() {
        Set<String> base = engine.allowedTools();
        Set<String> filtered = engine.filterAllowedToolsForMode(base, ProcessMode.EXPLORING);
        assertThat(filtered).doesNotContainAnyElementsOf(WRITE_TOOLS);
    }

    @Test
    void exploringMode_keepsAllReadTools() {
        Set<String> base = engine.allowedTools();
        Set<String> filtered = engine.filterAllowedToolsForMode(base, ProcessMode.EXPLORING);
        for (String readTool : READ_TOOLS_SAMPLE) {
            assertThat(base).as("baseAllowed must contain " + readTool).contains(readTool);
            assertThat(filtered).as("filtered must contain " + readTool).contains(readTool);
        }
    }

    @Test
    void planningMode_appliesSameFilterAsExploring() {
        Set<String> base = engine.allowedTools();
        Set<String> exploring = engine.filterAllowedToolsForMode(base, ProcessMode.EXPLORING);
        Set<String> planning = engine.filterAllowedToolsForMode(base, ProcessMode.PLANNING);
        assertThat(planning).containsExactlyInAnyOrderElementsOf(exploring);
    }

    @Test
    void normalMode_passesThroughUnchanged() {
        Set<String> base = engine.allowedTools();
        Set<String> filtered = engine.filterAllowedToolsForMode(base, ProcessMode.NORMAL);
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(base);
    }

    @Test
    void executingMode_passesThroughUnchanged() {
        Set<String> base = engine.allowedTools();
        Set<String> filtered = engine.filterAllowedToolsForMode(base, ProcessMode.EXECUTING);
        assertThat(filtered).containsExactlyInAnyOrderElementsOf(base);
    }

    @Test
    void emptyBase_inExploring_collapsesToReadOnlyDefault() {
        // Empty base = "no restriction"; under Plan-Mode the read-only set
        // becomes the effective allow-set so the LLM doesn't suddenly see
        // every tool the dispatcher has registered.
        Set<String> filtered = engine.filterAllowedToolsForMode(Set.of(), ProcessMode.EXPLORING);
        assertThat(filtered).isNotEmpty();
        assertThat(filtered).doesNotContainAnyElementsOf(WRITE_TOOLS);
        assertThat(filtered).contains("doc_read", "web_search", "current_time");
    }

    @Test
    void emptyBase_inNormal_staysEmpty() {
        Set<String> filtered = engine.filterAllowedToolsForMode(Set.of(), ProcessMode.NORMAL);
        assertThat(filtered).isEmpty();
    }

    private static ArthurEngine newEngine() {
        // filterAllowedToolsForMode is a pure function on Set<String> — it
        // does not touch any of the wired services. Mocks satisfy the
        // constructor without needing a real Spring context.
        return new ArthurEngine(
                mock(ThinkProcessService.class),
                new tools.jackson.databind.ObjectMapper(),
                mock(StreamingProperties.class),
                mock(ArthurProperties.class),
                mock(RecipeLoader.class),
                mock(ModelCatalog.class),
                mock(LlmCallTracker.class),
                mock(MemoryContextLoader.class),
                mock(EnginePromptResolver.class),
                mock(EngineChatFactory.class),
                mock(SkillTriggerMatcher.class),
                mock(EngineMessageRouter.class),
                mock(PlanModeEventEmitter.class));
    }
}
