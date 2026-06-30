package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecIsolationTest {

    private static ExecProperties.Isolation iso(String mode, String wrapper) {
        ExecProperties.Isolation i = new ExecProperties.Isolation();
        i.setMode(mode);
        i.setWrapper(wrapper);
        return i;
    }

    @Test
    void enabled_customWithCmdPlaceholder_true() {
        assertThat(ExecIsolation.enabled(iso("custom", "bwrap sh -c {cmd}"))).isTrue();
    }

    @Test
    void enabled_modeNone_false() {
        assertThat(ExecIsolation.enabled(iso("none", "bwrap sh -c {cmd}"))).isFalse();
    }

    @Test
    void enabled_null_false() {
        assertThat(ExecIsolation.enabled(null)).isFalse();
    }

    @Test
    void enabled_wrapperWithoutCmd_false() {
        assertThat(ExecIsolation.enabled(iso("custom", "bwrap sh -c echo"))).isFalse();
        assertThat(ExecIsolation.enabled(iso("custom", "  "))).isFalse();
    }

    @Test
    void wrap_substitutesWorkdirAndKeepsCommandAsSingleArg() {
        assertThat(ExecIsolation.wrap(
                "bwrap --bind {workdir} {workdir} --chdir {workdir} /bin/sh -c {cmd}",
                "/ws/job1", "grep -r x ."))
                .containsExactly("bwrap", "--bind", "/ws/job1", "/ws/job1", "--chdir", "/ws/job1",
                        "/bin/sh", "-c", "grep -r x .");
    }

    @Test
    void wrap_substitutesWorkdirInlineInToken() {
        assertThat(ExecIsolation.wrap("firejail --private={workdir} sh -c {cmd}", "/ws", "ls"))
                .containsExactly("firejail", "--private=/ws", "sh", "-c", "ls");
    }
}
