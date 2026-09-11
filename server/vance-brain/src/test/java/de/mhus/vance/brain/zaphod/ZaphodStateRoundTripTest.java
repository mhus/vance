package de.mhus.vance.brain.zaphod;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodMode;
import de.mhus.vance.api.zaphod.ZaphodPattern;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.api.zaphod.ZaphodStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Round-trip safety for the session-mode fields: a ZaphodState map
 * persisted <em>before</em> the session mode existed (no
 * {@code mode}/{@code turnIndex}/{@code turnGoal} keys) must
 * deserialise without throwing — Jackson 3 fails
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} on absent primitives, which is
 * why {@code turnIndex} is a nullable Integer. The nulls are
 * normalised to BATCH/0 in the engine's and the command handler's
 * loadState (covered by ZaphodSessionModeTest and
 * ZaphodCommandHandlerTest).
 */
class ZaphodStateRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void legacyBatchStateWithoutModeField_loadsAsBatch() {
        // Serialize a full state, then strip the mode field to simulate
        // a pre-session-mode persisted state.
        ZaphodState state = ZaphodState.builder()
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .status(ZaphodStatus.RUNNING)
                .heads(List.of(ZaphodHead.builder()
                        .name("optimist")
                        .recipe("ford")
                        .replies(new ArrayList<>(List.of("r")))
                        .build()))
                .build();
        Map<String, Object> raw = objectMapper.convertValue(state, Map.class);
        raw.remove("mode");
        raw.remove("turnIndex");
        raw.remove("turnGoal");

        ZaphodState loaded = objectMapper.convertValue(raw, ZaphodState.class);

        // Tolerant DTO: absent keys arrive as null (normalised to
        // BATCH/0 by the engine / command handler on load).
        assertThat(loaded.getMode()).isNull();
        assertThat(loaded.getTurnIndex()).isNull();
        assertThat(loaded.getTurnGoal()).isNull();
        assertThat(loaded.getHeads()).hasSize(1);
        assertThat(loaded.getHeads().get(0).getReplies()).containsExactly("r");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionState_survivesRoundTripWithTurnFields() {
        ZaphodState state = ZaphodState.builder()
                .mode(ZaphodMode.SESSION)
                .pattern(ZaphodPattern.COUNCIL)
                .maxRounds(1)
                .turnIndex(7)
                .turnGoal("What about C?")
                .status(ZaphodStatus.RUNNING)
                .synthesisTitle("Old title")
                .build();

        Map<String, Object> raw = objectMapper.convertValue(state, Map.class);
        ZaphodState loaded = objectMapper.convertValue(raw, ZaphodState.class);

        assertThat(loaded.getMode()).isEqualTo(ZaphodMode.SESSION);
        assertThat(loaded.getTurnIndex()).isEqualTo(7);
        assertThat(loaded.getTurnGoal()).isEqualTo("What about C?");
        assertThat(loaded.getSynthesisTitle()).isEqualTo("Old title");
    }
}
