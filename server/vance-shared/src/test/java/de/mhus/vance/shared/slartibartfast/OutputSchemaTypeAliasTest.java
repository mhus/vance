package de.mhus.vance.shared.slartibartfast;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code VOGON_STRATEGY} was renamed to {@link OutputSchemaType#VOGON_PLAN}.
 *
 * <p>A Slart process that was mid-run at deploy time has the old name
 * persisted in {@code engineParams.architectState}, and
 * {@code SlartibartfastEngine.loadState} deserialises that state on
 * <em>every</em> further turn — so without a backwards-compatible mapping the
 * process is terminally stuck, and the lenient spawn-parameter fallback never
 * sees it. The alias is what makes the rename survivable.
 */
class OutputSchemaTypeAliasTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void aPersistedStateWrittenBeforeTheRenameStillLoads() {
        // Round-trip a real state rather than hand-building a partial map:
        // what loadState() reads back is a full serialisation, and
        // ArchitectState has primitive fields that a partial map leaves
        // unset — that would fail the read for a reason that has nothing
        // to do with the rename this test is about.
        ArchitectState state = ArchitectState.builder()
                .runId("3a4f7c91")
                .userDescription("write me a story")
                .outputSchemaType(OutputSchemaType.VOGON_PLAN)
                .build();
        Map<String, Object> persisted =
                new LinkedHashMap<>(mapper.convertValue(state, Map.class));
        persisted.put("outputSchemaType", "VOGON_STRATEGY");

        ArchitectState back = mapper.convertValue(persisted, ArchitectState.class);

        assertThat(back.getOutputSchemaType()).isEqualTo(OutputSchemaType.VOGON_PLAN);
        assertThat(back.getRunId()).isEqualTo("3a4f7c91");
    }

    @Test
    void theCurrentNameIsTheOneWrittenBack() {
        assertThat(mapper.writeValueAsString(OutputSchemaType.VOGON_PLAN))
                .isEqualTo("\"VOGON_PLAN\"");
    }
}
