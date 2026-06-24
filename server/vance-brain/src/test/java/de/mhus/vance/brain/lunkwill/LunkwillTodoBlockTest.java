package de.mhus.vance.brain.lunkwill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renderer-only test for {@link LunkwillEngine#buildTodoListBlock}.
 * No streaming, no LLM, no tool dispatch — just the prompt-block
 * builder that injects the per-turn TodoList summary into the system
 * message stack.
 */
class LunkwillTodoBlockTest {

    private LunkwillEngine engine;

    @BeforeEach
    void setUp() {
        engine = new LunkwillEngine(
                mock(ThinkProcessService.class),
                new LunkwillProperties(),
                mock(EngineChatFactory.class),
                mock(LlmCallTracker.class),
                new StreamingProperties(),
                JsonMapper.builder().build(),
                mock(EnginePromptResolver.class),
                mock(SystemPromptComposer.class),
                mock(SkillResolver.class),
                mock(SkillPromptComposer.class),
                mock(SessionService.class),
                mock(de.mhus.vance.brain.memory.MemoryContextLoader.class),
                mock(de.mhus.vance.brain.ai.ModelCatalog.class),
                mock(de.mhus.vance.brain.memory.MemoryCompactionService.class));
    }

    @Test
    void emptyTodos_returnsEmpty_blockIsSkipped() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        // Default: no todos set.
        assertThat(engine.buildTodoListBlock(process)).isEmpty();

        process.setTodos(List.of());
        assertThat(engine.buildTodoListBlock(process)).isEmpty();
    }

    @Test
    void populatedTodos_includesMarkersAndCurrentStep() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setTodos(List.of(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED)
                        .content("Read parser").build(),
                TodoItem.builder().id("2").status(TodoStatus.IN_PROGRESS)
                        .content("Add streaming variant")
                        .activeForm("Adding streaming variant").build(),
                TodoItem.builder().id("3").status(TodoStatus.PENDING)
                        .content("Migrate callers").build()));

        String block = engine.buildTodoListBlock(process);

        assertThat(block).contains("## Active Plan");
        assertThat(block).contains("[✓] (id=1) Read parser");
        // IN_PROGRESS item shows activeForm, not content
        assertThat(block).contains("[~] (id=2) Adding streaming variant");
        assertThat(block).contains("[ ] (id=3) Migrate callers");
        // Current step is the first non-COMPLETED — id=2
        assertThat(block).contains("Current step: **(id=2)**");
        // Tool hints
        assertThat(block).contains("todo_update");
        assertThat(block).contains("todo_write");
        // Hard rules
        assertThat(block).contains("Never downgrade");
    }

    @Test
    void allCompleted_promptsForTaskComplete() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setTodos(List.of(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.COMPLETED).content("b").build()));

        String block = engine.buildTodoListBlock(process);

        assertThat(block).contains("All steps COMPLETED");
        assertThat(block).contains("task-complete");
        assertThat(block).doesNotContain("Current step:");
    }

    @Test
    void currentStepIsFirstPending_whenNoneInProgress() {
        ThinkProcessDocument process = new ThinkProcessDocument();
        process.setTodos(List.of(
                TodoItem.builder().id("1").status(TodoStatus.COMPLETED).content("a").build(),
                TodoItem.builder().id("2").status(TodoStatus.PENDING).content("b").build(),
                TodoItem.builder().id("3").status(TodoStatus.PENDING).content("c").build()));

        String block = engine.buildTodoListBlock(process);

        // First PENDING wins because there's no IN_PROGRESS
        assertThat(block).contains("Current step: **(id=2)** b");
    }
}
