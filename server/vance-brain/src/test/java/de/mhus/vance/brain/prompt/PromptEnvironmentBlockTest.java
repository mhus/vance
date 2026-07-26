package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.ws.ClientContext;
import org.junit.jupiter.api.Test;

class PromptEnvironmentBlockTest {

    private static ClientContext ctx(String os, String shell, boolean sandbox) {
        return ClientContext.builder()
                .os(os)
                .arch("aarch64")
                .shell(shell)
                .cwd("/home/bob/project")
                .sandboxEnabled(sandbox)
                .build();
    }

    @Test
    void render_windows_tellsLlmToTargetCmdNotBash() {
        String body = PromptEnvironmentBlock.render(ctx("windows", "cmd.exe", true));

        assertThat(body).startsWith("## Environment");
        assertThat(body).contains("Windows (aarch64)");
        assertThat(body).contains("`cmd.exe`");
        assertThat(body).contains("Windows command syntax");
        assertThat(body).contains("not POSIX/bash");
    }

    @Test
    void render_posix_targetsPosixShell() {
        String body = PromptEnvironmentBlock.render(ctx("linux", "/bin/sh", true));

        assertThat(body).contains("Linux (aarch64)");
        assertThat(body).contains("`/bin/sh`");
        assertThat(body).contains("POSIX shell syntax");
        assertThat(body).doesNotContain("cmd.exe");
    }

    @Test
    void render_sandboxDisabled_saysNotGated() {
        String body = PromptEnvironmentBlock.render(ctx("macos", "/bin/sh", false));

        assertThat(body).contains("macOS");
        assertThat(body).contains("off — client-side calls are not gated");
    }

    @Test
    void render_sandboxEnabled_saysCheckedAgainstPolicy() {
        String body = PromptEnvironmentBlock.render(ctx("linux", "/bin/sh", true));

        assertThat(body).contains("on — client-side file and exec calls are checked");
    }

    @Test
    void render_missingShell_fallsBackToPlatformDefault() {
        String body = PromptEnvironmentBlock.render(ctx("windows", null, true));

        assertThat(body).contains("`cmd.exe`");
    }

    @Test
    void render_noOs_returnsBlankSoCallerSkipsTheBlock() {
        assertThat(PromptEnvironmentBlock.render(ctx(null, "/bin/sh", true))).isEmpty();
        assertThat(PromptEnvironmentBlock.render(ctx("  ", "/bin/sh", true))).isEmpty();
    }
}
