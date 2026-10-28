package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExecIsolationTest {

    @Test
    void enabled_customWithCmdPlaceholder_true() {
        assertThat(ExecIsolation.enabled("custom", "bwrap sh -c {cmd}")).isTrue();
    }

    @Test
    void enabled_modeNone_false() {
        assertThat(ExecIsolation.enabled("none", "bwrap sh -c {cmd}")).isFalse();
    }

    @Test
    void enabled_null_false() {
        assertThat(ExecIsolation.enabled(null, null)).isFalse();
    }

    @Test
    void enabled_wrapperWithoutCmd_false() {
        assertThat(ExecIsolation.enabled("custom", "bwrap sh -c echo")).isFalse();
        assertThat(ExecIsolation.enabled("custom", "  ")).isFalse();
    }

    @Test
    void wrap_substitutesWorkdirAndKeepsCommandAsSingleArg() {
        assertThat(ExecIsolation.wrap(
                        "bwrap --bind {workdir} {workdir} --chdir {workdir} /bin/sh -c {cmd}",
                        "/ws/job1",
                        "grep -r x ."))
                .containsExactly(
                        "bwrap",
                        "--bind",
                        "/ws/job1",
                        "/ws/job1",
                        "--chdir",
                        "/ws/job1",
                        "/bin/sh",
                        "-c",
                        "grep -r x .");
    }

    @Test
    void wrap_substitutesWorkdirInlineInToken() {
        assertThat(ExecIsolation.wrap("firejail --private={workdir} sh -c {cmd}", "/ws", "ls"))
                .containsExactly("firejail", "--private=/ws", "sh", "-c", "ls");
    }

    @Test
    void wrap_substitutesTheHome_andFallsBackToTheWorkdirWithoutOne() {
        String template = "bwrap --bind {home} {home} --chdir {workdir} sh -c {cmd}";

        assertThat(ExecIsolation.wrap(template, "/ws/job1", "/homes/p1", "ls"))
                .containsExactly("bwrap", "--bind", "/homes/p1", "/homes/p1", "--chdir", "/ws/job1", "sh", "-c", "ls");
        assertThat(ExecIsolation.wrap(template, "/ws/job1", "ls"))
                .containsExactly("bwrap", "--bind", "/ws/job1", "/ws/job1", "--chdir", "/ws/job1", "sh", "-c", "ls");
    }

    @Test
    void shellArgv_runsTheCommandAsOneArgumentUnderTheShell() {
        List<String> argv = ExecIsolation.shellArgv("echo a; echo b");

        assertThat(argv).last().isEqualTo("echo a; echo b");
        assertThat(argv).hasSize(3);
    }
}
