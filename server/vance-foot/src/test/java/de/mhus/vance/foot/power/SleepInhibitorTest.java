package de.mhus.vance.foot.power;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SleepInhibitorTest {

    private @org.jspecify.annotations.Nullable List<String> commandFor(String osName) {
        String prev = System.getProperty("os.name");
        System.setProperty("os.name", osName);
        try {
            return SleepInhibitor.command(4242L);
        } finally {
            if (prev != null) System.setProperty("os.name", prev);
        }
    }

    @Test
    void command_onMac_usesCaffeinatePinnedToPid() {
        List<String> cmd = commandFor("Mac OS X");
        assertThat(cmd).containsExactly("caffeinate", "-i", "-w", "4242");
    }

    @Test
    void command_onLinux_usesSystemdInhibitWatchingPid() {
        List<String> cmd = commandFor("Linux");
        assertThat(cmd)
                .startsWith("systemd-inhibit", "--what=idle:sleep")
                .contains("--mode=block")
                .containsSubsequence("tail", "--pid=4242", "-f", "/dev/null");
    }

    @Test
    void command_onWindows_usesHiddenPowershellSettingExecutionState() {
        List<String> cmd = commandFor("Windows 11");
        assertThat(cmd).startsWith("powershell");
        assertThat(cmd).containsSubsequence("-WindowStyle", "Hidden");
        assertThat(String.join(" ", cmd))
                .contains("SetThreadExecutionState(0x80000001)")
                .contains("-Id 4242");
    }

    @Test
    void command_onUnknownPlatform_returnsNull() {
        assertThat(commandFor("SunOS")).isNull();
    }
}
