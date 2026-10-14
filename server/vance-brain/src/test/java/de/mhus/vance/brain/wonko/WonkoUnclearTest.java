package de.mhus.vance.brain.wonko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code cannot_determine} is a question to the model, not to the user
 * (plan §4a.8): it stays unclear until a recheck reaches a verdict for the
 * same reference, and then it disappears from the register.
 */
class WonkoUnclearTest {

    private final WonkoItemHandlers handlers = new WonkoItemHandlers(mock(LightLlmService.class));

    private static WonkoTurn turn(WonkoRegister register, WonkoState state) {
        return new WonkoTurn(null, null, state, register, new WonkoParams(Map.of()), "goal", null, "");
    }

    private static Map<String, Object> verdict(String verdict) {
        return Map.of("results", List.of(Map.of("passageId", "p1", "verdict", verdict, "reason", "because")));
    }

    @Test
    void cannotDetermine_isUnclear_untilARecheckDecides() {
        WonkoRegister register = WonkoTestData.register(MandateLevel.CONFIRM);
        WonkoState state = new WonkoState();
        WonkoClaim claim = new WonkoClaim();
        claim.setId("c2");
        claim.setText("Payment data is archived.");
        state.getClaims().add(claim);
        List<WonkoPassage> passages =
                List.of(new WonkoPassage("p1", "rules/archiving.md", "Archives must be unalterable.", null));
        WonkoItem item = WonkoItem.of("i1", WonkoItemType.COMPARE, 20);
        item.setClaimId("c2");
        WonkoTurn turn = turn(register, state);

        handlers.applyCompare(turn, item, passages, verdict(WonkoPrompts.CANNOT_DETERMINE));

        assertThat(register.getTopics()).singleElement().satisfies(t -> {
            assertThat(t.getStatus()).isEqualTo(WonkoTopicStatus.UNCLEAR);
            assertThat(t.getEvidenceRef()).isEqualTo("rules/archiving.md");
            assertThat(state.getTopicClaims()).containsEntry(t.getKey(), "c2");
        });

        handlers.applyCompare(turn, item, passages, verdict("satisfies"));

        assertThat(register.getTopics()).isEmpty();
    }
}
