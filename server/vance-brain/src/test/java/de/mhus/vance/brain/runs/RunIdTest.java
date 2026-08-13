package de.mhus.vance.brain.runs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunIdTest {

    @Test
    void roundTripsThroughTheCompositeForm() {
        assertThat(RunId.of("workflow", "abc").composite()).isEqualTo("workflow:abc");
        assertThat(RunId.parse("workflow:abc")).isEqualTo(RunId.of("workflow", "abc"));
    }

    @Test
    void keepsColonsInsideTheNativeId() {
        // Only the first colon separates; a source that uses colons in its
        // own ids must still round-trip.
        assertThat(RunId.parse("compose:a:b").nativeId()).isEqualTo("a:b");
    }

    @Test
    void rejectsFormsThatCarryNoSource() {
        assertThat(RunId.parse(null)).isNull();
        assertThat(RunId.parse("nocolon")).isNull();
        assertThat(RunId.parse(":leading")).isNull();
        assertThat(RunId.parse("trailing:")).isNull();
    }
}
