package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** With {@code vance.workspace.mode=off} only a fully enforced sandbox lets the pod start (E5). */
class ScriptSandboxSelfTestTest {

    @Test
    void strictlyPassed_everyLimitEnforced_isTrue() {
        assertThat(ScriptSandboxSelfTest.strictlyPassed(new ScriptSandboxLimits.SelfTestResult(true, true, true)))
                .isTrue();
    }

    @Test
    void strictlyPassed_runtimeWithoutHeapLimit_isFalse() {
        ScriptSandboxLimits.SelfTestResult noHeap = new ScriptSandboxLimits.SelfTestResult(false, false, true);

        assertThat(noHeap.passed()).as("lenient check used for local mode").isTrue();
        assertThat(ScriptSandboxSelfTest.strictlyPassed(noHeap)).isFalse();
    }

    @Test
    void strictlyPassed_cpuNotEnforced_isFalse() {
        assertThat(ScriptSandboxSelfTest.strictlyPassed(new ScriptSandboxLimits.SelfTestResult(true, true, false)))
                .isFalse();
    }
}
