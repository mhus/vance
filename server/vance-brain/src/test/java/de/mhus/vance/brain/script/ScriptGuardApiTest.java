package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptGuardScratchApi;
import de.mhus.vance.brain.script.VanceScriptApi.ScriptHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@code vance.guard} — the yield-context fields and
 * the cap-aware {@link ScriptGuardApi#continueWith(String)} hook. The
 * host is a stub {@link GuardScriptHost}; no service or GraalJS context
 * is needed.
 */
class ScriptGuardApiTest {

    private ScriptGuardApi guard(long round, long maxRounds, GuardScriptHost host) {
        return new ScriptGuardApi(
                "the task", "the output", round, maxRounds, /*naturalStop*/ true,
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                host);
    }

    @Test
    void contextFields_areExposed() {
        ScriptGuardApi g = guard(1, 3, prompt -> true);
        assertThat(g.task).isEqualTo("the task");
        assertThat(g.output).isEqualTo("the output");
        assertThat(g.round).isEqualTo(1);
        assertThat(g.maxRounds).isEqualTo(3);
        assertThat(g.naturalStop).isTrue();
        assertThat(g.loopValues).isNotNull();
        assertThat(g.sessionValues).isNotNull();
    }

    @Test
    void continueWith_delegatesToHost_andReturnsResult() {
        List<String> injected = new ArrayList<>();
        GuardScriptHost host = prompt -> {
            injected.add(prompt);
            return true;
        };
        ScriptGuardApi g = guard(0, 3, host);

        boolean result = g.continueWith("run the tests");

        assertThat(result).isTrue();
        assertThat(injected).containsExactly("run the tests");
    }

    @Test
    void continueWith_returnsFalse_whenHostCaps() {
        // Host models the round cap being reached: no injection, returns false.
        ScriptGuardApi g = guard(3, 3, prompt -> false);
        assertThat(g.continueWith("nudge")).isFalse();
    }

    @Test
    void continueWith_blankPrompt_throws_andDoesNotCallHost() {
        List<String> injected = new ArrayList<>();
        ScriptGuardApi g = guard(0, 3, prompt -> {
            injected.add(prompt);
            return true;
        });
        assertThatThrownBy(() -> g.continueWith("   "))
                .isInstanceOf(ScriptHostException.class);
        assertThat(injected).isEmpty();
    }
}
