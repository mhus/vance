package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Registry bounding: terminal runs must not accumulate. The count-only
 * eviction never fired while runs stayed under the cap, so a TTL sweep drops
 * terminal runs by age; running runs are always kept.
 */
class ComposeRunRegistryTest {

    private final ComposeRunRegistry registry = new ComposeRunRegistry();

    @Test
    void evictExpiredTerminal_dropsOldTerminalRuns_keepsRunning() {
        registry.register(running("r-run"));
        registry.register(terminal("r-done"));

        // Sweep as if far in the future → the terminal run is past its TTL.
        registry.evictExpiredTerminal(Instant.now().plus(Duration.ofMinutes(20)));

        assertThat(registry.find("t", "p", "r-run")).isPresent();   // running never dropped
        assertThat(registry.find("t", "p", "r-done")).isEmpty();    // terminal + expired → gone
    }

    @Test
    void evictExpiredTerminal_keepsRecentTerminal() {
        registry.register(terminal("r-done"));

        // A just-finished terminal run is within the TTL.
        registry.evictExpiredTerminal(Instant.now());

        assertThat(registry.find("t", "p", "r-done")).isPresent();
    }

    private static ComposeRun running(String id) {
        return new ComposeRun(id, "t", "p", "ws", Instant.now());
    }

    private static ComposeRun terminal(String id) {
        ComposeRun r = running(id);
        r.fail("done"); // → FAILURE + finishedAt = now
        return r;
    }
}
