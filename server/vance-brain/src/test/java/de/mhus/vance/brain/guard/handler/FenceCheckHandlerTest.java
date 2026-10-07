package de.mhus.vance.brain.guard.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.recipe.GuardPoint;
import de.mhus.vance.brain.script.GuardScriptHost;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Unit coverage of the {@code fence-check} guard handler's fence scan. */
class FenceCheckHandlerTest {

    private final List<String> prompts = new ArrayList<>();

    private GuardContext ctx(String output) {
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .tenantId("acme")
                .projectId("proj")
                .sessionId("s1")
                .build();
        GuardScriptHost host = new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                prompts.add(prompt);
                return true;
            }

            @Override
            public boolean deny(String reason) {
                throw new AssertionError("deny is not available at the stop point");
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                throw new AssertionError("not used here");
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new AssertionError("not used here");
            }
        };
        return new GuardContext(
                process,
                GuardPoint.STOP,
                "task",
                output,
                0,
                2,
                true,
                null,
                Map.of(),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(),
                host);
    }

    private String lastPrompt() {
        return prompts.isEmpty() ? null : prompts.get(prompts.size() - 1);
    }

    @Test
    void cleanOutput_staysQuiet() {
        new FenceCheckHandler().onStop(ctx("All done.\n\n```diagram\ngraph TD; A-->B;\n```\n\nDone."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void unclosedFence_isFlagged() {
        new FenceCheckHandler().onStop(ctx("Here you go:\n```diagram\ngraph TD; A-->B;"));
        assertThat(lastPrompt()).contains("never closed").contains("line 2");
    }

    @Test
    void emptyFence_isFlagged() {
        new FenceCheckHandler().onStop(ctx("```\n\n```\nfin"));
        assertThat(lastPrompt()).contains("empty body");
    }

    @Test
    void fourBacktickFence_keepsThreeBacktickContentAsBody() {
        // vance-columns: 4-backtick fence with 3-backtick content fences —
        // the inner fences must not close the outer one (CommonMark rule).
        String out = "````vance-columns\n```column\nleft\n```\n```column\nright\n```\n````";
        new FenceCheckHandler().onStop(ctx(out));
        assertThat(prompts).isEmpty();
    }

    @Test
    void inlineBackticks_areNotFences() {
        new FenceCheckHandler().onStop(ctx("Use `code` inline, or ```triple``` mid-line — not fences."));
        assertThat(prompts).isEmpty();
    }

    @Test
    void blankOutput_staysQuiet() {
        new FenceCheckHandler().onStop(ctx("   "));
        assertThat(prompts).isEmpty();
    }

    @Test
    void terminateHook_behavesLikeStop() {
        GuardContext ctx = ctx("```vance-callout\n unclosed");
        new FenceCheckHandler().onTerminate(ctx);
        assertThat(lastPrompt()).contains("never closed");
    }
}
