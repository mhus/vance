package de.mhus.vance.shared.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MailRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final MailRetryPolicy policy = new MailRetryPolicy(new int[] {5, 20, 60});

    @Test
    void retriesFollowTheScheduleInOrder() {
        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(5));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(20));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void givesUpOnlyAfterTheLastRetryFailed() {
        assertThat(policy.exhausted(1)).isFalse();
        assertThat(policy.exhausted(3)).isFalse();
        assertThat(policy.exhausted(4)).isTrue();
    }

    @Test
    void negativeDelaysAreTreatedAsImmediate() {
        MailRetryPolicy immediate = new MailRetryPolicy(new int[] {-3});

        assertThat(immediate.nextAttemptAt(1, NOW)).isEqualTo(NOW);
    }

    @Test
    void emptyScheduleAllowsExactlyOneAttempt() {
        MailRetryPolicy once = new MailRetryPolicy(new int[0]);

        assertThat(once.exhausted(1)).isTrue();
    }
}
