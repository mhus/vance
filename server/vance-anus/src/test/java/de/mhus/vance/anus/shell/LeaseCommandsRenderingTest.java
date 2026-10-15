package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.anus.lease.AnusLeaseService.RevokeOutcome;
import de.mhus.vance.anus.lease.AnusLeaseService.RevokeOutcome.Status;
import de.mhus.vance.shared.lease.LeaseDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The text an operator reads after a lease command. */
class LeaseCommandsRenderingTest {

    @Test
    void revoke_saysWhetherTheHandlerRan() {
        assertThat(LeaseCommands.renderRevoke(new RevokeOutcome(Status.REVOKED, List.of("rl-1"), ""), "x"))
                .isEqualTo("Revoked 1 lease, cleanup handler dispatched: rl-1");
        assertThat(LeaseCommands.renderRevoke(new RevokeOutcome(Status.FORCED, List.of("rl-1", "rl-2"), ""), "x"))
                .startsWith("Revoked 2 leases without a cleanup handler: rl-1, rl-2")
                .contains("left to its own expiry");
        assertThat(LeaseCommands.renderRevoke(new RevokeOutcome(Status.NOT_FOUND, List.of(), ""), "lease 'rl-9'"))
                .isEqualTo("(no lease 'rl-9' to revoke)");
        assertThat(LeaseCommands.renderRevoke(new RevokeOutcome(Status.FAILED, List.of(), "HTTP 500 boom"), "x"))
                .isEqualTo("(failed: HTTP 500 boom)");
    }

    @Test
    void definition_marksDefaultsAndMissingCaps() {
        String text =
                LeaseCommands.renderDefinition(new LeaseDefinition("tex", "tex2pdf", 6L, 3L, null, null, null, 0L));

        assertThat(text)
                .contains("resource    tex2pdf")
                .contains("global      6")
                .contains("project     —  (no cap)")
                .contains("ttlSeconds  120  (default)")
                .contains("waitMs      0\n");
    }

    @Test
    void definition_withoutAnyCap_saysNoop() {
        assertThat(LeaseCommands.renderDefinition(new LeaseDefinition("x", null, null, null, null, null, null, null)))
                .contains("NOOP lease");
    }

    @Test
    void since_scalesTheUnit() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");

        assertThat(LeaseCommands.since(now.minusSeconds(42), now)).isEqualTo("42s");
        assertThat(LeaseCommands.since(now.minusSeconds(5 * 60), now)).isEqualTo("5m");
        assertThat(LeaseCommands.since(now.minusSeconds(2 * 3600 + 7 * 60), now))
                .isEqualTo("2h7m");
        assertThat(LeaseCommands.since(now.minusSeconds(3 * 86_400 + 4 * 3600), now))
                .isEqualTo("3d4h");
        assertThat(LeaseCommands.since(null, now)).isEmpty();
    }
}
