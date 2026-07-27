package de.mhus.vance.foot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import org.junit.jupiter.api.Test;

class FootVersionProviderTest {

    private static FootVersionProvider provider(String version, String time) {
        FootConfig config = new FootConfig();
        config.getBuild().setVersion(version);
        config.getBuild().setTime(time);
        return new FootVersionProvider(config);
    }

    @Test
    void getVersion_withBuildTime_appendsBuiltSuffix() {
        String[] out = provider("1.2.3", "2026-07-27T10:15:00Z").getVersion();

        assertThat(out).containsExactly("vance-foot 1.2.3 (built 2026-07-27T10:15:00Z)");
    }

    @Test
    void getVersion_blankBuildTime_omitsBuiltSuffix() {
        String[] out = provider("1.2.3", "").getVersion();

        assertThat(out).containsExactly("vance-foot 1.2.3");
    }
}
