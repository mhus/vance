package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import de.mhus.vance.toolpack.ToolException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * `refresh:` in the manifest — seconds between calls to the program's
 * {@code onAppRefresh()}.
 *
 * <p>In the manifest and not in the program, because it is a property of the app
 * rather than something its code should change while running, and because a
 * reader looking at the folder can then see that it polls.
 */
class RefreshConfigTest {

    private static BistromathConfig parse(Object refresh) {
        Map<String, Object> custom = new LinkedHashMap<>();
        if (refresh != null) custom.put("refresh", refresh);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(BistromathConfig.BLOCK, custom);
        return BistromathConfig.from(new ApplicationDocument("application",
                BistromathConfig.BLOCK, "Poller", null, config, new LinkedHashMap<>()));
    }

    @Test
    void refresh_absentMeansNoPolling() {
        assertThat(parse(null).refresh()).isNull();
    }

    @Test
    void refresh_takesWholeSeconds() {
        assertThat(parse(30).refresh()).isEqualTo(30);
        // YAML may hand a quoted number through; the intent is unambiguous.
        assertThat(parse("30").refresh()).isEqualTo(30);
    }

    @Test
    void refresh_acceptsExactlyTheFloor() {
        assertThat(parse(BistromathConfig.MIN_REFRESH_SECONDS).refresh())
                .isEqualTo(BistromathConfig.MIN_REFRESH_SECONDS);
    }

    /**
     * Refused rather than clamped. A one-second interval is one round trip per
     * second per open tab; somebody who wrote it meant something else, and
     * silently correcting it would leave that belief in place.
     */
    @Test
    void refresh_belowTheFloorIsRefusedAndSaysWhy() {
        assertThatThrownBy(() -> parse(1))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("shortest allowed is 5")
                .hasMessageContaining("Remove the key for no polling");
    }

    @Test
    void refresh_zeroAndNegativeAreBelowTheFloorToo() {
        // Not a spelling of "off" — the absence of the key is.
        assertThatThrownBy(() -> parse(0)).isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> parse(-5)).isInstanceOf(ToolException.class);
    }

    @Test
    void refresh_fractionIsRefusedRatherThanRounded() {
        // A fraction is a sub-second poll asked for indirectly, and rounding it
        // would answer a question the author did not ask.
        assertThatThrownBy(() -> parse(0.5))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void refresh_nonNumberIsRefused() {
        assertThatThrownBy(() -> parse("often"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a number of seconds");
    }

    @Test
    void refresh_survivesARoundTrip() {
        Map<String, Object> block = new BistromathConfig(
                null, null, java.util.List.of(), null, 60).toBlock();

        assertThat(block).containsEntry("refresh", 60);
    }
}
