package de.mhus.vance.shared.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.document.DocumentService;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Covers {@link UrsaSchedulerLoader#applyDefaultTimezone} — the
 * write-time timezone pinning used by the {@code scheduler_set} tool.
 * Only the pure YAML transform is exercised; the DocumentService is an
 * unused mock.
 */
class UrsaSchedulerLoaderTest {

    private final UrsaSchedulerLoader loader =
            new UrsaSchedulerLoader(mock(DocumentService.class));

    @SuppressWarnings("unchecked")
    private static Object tzOf(String yaml) {
        return ((java.util.Map<String, Object>) new Yaml().load(yaml)).get("timezone");
    }

    @Test
    void applyDefaultTimezone_addsTimezone_whenAbsent() {
        String yaml = "description: daily\ncron: '0 9 * * *'\nrecipe: report\n";

        String out = loader.applyDefaultTimezone(yaml, "Asia/Kolkata");

        assertThat(tzOf(out)).isEqualTo("Asia/Kolkata");
        // Other fields survive the round-trip.
        assertThat(out).contains("report");
    }

    @Test
    void applyDefaultTimezone_keepsExplicitTimezone() {
        String yaml = "description: daily\ncron: '0 9 * * *'\nrecipe: report\ntimezone: Europe/Berlin\n";

        String out = loader.applyDefaultTimezone(yaml, "Asia/Kolkata");

        assertThat(tzOf(out)).isEqualTo("Europe/Berlin");
    }

    @Test
    void applyDefaultTimezone_fillsBlankTimezone() {
        String yaml = "description: daily\ncron: '0 9 * * *'\nrecipe: report\ntimezone: '   '\n";

        String out = loader.applyDefaultTimezone(yaml, "Asia/Kolkata");

        assertThat(tzOf(out)).isEqualTo("Asia/Kolkata");
    }

    @Test
    void applyDefaultTimezone_returnsVerbatim_whenNoTimezoneGiven() {
        String yaml = "description: daily\ncron: '0 9 * * *'\nrecipe: report\n";

        assertThat(loader.applyDefaultTimezone(yaml, null)).isEqualTo(yaml);
        assertThat(loader.applyDefaultTimezone(yaml, "  ")).isEqualTo(yaml);
    }

    @Test
    void applyDefaultTimezone_returnsVerbatim_whenYamlUnparseable() {
        String broken = "description: [unterminated";

        assertThat(loader.applyDefaultTimezone(broken, "Asia/Kolkata")).isEqualTo(broken);
    }
}
