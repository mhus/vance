package de.mhus.vance.foot.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExecIsolationTest {

    @Test
    void wrap_substitutesPlaceholders_andKeepsCommandAsSingleArg() {
        ExecIsolation iso = new ExecIsolation(true, "/work",
                "bwrap --bind {workdir} {workdir} --chdir {workdir} /bin/sh -c {cmd}");

        assertThat(iso.wrap("cat a b"))
                .containsExactly("bwrap", "--bind", "/work", "/work", "--chdir", "/work",
                        "/bin/sh", "-c", "cat a b");
    }

    @Test
    void wrap_substitutesWorkdirInlineInToken() {
        ExecIsolation iso = new ExecIsolation(true, "/work",
                "firejail --private={workdir} sh -c {cmd}");

        assertThat(iso.wrap("ls")).containsExactly(
                "firejail", "--private=/work", "sh", "-c", "ls");
    }

    @Test
    void wrap_disabled_throws() {
        assertThatThrownBy(() -> ExecIsolation.DISABLED.wrap("ls"))
                .isInstanceOf(IllegalStateException.class);
    }
}
