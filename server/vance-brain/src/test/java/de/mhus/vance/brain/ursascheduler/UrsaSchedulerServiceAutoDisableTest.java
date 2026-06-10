package de.mhus.vance.brain.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the static helpers behind the auto-disable
 * (C — recipe-missing → flip {@code enabled: false} + inbox-notify).
 * Higher-level integration is covered by the mini-deploy smoke runs.
 */
class UrsaSchedulerServiceAutoDisableTest {

    // ──── disableInYaml ──────────────────────────────────────────────

    @Test
    void disableInYaml_flipsExistingEnabledTrue() {
        String yaml = """
                description: "test"
                cron: "0 0 * * * *"
                enabled: true
                recipe: "process_runner"
                """;
        String out = UrsaSchedulerService.disableInYaml(yaml);
        assertThat(out).contains("enabled: false");
        assertThat(out).doesNotContain("enabled: true");
        assertThat(out).contains("recipe: \"process_runner\"");
    }

    @Test
    void disableInYaml_leavesExistingEnabledFalseAlone() {
        String yaml = """
                description: "test"
                enabled: false
                recipe: "ford"
                """;
        String out = UrsaSchedulerService.disableInYaml(yaml);
        // Single match; replaceFirst keeps "false" in place.
        assertThat(out).contains("enabled: false");
        assertThat(out).doesNotContain("enabled: true");
    }

    @Test
    void disableInYaml_appendsFieldWhenMissing() {
        String yaml = """
                description: "test"
                cron: "0 0 * * * *"
                recipe: "ford"
                """;
        String out = UrsaSchedulerService.disableInYaml(yaml);
        assertThat(out).endsWith("enabled: false\n");
        // Original content preserved.
        assertThat(out).contains("cron: \"0 0 * * * *\"");
    }

    @Test
    void disableInYaml_appendsNewlineBeforeFieldWhenSourceMissingTrailingNewline() {
        String yaml = "description: \"test\"\nrecipe: \"ford\"";
        String out = UrsaSchedulerService.disableInYaml(yaml);
        assertThat(out).contains("recipe: \"ford\"\nenabled: false\n");
    }

    // ──── clampCronSecondsIfDisallowed ───────────────────────────────

    @Test
    void clampCron_allowSecondsTrue_keepsCronUntouched() {
        assertThat(UrsaSchedulerService.clampCronSecondsIfDisallowed("*/5 * * * * *", true))
                .isEqualTo("*/5 * * * * *");
    }

    @Test
    void clampCron_disallowed_clampsNonZeroSecondsToZero() {
        // Six-field with seconds=*/5 → clamped to seconds=0 (still hourly).
        assertThat(UrsaSchedulerService.clampCronSecondsIfDisallowed("*/5 * * * * *", false))
                .isEqualTo("0 * * * * *");
    }

    @Test
    void clampCron_disallowed_leavesAlreadyZeroSecondsAlone() {
        // Already once-per-minute, no clamp needed.
        assertThat(UrsaSchedulerService.clampCronSecondsIfDisallowed("0 0 * * * *", false))
                .isEqualTo("0 0 * * * *");
    }

    @Test
    void clampCron_disallowed_leavesFiveFieldCronAlone() {
        // The loader normally upgrades 5-field to 6-field, but defensively
        // handle the case where a 5-field expression slips through.
        assertThat(UrsaSchedulerService.clampCronSecondsIfDisallowed("0 * * * *", false))
                .isEqualTo("0 * * * *");
    }

    @Test
    void clampCron_disallowed_normalisesInternalWhitespace() {
        // Multiple spaces between fields are squashed by the split+join
        // — the resulting trigger value is canonical-form.
        assertThat(UrsaSchedulerService.clampCronSecondsIfDisallowed("*/5   *   *   *   *   *", false))
                .isEqualTo("0 * * * * *");
    }

    @Test
    void disableInYaml_preservesCommentsAndIndentation() {
        // The contract is "edit in place, preserve author intent" — no
        // SnakeYAML round-trip normalisation. Comments and ordering stay.
        String yaml = """
                # Run RSS fetcher every hour
                description: "RSS fetcher"
                cron: "0 0 * * * *"
                # Toggle here if you need to pause.
                enabled: true
                recipe: "ford"
                """;
        String out = UrsaSchedulerService.disableInYaml(yaml);
        assertThat(out).contains("# Run RSS fetcher every hour");
        assertThat(out).contains("# Toggle here if you need to pause.");
        assertThat(out).contains("enabled: false");
    }
}
