package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChildReplyTest {

    @Test
    void wrapAndUnwrap_roundTrip_unwrappedTextPassesThrough() {
        String wrapped = ChildReply.wrap("Child process p status=done", "Done.\nChanged two sections.");

        assertThat(wrapped).contains(ChildReply.BEGIN, ChildReply.END);
        assertThat(ChildReply.unwrap(wrapped)).isEqualTo("Done.\nChanged two sections.");
        assertThat(ChildReply.unwrap("plain")).isEqualTo("plain");
        assertThat(ChildReply.unwrap(null)).isNull();
    }
}
