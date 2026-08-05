package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.tools.exec.ClientExecStat;
import de.mhus.vance.foot.tools.exec.ClientExecStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Row rendering for the {@code /ui-exec} master list. The Lanterna
 * wiring itself needs a TTY, so only the pure projection is covered.
 */
class UiExecCommandFormatTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:30Z");

    private static ClientExecStat stat(String id,
                                       String command,
                                       ClientExecStatus status,
                                       Integer exitCode,
                                       long durationMs,
                                       Instant lastOutputAt) {
        return new ClientExecStat(id, command, null, null, status,
                Instant.parse("2026-08-05T12:00:00Z"), lastOutputAt, null,
                exitCode, durationMs, 0, 0, 0, 0,
                "out.log", "err.log", false);
    }

    @Test
    void formatRow_runningJob_showsDashForMissingExitCode() {
        ClientExecStat job = stat("abcd1234", "sleep 60", ClientExecStatus.RUNNING,
                null, 30_000, Instant.parse("2026-08-05T12:00:20Z"));

        String row = UiExecCommand.formatRow(job, NOW);

        assertThat(row).startsWith("abcd1234  RUNNING  ");
        assertThat(row).contains("—");
        assertThat(row).endsWith("sleep 60");
    }

    @Test
    void formatRow_idleColumn_measuresSinceLastOutput() {
        ClientExecStat job = stat("abcd1234", "make", ClientExecStatus.RUNNING,
                null, 30_000, Instant.parse("2026-08-05T12:00:00Z"));

        // 30s since the last output line, 30s runtime.
        assertThat(UiExecCommand.formatRow(job, NOW)).contains("30s");
    }

    @Test
    void formatRow_truncatesLongCommand() {
        String command = "echo " + "x".repeat(200);
        ClientExecStat job = stat("abcd1234", command, ClientExecStatus.COMPLETED,
                0, 1_200, NOW);

        String row = UiExecCommand.formatRow(job, NOW);

        assertThat(row).endsWith("…");
        assertThat(row.length()).isLessThan(command.length());
    }

    @Test
    void formatRow_flattensMultiLineCommand() {
        ClientExecStat job = stat("abcd1234", "echo a\necho b", ClientExecStatus.FAILED,
                1, 500, NOW);

        assertThat(UiExecCommand.formatRow(job, NOW))
                .doesNotContain("\n")
                .endsWith("echo a echo b");
    }

    @Test
    void humanDuration_scalesFromMillisToHours() {
        assertThat(UiExecCommand.humanDuration(-5)).isEqualTo("0s");
        assertThat(UiExecCommand.humanDuration(1_200)).isEqualTo("1.2s");
        assertThat(UiExecCommand.humanDuration(45_000)).isEqualTo("45s");
        assertThat(UiExecCommand.humanDuration(125_000)).isEqualTo("2m05s");
        assertThat(UiExecCommand.humanDuration(3_723_000)).isEqualTo("1h02m");
    }

    @Test
    void humanSize_scalesFromBytesToMegabytes() {
        assertThat(UiExecCommand.humanSize(512)).isEqualTo("512B");
        assertThat(UiExecCommand.humanSize(2_048)).isEqualTo("2.0K");
        assertThat(UiExecCommand.humanSize(5 * 1024 * 1024)).isEqualTo("5.0M");
    }
}
