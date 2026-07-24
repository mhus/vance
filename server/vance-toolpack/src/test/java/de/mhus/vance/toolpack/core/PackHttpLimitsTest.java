package de.mhus.vance.toolpack.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Security regression (code-review-2 S2): the tool-pack HTTP consumers must not
 * buffer an unbounded response body into the Brain heap.
 */
class PackHttpLimitsTest {

    @Test
    void readCapped_underLimit_returnsAllBytes() throws IOException {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] out = PackHttpLimits.readCapped(new ByteArrayInputStream(data), 1024);
        assertThat(out).isEqualTo(data);
    }

    @Test
    void readCapped_overLimit_throws() {
        byte[] data = new byte[5000];
        assertThatThrownBy(() -> PackHttpLimits.readCapped(new ByteArrayInputStream(data), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void readCapped_exactlyAtLimit_ok() throws IOException {
        byte[] data = new byte[1024];
        byte[] out = PackHttpLimits.readCapped(new ByteArrayInputStream(data), 1024);
        assertThat(out).hasSize(1024);
    }
}
