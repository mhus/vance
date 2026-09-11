package de.mhus.vance.brain.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.command.EngineCommandOutcome;
import de.mhus.vance.api.zaphod.HeadStatus;
import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodMode;
import de.mhus.vance.api.zaphod.ZaphodPattern;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.api.zaphod.ZaphodStatus;
import de.mhus.vance.brain.zaphod.ZaphodEngine;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ZaphodCommandHandler} — the read-only {@code //zaphod info}
 * verb: list / per-head detail / defined ERROR outcomes, the lane
 * bypass, and mode-agnostic state reads (BATCH and SESSION both ACK).
 */
class ZaphodCommandHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ZaphodCommandHandler handler = new ZaphodCommandHandler(objectMapper);

    @Test
    void verbIsZaphodAndRunsOffLane() {
        assertThat(handler.verb()).isEqualTo("zaphod");
        // Pure read — must not queue behind a stuck turn.
        assertThat(handler.runsOnLane()).isFalse();
    }

    @Test
    void info_withoutHead_rendersListAndStructuredValue() {
        ThinkProcessDocument process = zaphodProcess(sessionState());

        EngineCommandResult result = handler.handle(process, command("info"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("optimist").contains("skeptiker");
        assertThat(result.message()).contains("mode=SESSION").contains("turn=3");
        assertThat(result.message()).contains("Last synthesis: \"Use A\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertThat(value.get("mode")).isEqualTo("SESSION");
        assertThat(value.get("turnIndex")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heads = (List<Map<String, Object>>) value.get("heads");
        assertThat(heads).hasSize(2);
        assertThat(heads.get(0)).containsEntry("name", "optimist").containsEntry("replyCount", 1);
    }

    @Test
    void info_withHead_rendersHeadDetailsWithProcessPointer() {
        ThinkProcessDocument process = zaphodProcess(sessionState());

        EngineCommandResult result = handler.handle(process, command("info skeptiker"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("Head skeptiker");
        assertThat(result.message()).contains("recipe=council-member");
        assertThat(result.message()).contains("Process: zaphod-p1-skeptiker");
        assertThat(result.message()).contains("Failure: worker produced no assistant reply");
        assertThat(result.message()).contains("Persona: You are a skeptical reviewer");
    }

    @Test
    void info_unknownHead_listsValidNames() {
        ThinkProcessDocument process = zaphodProcess(sessionState());

        EngineCommandResult result = handler.handle(process, command("info nobody"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("nobody").contains("optimist").contains("skeptiker");
    }

    @Test
    void nonZaphodEngine_isDefinedError() {
        ThinkProcessDocument process = zaphodProcess(sessionState());
        process.setThinkEngine("ford");

        EngineCommandResult result = handler.handle(process, command("info"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("runs engine 'ford'");
    }

    @Test
    void missingState_isDefinedError() {
        ThinkProcessDocument process = zaphodProcess(null);

        EngineCommandResult result = handler.handle(process, command("info"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.ERROR);
        assertThat(result.message()).contains("has not started yet");
    }

    @Test
    void legacyStateWithoutModeField_normalisesToBatch() {
        // Pre-session-mode persisted state: no mode/turnIndex keys.
        // The handler normalises for display instead of crashing
        // (Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES on absent primitives).
        ZaphodState state = sessionState();
        Map<String, Object> raw = objectMapper.convertValue(state, Map.class);
        raw.remove("mode");
        raw.remove("turnIndex");
        raw.remove("turnGoal");
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("t")
                .sessionId("s1")
                .name("chat")
                .thinkEngine(ZaphodEngine.NAME)
                .engineParams(new LinkedHashMap<>(Map.of(ZaphodEngine.STATE_KEY, raw)))
                .build();

        EngineCommandResult result = handler.handle(process, command("info"));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("mode=BATCH").contains("turn=0");
    }

    @Test
    void batchStateAlsoAnswers() {
        ZaphodState batch = sessionState();
        batch.setMode(ZaphodMode.BATCH);
        ThinkProcessDocument process = zaphodProcess(batch);

        EngineCommandResult result = handler.handle(process, command(""));

        assertThat(result.outcome()).isEqualTo(EngineCommandOutcome.OK);
        assertThat(result.message()).contains("mode=BATCH");
    }

    // ──────────────────── fixtures ────────────────────

    private EngineCommand command(String text) {
        return EngineCommand.parse("//zaphod " + text);
    }

    private ZaphodState sessionState() {
        return ZaphodState.builder()
                .mode(ZaphodMode.SESSION)
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .turnIndex(3)
                .turnGoal("A or B?")
                .currentHeadIndex(2)
                .status(ZaphodStatus.DONE)
                .synthesisTitle("Use A")
                .synthesisSummary("A wins")
                .heads(List.of(
                        ZaphodHead.builder()
                                .name("optimist")
                                .recipe("council-member")
                                .status(HeadStatus.DONE)
                                .spawnedProcessId("c1")
                                .persona("You are an optimistic advisor.")
                                .replies(new ArrayList<>(List.of("A is great.")))
                                .build(),
                        ZaphodHead.builder()
                                .name("skeptiker")
                                .recipe("council-member")
                                .status(HeadStatus.FAILED)
                                .spawnedProcessId("c2")
                                .persona("You are a skeptical reviewer.")
                                .failureReason("worker produced no assistant reply in round 0")
                                .replies(new ArrayList<>())
                                .build()))
                .build();
    }

    private ThinkProcessDocument zaphodProcess(ZaphodState state) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (state != null) {
            params.put(ZaphodEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        }
        return ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("t")
                .sessionId("s1")
                .name("chat")
                .thinkEngine(ZaphodEngine.NAME)
                .engineParams(params)
                .build();
    }
}
