package de.mhus.vance.brain.benjy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The digest renders bounded: completed items collapse, facts truncate,
 * and the structure stays fixed — small controller models get a small,
 * stable window (§9a).
 */
class BenjyDigestTest {

    @Test
    void render_containsGoalCriteriaItemsQueueAndTrigger() {
        BenjyState state = new BenjyState();
        state.setInterpretedGoal("Implement login validation");
        state.getCriteria().add(BenjyState.Criterion.of("c1", "Tests pass", null));
        state.getCriteria().get(0).setStatus("pending");
        BenjyState.Item item = BenjyState.Item.of("1", "Write validation logic");
        item.setStatus("in_progress");
        item.addFact("check FAILED: exit 1 — ImportError");
        state.getItems().add(item);
        state.getQueue().add(BenjyState.QueuedTask.of("t2", BenjyTaskTypes.ROUTE, null));

        String digest = BenjyDigest.render(state, "The check failed for item #1");

        assertThat(digest)
                .contains("Implement login validation")
                .contains("[c1] Tests pass (pending)")
                .contains("[#1] Write validation logic — in_progress")
                .contains("check FAILED: exit 1 — ImportError")
                .contains("- route")
                .contains("The check failed for item #1");
    }

    @Test
    void render_fallsBackToRawGoalWhenUninterpreted() {
        BenjyState state = new BenjyState();
        state.setGoal("Do the thing");
        assertThat(BenjyDigest.render(state, null)).contains("Do the thing");
    }

    @Test
    void render_truncatesLongFacts() {
        BenjyState state = new BenjyState();
        state.setGoal("g");
        BenjyState.Item item = BenjyState.Item.of("1", "i");
        item.addFact("x".repeat(2000));
        state.getItems().add(item);
        assertThat(BenjyDigest.render(state, null)).doesNotContain("x".repeat(400));
    }

    @Test
    void render_countsCompletedAttemptsNotTheNextOne() {
        BenjyState state = new BenjyState();
        state.setGoal("g");
        BenjyState.Item once = BenjyState.Item.of("1", "i");
        once.setAttempts(1);
        BenjyState.Item twice = BenjyState.Item.of("2", "j");
        twice.setAttempts(2);
        state.getItems().add(once);
        state.getItems().add(twice);

        String digest = BenjyDigest.render(state, null);

        // The count names finished attempts — a route model must not read
        // an attempt as in progress that has not started.
        assertThat(digest).contains("(1 attempt done)").contains("(2 attempts done)");
    }

    @Test
    void renderItem_carriesItemGoalCriteriaResultAndFacts() {
        BenjyState state = new BenjyState();
        state.setInterpretedGoal("Implement login validation");
        state.getCriteria().add(BenjyState.Criterion.of("c1", "Tests pass", null));
        BenjyState.Item item = BenjyState.Item.of("1", "Write validation logic");
        item.setLastResult("Created LoginValidator.java, tests pending");
        item.addFact("attempt 1 finished");

        String view = BenjyDigest.renderItem(state, item, item.getLastResult());

        assertThat(view)
                .contains("[#1] Write validation logic")
                .contains("Implement login validation")
                .contains("[c1] Tests pass")
                .contains("Created LoginValidator.java, tests pending")
                .contains("attempt 1 finished");
    }

    @Test
    void renderItem_marksMissingWorkerResult() {
        BenjyState state = new BenjyState();
        state.setGoal("g");
        BenjyState.Item item = BenjyState.Item.of("1", "i");
        assertThat(BenjyDigest.renderItem(state, item, null)).contains("(no reply)");
    }
}
