package de.mhus.vance.brain.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.ursascheduler.SchedulerGranularity;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

class CronGranularityPolicyTest {

    private static final String KEY = "acme/proj/nightly";

    @Test
    void seconds_keepsCronUntouched() {
        assertThat(CronGranularityPolicy.apply("*/5 * * * * *", SchedulerGranularity.SECONDS, KEY))
                .isEqualTo("*/5 * * * * *");
    }

    @Test
    void minutes_clampsSecondsToZero() {
        assertThat(CronGranularityPolicy.apply("*/5 * * * * *", SchedulerGranularity.MINUTES, KEY))
                .isEqualTo("0 * * * * *");
    }

    @Test
    void minutes_leavesMinuteFieldAlone() {
        assertThat(CronGranularityPolicy.apply("0 */5 * * * *", SchedulerGranularity.MINUTES, KEY))
                .isEqualTo("0 */5 * * * *");
    }

    @Test
    void minutes_leavesFiveFieldCronAlone() {
        assertThat(CronGranularityPolicy.apply("0 * * * *", SchedulerGranularity.MINUTES, KEY))
                .isEqualTo("0 * * * *");
    }

    @Test
    void minutes_normalisesInternalWhitespace() {
        assertThat(CronGranularityPolicy.apply("*/5   *   *   *   *   *", SchedulerGranularity.MINUTES, KEY))
                .isEqualTo("0 * * * * *");
    }

    @Test
    void hours_clampsSecondsAndReplacesMinuteWithOffset() {
        int minute = CronGranularityPolicy.spreadMinute(KEY);
        assertThat(CronGranularityPolicy.apply("*/20 */5 * * * *", SchedulerGranularity.HOURS, KEY))
                .isEqualTo("0 " + minute + " * * * *");
    }

    @Test
    void hours_keepsHourDayAndWeekdayFields() {
        int minute = CronGranularityPolicy.spreadMinute(KEY);
        assertThat(CronGranularityPolicy.apply("0 0 8 * * MON-FRI", SchedulerGranularity.HOURS, KEY))
                .isEqualTo("0 " + minute + " 8 * * MON-FRI");
    }

    @Test
    void hours_resultIsAValidCronFiringOncePerHour() {
        String effective = CronGranularityPolicy.apply("*/1 * * * * *", SchedulerGranularity.HOURS, KEY);
        assertThat(CronExpression.isValidExpression(effective)).isTrue();
        var first = CronExpression.parse(effective).next(java.time.ZonedDateTime.parse("2026-01-01T00:00:00Z"));
        var second = CronExpression.parse(effective).next(first);
        assertThat(java.time.Duration.between(first, second)).isEqualTo(java.time.Duration.ofHours(1));
    }

    @Test
    void spreadMinute_isStableAndInRange() {
        int first = CronGranularityPolicy.spreadMinute(KEY);
        assertThat(CronGranularityPolicy.spreadMinute(KEY)).isEqualTo(first);
        assertThat(first).isBetween(0, 59);
    }

    @Test
    void spreadMinute_spreadsDifferentSchedulers() {
        Set<Integer> minutes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            minutes.add(CronGranularityPolicy.spreadMinute("acme/proj/scheduler-" + i));
        }
        assertThat(minutes).hasSizeGreaterThan(40);
    }

    @Test
    void parse_isCaseInsensitive() {
        assertThat(CronGranularityPolicy.parse(" Hours ")).isEqualTo(SchedulerGranularity.HOURS);
        assertThat(CronGranularityPolicy.parse("seconds")).isEqualTo(SchedulerGranularity.SECONDS);
    }

    @Test
    void parse_rejectsUnknownValue() {
        assertThatThrownBy(() -> CronGranularityPolicy.parse("days"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seconds, minutes, hours");
    }
}
