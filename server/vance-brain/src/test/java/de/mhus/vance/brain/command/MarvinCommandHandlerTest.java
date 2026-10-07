package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.brain.marvin.MarvinEngine;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code //marvin} diagnostic: read-only, no lane, the full tree
 * picture without waking the identity — and it works headless, where
 * there is no identity at all.
 */
class MarvinCommandHandlerTest {

    private MarvinEngine engine;
    private MarvinCommandHandler handler;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        engine = mock(MarvinEngine.class);
        handler = new MarvinCommandHandler(engine);
        process = new ThinkProcessDocument();
        process.setId("proc1");
        process.setName("deep-think");
        process.setTenantId("t");
        process.setProjectId("p");
        process.setThinkEngine(MarvinEngine.NAME);
    }

    @Test
    void verbIsTheEngineNameAndNeverQueuesOnTheLane() {
        assertThat(handler.verb()).isEqualTo("marvin");
        assertThat(handler.runsOnLane()).isFalse();
    }

    @Test
    void aNonMarvinProcessIsRefused() {
        process.setThinkEngine("arthur");

        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("arthur");
    }

    @Test
    void anUnknownSubcommandIsNamed() {
        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "frood")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("frood").contains("info").contains("state");
    }

    @Test
    void withoutATreeTheInfoNamesTheGap() {
        when(engine.readTreeStatus(process)).thenReturn(Map.of("run", false));

        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("no tree yet");
    }

    @Test
    void stateRendersTheOneLiner() {
        when(engine.readTreeStatus(process)).thenReturn(liveStatus());

        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "state")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).isEqualTo("LIVE, 1 done / 1 running / 2 pending / 0 failed");
    }

    @Test
    void infoRendersTheFullTreePicture() {
        when(engine.readTreeStatus(process)).thenReturn(finishedStatus());

        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "info")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message())
                .contains("finished")
                .contains("Goal: Find the best database")
                .contains("2 done, 0 running, 0 waiting, 0 pending, 1 failed (3 total)")
                .contains("Result: the answer (truncated at 300 chars)");
        assertThat(result.message()).doesNotContain("Open questions");
    }

    @Test
    void infoListsOpenInboxQuestions() {
        Map<String, Object> status = liveStatus();
        status.put("openQuestions", List.of("Which dataset?", "Green or blue?"));
        when(engine.readTreeStatus(process)).thenReturn(status);

        EngineCommandResult result = handler.handle(process, new EngineCommand("marvin", Map.of("text", "")));

        assertThat(result.message())
                .contains("Open questions")
                .contains("Which dataset?")
                .contains("Green or blue?");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static Map<String, Object> liveStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("run", true);
        status.put("lifecycle", "LIVE");
        status.put("goal", "Find the best database");
        status.put("done", 1);
        status.put("running", 1);
        status.put("waiting", 0);
        status.put("pending", 2);
        status.put("failed", 0);
        status.put("skipped", 0);
        status.put("nodeCount", 4);
        status.put("currentNode", "Compare Mongo and Postgres");
        status.put("currentPhase", "SCOPE");
        status.put("openQuestions", List.of());
        return status;
    }

    private static Map<String, Object> finishedStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("run", true);
        status.put("lifecycle", "FINISHED");
        status.put("goal", "Find the best database");
        status.put("done", 2);
        status.put("running", 0);
        status.put("waiting", 0);
        status.put("pending", 0);
        status.put("failed", 1);
        status.put("skipped", 0);
        status.put("nodeCount", 3);
        status.put("openQuestions", List.of());
        status.put("result", "the answer (truncated at 300 chars) " + "x".repeat(400));
        return status;
    }
}
