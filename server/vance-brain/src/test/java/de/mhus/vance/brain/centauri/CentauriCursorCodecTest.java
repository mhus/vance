package de.mhus.vance.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CentauriCursorCodecTest {

    private final CentauriCursorCodec codec =
            new CentauriCursorCodec(JsonMapper.builder().build());

    @Test
    void roundTrip_preservesEveryPart() {
        CentauriCursor original = new CentauriCursor(
                Map.of("alpha|world", "a17", "beta|tech", "b3"),
                Instant.parse("2026-08-19T09:30:00Z"),
                Set.of("gamma|sport"));

        CentauriCursor decoded = codec.decode(codec.encode(original));

        assertThat(decoded.perStream()).isEqualTo(original.perStream());
        assertThat(decoded.watermark()).isEqualTo(original.watermark());
        assertThat(decoded.exhausted()).isEqualTo(original.exhausted());
    }

    @Test
    void decode_missingCursor_startsAtTheTop() {
        assertThat(codec.decode(null).isFresh()).isTrue();
        assertThat(codec.decode("  ").isFresh()).isTrue();
    }

    @Test
    void decode_garbage_isRejectedRatherThanRestarted() {
        // Silently restarting would look like an endless scroll that
        // occasionally loops — a content bug rather than a paging one.
        assertThatThrownBy(() -> codec.decode("not-a-cursor!!"))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void decode_foreignFormatVersion_isRejected() {
        String other = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":99,\"s\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(other))
                .isInstanceOf(CentauriException.class)
                .hasMessageContaining("version");
    }

    @Test
    void encode_producesUrlSafeOpaqueText() {
        String encoded = codec.encode(new CentauriCursor(
                Map.of("alpha|world", "a1"), null, Set.of()));

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(encoded).doesNotContain("alpha");
    }
}
