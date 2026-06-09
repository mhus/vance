package de.mhus.vance.shared.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Cron-expression handling in the scheduler loader.
 *
 * <p>Background — see ticket mhus/vance#1: the loader used to leave the
 * cron field as-is and let the scheduler service catch malformed
 * expressions later, which produced a silent {@code registered: false}
 * response from {@code scheduler_set} that misled the LLM into
 * "scheduler service is broken" diagnoses. The fix validates up-front
 * AND auto-upgrades 5-field Unix cron (the form most LLMs default to)
 * to 6-field by prepending a {@code "0 "} seconds field.
 */
class UrsaSchedulerLoaderCronTest {

    private final UrsaSchedulerLoader loader = new UrsaSchedulerLoader(null);

    @Test
    void sixField_quartz_cron_passes_unchanged() {
        String yaml = """
                description: "Hourly at :00"
                cron: "0 0 * * * *"
                recipe: "default"
                """;
        ResolvedUrsaScheduler r = loader.validateYaml("hourly", yaml);
        assertThat(r.cron()).isEqualTo("0 0 * * * *");
    }

    @Test
    void fiveField_unix_cron_autoUpgrades_to_sixField() {
        // The original symptom of mhus/vance#1: LLM writes the common
        // 5-field Unix cron, loader used to fail registration silently.
        String yaml = """
                description: "Hourly at :00 (Unix form)"
                cron: "0 * * * *"
                recipe: "default"
                """;
        ResolvedUrsaScheduler r = loader.validateYaml("hourly-unix", yaml);
        // Auto-upgraded: seconds=0 prepended.
        assertThat(r.cron()).isEqualTo("0 0 * * * *");
    }

    @Test
    void fiveField_with_slash_step_autoUpgrades() {
        // "*/5 * * * *" — another LLM-common form for "every 5 minutes".
        String yaml = """
                description: "Every 5 min (Unix form)"
                cron: "*/5 * * * *"
                recipe: "default"
                """;
        ResolvedUrsaScheduler r = loader.validateYaml("every5", yaml);
        assertThat(r.cron()).isEqualTo("0 */5 * * * *");
    }

    @Test
    void cron_macro_passes() {
        String yaml = """
                description: "Daily via @daily"
                cron: "@daily"
                recipe: "default"
                """;
        ResolvedUrsaScheduler r = loader.validateYaml("daily-macro", yaml);
        assertThat(r.cron()).isEqualTo("@daily");
    }

    @Test
    void invalid_cron_rejected_with_format_hint() {
        String yaml = """
                description: "Bogus"
                cron: "definitely not a cron"
                recipe: "default"
                """;
        assertThatThrownBy(() -> loader.validateYaml("bogus", yaml))
                .isInstanceOf(UrsaSchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("invalid cron")
                .hasMessageContaining("6-field");
    }

    @Test
    void sevenField_quartz_cron_rejected_explicitly() {
        // 7-field Quartz (with year) is a real-world habit too, but
        // Spring's CronExpression doesn't support it — call that out
        // explicitly so the LLM doesn't waste retries.
        String yaml = """
                description: "Quartz 7-field (with year)"
                cron: "0 0 0 1 1 ? 2027"
                recipe: "default"
                """;
        assertThatThrownBy(() -> loader.validateYaml("y7", yaml))
                .isInstanceOf(UrsaSchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("invalid cron")
                .hasMessageContaining("7-field");
    }
}
