package de.mhus.vance.shared.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.ursascheduler.OverlapPolicy;
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

    // ──── overlap-policy vs trigger-type validation (code-review-2) ────

    @Test
    void validate_rejectsQueueOverlap_forWorkflowTrigger() {
        String yaml = "description: d\ncron: '0 0 * * * *'\nworkflow: myflow\noverlap: QUEUE\n";

        assertThatThrownBy(() -> loader.validateYaml("s", yaml))
                .isInstanceOf(UrsaSchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("only supported for 'recipe'");
    }

    @Test
    void validate_rejectsCancelPreviousOverlap_forWorkflowTrigger() {
        String yaml =
                "description: d\ncron: '0 0 * * * *'\nworkflow: myflow\noverlap: cancelPrevious\n";

        assertThatThrownBy(() -> loader.validateYaml("s", yaml))
                .isInstanceOf(UrsaSchedulerLoader.SchedulerParseException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void validate_allowsSkipOverlap_forWorkflowTrigger() {
        String yaml = "description: d\ncron: '0 0 * * * *'\nworkflow: myflow\noverlap: SKIP\n";

        ResolvedUrsaScheduler r = loader.validateYaml("s", yaml);
        assertThat(r.overlap()).isEqualTo(OverlapPolicy.SKIP);
        assertThat(r.workflow()).isEqualTo("myflow");
    }

    @Test
    void validate_allowsQueueOverlap_forRecipeTrigger() {
        String yaml = "description: d\ncron: '0 0 * * * *'\nrecipe: report\noverlap: QUEUE\n";

        ResolvedUrsaScheduler r = loader.validateYaml("s", yaml);
        assertThat(r.overlap()).isEqualTo(OverlapPolicy.QUEUE);
        assertThat(r.recipe()).isEqualTo("report");
    }
}
