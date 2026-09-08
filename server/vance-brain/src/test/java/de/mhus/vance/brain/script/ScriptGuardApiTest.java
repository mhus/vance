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
 * Unit coverage of {@code vance.guard} — the run-context fields
 * (including point/command), the cap-aware {@link ScriptGuardApi#continueWith(String)}
 * hook, {@code deny} and {@code activateSkill}. The hosts are stubs of the
 * point-specific {@link GuardScriptHost}s; no service or GraalJS context
 * is needed.
 */
class ScriptGuardApiTest {

    private ScriptGuardApi guard(long round, long maxRounds, GuardScriptHost host) {
        return stopGuard(round, maxRounds, host);
    }

    private ScriptGuardApi stopGuard(long round, long maxRounds, GuardScriptHost host) {
        return new ScriptGuardApi(
                "the task",
                "the output",
                round,
                maxRounds, /*naturalStop*/
                true,
                "stop",
                null,
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                host);
    }

    private ScriptGuardApi commandGuard(GuardScriptHost host) {
        return new ScriptGuardApi(
                "the task",
                "",
                0,
                0, /*naturalStop*/
                false,
                "command",
                java.util.Map.of("name", "mode.set", "args", java.util.Map.of("text", "review")),
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                new ScriptGuardScratchApi(new LinkedHashMap<>()),
                host);
    }

    /** A stop-point host stub: continueWith works, everything else throws. */
    private GuardScriptHost stopHost(List<String> injected, boolean capReached) {
        return new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                injected.add(prompt);
                return !capReached;
            }

            @Override
            public boolean deny(String reason) {
                throw new ScriptHostException("deny: not available at point 'stop'", null);
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                throw new ScriptHostException("activateSkill: stub", null);
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new ScriptHostException("setTurnPrompt: stub", null);
            }
        };
    }

    @Test
    void contextFields_areExposed() {
        List<String> injected = new ArrayList<>();
        ScriptGuardApi g = guard(1, 3, stopHost(injected, false));
        assertThat(g.task).isEqualTo("the task");
        assertThat(g.output).isEqualTo("the output");
        assertThat(g.round).isEqualTo(1);
        assertThat(g.maxRounds).isEqualTo(3);
        assertThat(g.naturalStop).isTrue();
        assertThat(g.point).isEqualTo("stop");
        assertThat(g.command).isNull();
        assertThat(g.loopValues).isNotNull();
        assertThat(g.sessionValues).isNotNull();
    }

    @Test
    void commandPoint_exposesPointAndCommand() {
        ScriptGuardApi g = commandGuard(new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                return false;
            }

            @Override
            public boolean deny(String reason) {
                return true;
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                return false;
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new ScriptHostException("setTurnPrompt: stub", null);
            }
        });
        assertThat(g.point).isEqualTo("command");
        assertThat(g.command.get("name")).isEqualTo("mode.set");
        assertThat(g.command.get("args")).isEqualTo(java.util.Map.of("text", "review"));
    }

    @Test
    void continueWith_delegatesToHost_andReturnsResult() {
        List<String> injected = new ArrayList<>();
        ScriptGuardApi g = guard(0, 3, stopHost(injected, false));

        boolean result = g.continueWith("run the tests");

        assertThat(result).isTrue();
        assertThat(injected).containsExactly("run the tests");
    }

    @Test
    void continueWith_returnsFalse_whenHostCaps() {
        // Host models the round cap being reached: no injection, returns false.
        ScriptGuardApi g = guard(3, 3, stopHost(new ArrayList<>(), true));
        assertThat(g.continueWith("nudge")).isFalse();
    }

    @Test
    void continueWith_blankPrompt_throws_andDoesNotCallHost() {
        List<String> injected = new ArrayList<>();
        ScriptGuardApi g = guard(0, 3, stopHost(injected, false));
        assertThatThrownBy(() -> g.continueWith("   ")).isInstanceOf(ScriptHostException.class);
        assertThat(injected).isEmpty();
    }

    @Test
    void deny_blankReason_throws() {
        ScriptGuardApi g = commandGuard(new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                return false;
            }

            @Override
            public boolean deny(String reason) {
                return true;
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                return false;
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new ScriptHostException("setTurnPrompt: stub", null);
            }
        });
        assertThatThrownBy(() -> g.deny("  ")).isInstanceOf(ScriptHostException.class);
    }

    @Test
    void continueWith_atCommandPoint_surfacesHostError() {
        // A start/command-point host throws for continueWith — calling it is
        // a script bug, visible as ScriptHostException (fail strategy is
        // per-point at the service).
        ScriptGuardApi g = commandGuard(new GuardScriptHost() {
            @Override
            public boolean continueWith(String prompt) {
                throw new ScriptHostException("continueWith: not available at point 'command'", null);
            }

            @Override
            public boolean deny(String reason) {
                return true;
            }

            @Override
            public boolean activateSkill(String skillName, String args) {
                return false;
            }

            @Override
            public void setTurnPrompt(String text) {
                throw new ScriptHostException("setTurnPrompt: stub", null);
            }
        });
        assertThatThrownBy(() -> g.continueWith("nudge"))
                .isInstanceOf(ScriptHostException.class)
                .hasMessageContaining("not available at point 'command'");
    }
}
