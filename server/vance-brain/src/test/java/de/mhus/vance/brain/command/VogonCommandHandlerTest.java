package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.vogon.VogonEngine;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code //vogon} diagnostic: read-only, no lane, the full run picture
 * without waking the identity — and it works headless, where there is no
 * identity at all.
 */
class VogonCommandHandlerTest {

    private MagratheaStateProjector projector;
    private MagratheaGateChatAnswerService gateAnswers;
    private VogonCommandHandler handler;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        projector = mock(MagratheaStateProjector.class);
        gateAnswers = mock(MagratheaGateChatAnswerService.class);
        handler = new VogonCommandHandler(projector, gateAnswers);
        process = new ThinkProcessDocument();
        process.setId("proc1");
        process.setName("release-run");
        process.setTenantId("t");
        process.setProjectId("p");
        process.setThinkEngine(VogonEngine.NAME);
    }

    @Test
    void verbIsTheEngineNameAndNeverQueuesOnTheLane() {
        assertThat(handler.verb()).isEqualTo("vogon");
        assertThat(handler.runsOnLane()).isFalse();
    }

    @Test
    void withoutARunTheInfoNamesTheGap() {
        EngineCommandResult result = handler.handle(process, new EngineCommand("vogon", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("no run yet");
    }

    @Test
    void stateRendersTheOneLiner() {
        givenRun(MagratheaRunStatus.RUNNING, "review");

        EngineCommandResult result = handler.handle(process, new EngineCommand("vogon", Map.of("text", "state")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).isEqualTo("running, waterfall, review");
    }

    @Test
    void infoRendersTheFullRunPicture() {
        givenRun(MagratheaRunStatus.DONE, "publish");

        EngineCommandResult result = handler.handle(process, new EngineCommand("vogon", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message())
                .contains("finished")
                .contains("Plan: waterfall")
                .contains("Current state: publish")
                .contains("Result: {version=1.2.3}");
    }

    @Test
    void anUnknownSubcommandIsAnError() {
        EngineCommandResult result = handler.handle(process, new EngineCommand("vogon", Map.of("text", "explode")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("Unknown subcommand 'explode'");
    }

    @Test
    void aForeignEngineIsRejected() {
        process.setThinkEngine("ford");

        EngineCommandResult result = handler.handle(process, new EngineCommand("vogon", Map.of("text", "")));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
    }

    // ── helpers ────────────────────────────────────────────────────

    /** A projected run with the given status and current state. */
    private void givenRun(MagratheaRunStatus status, String currentState) {
        process.setEngineParams(new java.util.LinkedHashMap<>(Map.of(VogonEngine.PARAM_RUN_ID, "run-7")));
        MagratheaProcessDto dto = MagratheaProcessDto.builder()
                .workflowRunId("run-7")
                .workflowName("waterfall")
                .status(status)
                .currentState(currentState)
                .result(Map.of("version", "1.2.3"))
                .build();
        when(projector.project("t", "p", "run-7")).thenReturn(Optional.of(dto));
        when(gateAnswers.findOpenGateItem("t", "run-7")).thenReturn(Optional.empty());
    }
}
