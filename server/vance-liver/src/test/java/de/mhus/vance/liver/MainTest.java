package de.mhus.vance.liver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Smoke tests for the {@link Main} entry point.
 *
 * <p>The skeleton intentionally has no subcommands yet — these tests
 * pin the bits the later subcommands depend on (banner text, Picocli
 * wiring, exit-code mapping) so a future refactor cannot silently
 * break the contract.
 */
class MainTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void usageBanner_mentionsInit() {
        // The banner exists so a user running with no args sees a hint
        // to start with `init`. A refactor that drops the word would
        // make first-run harder.
        String banner = Main.usageBanner();
        assertThat(banner).contains("vancetope-backup");
        assertThat(banner).contains("init");
        assertThat(banner).contains("~/.vancetope/liver/backup.yaml");
    }

    @Test
    void runWithoutSubcommand_logsWarning() {
        // The branch is only hit when something bypasses Picocli's
        // subcommand dispatch. We just confirm the log call doesn't
        // throw — full assertion on the logger output is not worth
        // mocking System.Logger for at this stage.
        new Main().run();
        // Stdout stays empty: PMD forbids System.out, and Picocli's
        // own usage handler owns the user-facing message in normal
        // operation.
        assertThat(capturedOut.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void picocli_reportsUnknownSubcommand_asInvalidInput() {
        // @Command(exitCodeOnInvalidInput = 4) — config error. A
        // refactor that drops the attribute and falls back to
        // Picocli's default (USAGE = 2) would break this test.
        int exit = new CommandLine(new Main()).execute("nosuchcommand");
        assertThat(exit).isEqualTo(4);
        // Picocli prints the usage to stderr by default; we only assert
        // the exit code, not the message, because the wording is owned
        // by Picocli and may shift across versions.
    }

    @Test
    void picocli_helpFlag_exitsZero() {
        int exit = new CommandLine(new Main()).execute("--help");
        assertThat(exit).isEqualTo(0);
    }
}
