package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Round-trip of the persisted machine state through the
 * {@code engineParams.benjyState} map form (Zaphod persistence form):
 * whatever Jackson writes must load back into an equal state — a
 * crash-restart resume depends on the queue surviving the detour.
 */
class BenjyStateCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void state_roundTripThroughMap_preservesQueueItemsCriteriaAndInFlight() {
        BenjyState state = new BenjyState();
        state.setGoal("Add login validation");
        state.setInterpretedGoal("Implement login validation with tests");
        state.setTaskType(BenjyFeatureConfig.TASK_TYPE_CODING);
        state.getCriteria().add(BenjyState.Criterion.of("c1", "Tests pass", "req/checklist.yaml"));
        state.getCriteria().get(0).setStatus("pass");

        BenjyState.Item item = BenjyState.Item.of("1", "Write validation logic");
        item.setStatus("in_progress");
        item.setAttempts(2);
        item.addFact("check FAILED: exit 1 — ImportError");
        item.setLastResult("implemented, tests pending");
        state.getItems().add(item);

        BenjyState.QueuedTask task = BenjyState.QueuedTask.of("t3", BenjyTaskTypes.DO, "1");
        task.getPayload().put("chain", List.of(BenjyTaskTypes.CHECK, BenjyTaskTypes.EVALUATE));
        state.getQueue().add(task);

        BenjyState.InFlight inFlight = new BenjyState.InFlight();
        inFlight.setTaskId("t3");
        inFlight.setWorkerProcessId("w-47");
        inFlight.setItemId("1");
        inFlight.setRemainingChain(List.of(BenjyTaskTypes.CHECK, BenjyTaskTypes.EVALUATE));
        state.setInFlight(inFlight);
        state.setPendingQuestion("Which auth provider?");
        state.setReflected(false);
        state.getCounters().setRounds(7);
        state.getCounters().setLlmCalls(3);
        state.getCounters().setTokens(12345L);
        state.setNextTaskId(4);
        state.getCounters().setNoProgressStreak(9);
        state.setStagnationEscalated(true);
        state.setReflectNoCount(2);
        state.setTokenBudgetOffset(5000L);
        state.setPendingCheckpoint("tokens");

        Map<String, Object> serialized = mapper.convertValue(state, Map.class);
        // the engineParams storage adds a surrounding map — simulate it
        Map<String, Object> engineParams = new LinkedHashMap<>();
        engineParams.put(BenjyEngine.STATE_KEY, serialized);

        BenjyState loaded = mapper.convertValue(engineParams.get(BenjyEngine.STATE_KEY), BenjyState.class);

        assertThat(loaded.getGoal()).isEqualTo("Add login validation");
        assertThat(loaded.getInterpretedGoal()).isEqualTo("Implement login validation with tests");
        assertThat(loaded.getTaskType()).isEqualTo(BenjyFeatureConfig.TASK_TYPE_CODING);
        assertThat(loaded.getCriteria()).hasSize(1);
        assertThat(loaded.getCriteria().get(0).getText()).isEqualTo("Tests pass");
        assertThat(loaded.getCriteria().get(0).getSourceRef()).isEqualTo("req/checklist.yaml");
        assertThat(loaded.getCriteria().get(0).getStatus()).isEqualTo("pass");
        assertThat(loaded.getItems()).hasSize(1);
        assertThat(loaded.getItems().get(0).getAttempts()).isEqualTo(2);
        assertThat(loaded.getItems().get(0).getFacts()).containsExactly("check FAILED: exit 1 — ImportError");
        assertThat(loaded.getItems().get(0).getLastResult()).isEqualTo("implemented, tests pending");
        assertThat(loaded.getQueue()).hasSize(1);
        assertThat(loaded.getQueue().get(0).getType()).isEqualTo(BenjyTaskTypes.DO);
        assertThat(loaded.getQueue().get(0).getPayload())
                .containsEntry("chain", List.of(BenjyTaskTypes.CHECK, BenjyTaskTypes.EVALUATE));
        assertThat(loaded.getInFlight()).isNotNull();
        assertThat(loaded.getInFlight().getWorkerProcessId()).isEqualTo("w-47");
        assertThat(loaded.getInFlight().getRemainingChain())
                .containsExactly(BenjyTaskTypes.CHECK, BenjyTaskTypes.EVALUATE);
        assertThat(loaded.getPendingQuestion()).isEqualTo("Which auth provider?");
        assertThat(loaded.getCounters().getRounds()).isEqualTo(7);
        assertThat(loaded.getCounters().getTokens()).isEqualTo(12345L);
        assertThat(loaded.getCounters().getNoProgressStreak()).isEqualTo(9);
        assertThat(loaded.isStagnationEscalated()).isTrue();
        assertThat(loaded.getReflectNoCount()).isEqualTo(2);
        assertThat(loaded.getTokenBudgetOffset()).isEqualTo(5000L);
        assertThat(loaded.getPendingCheckpoint()).isEqualTo("tokens");
        assertThat(loaded.getNextTaskId()).isEqualTo(4);
    }

    @Test
    void emptyMap_loadsAsFreshState() {
        BenjyState loaded = mapper.convertValue(Map.of(), BenjyState.class);
        assertThat(loaded.getQueue()).isEmpty();
        assertThat(loaded.getItems()).isEmpty();
        assertThat(loaded.getInFlight()).isNull();
        assertThat(loaded.getTaskType()).isEqualTo(BenjyFeatureConfig.TASK_TYPE_INFO);
    }

    @Test
    void itemFacts_stayBounded() {
        BenjyState.Item item = BenjyState.Item.of("1", "x");
        for (int i = 0; i < 20; i++) {
            item.addFact("fact " + i);
        }
        assertThat(item.getFacts()).hasSize(12);
        assertThat(item.getFacts().getLast()).isEqualTo("fact 19");
    }

    @Test
    void itemLastResult_isBoundedOnSet() {
        BenjyState.Item item = BenjyState.Item.of("1", "x");
        item.setLastResult("a".repeat(9000));
        assertThat(item.getLastResult()).hasSize(4000);
    }
}
