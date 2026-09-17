package de.mhus.vance.shared.ursascheduler;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-scheduler} document kind. The
 * handler is a thin delegate onto {@link UrsaSchedulerLoader#parseValidated}
 * — these tests pin the contract that matters for callers of the kind
 * validation: a well-formed body produces no findings, a broken one
 * produces exactly one ERROR finding with the stable
 * {@code vance-scheduler-parse} code, and a {@code $meta} header (present
 * on every persisted scheduler since the write paths stamp it) does not
 * affect validation.
 */
class UrsaSchedulerKindHandlerTest {

    private final UrsaSchedulerKindHandler handler = new UrsaSchedulerKindHandler();

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public @Nullable String kindOf(String path) {
            return null;
        }

        @Override
        public @Nullable Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void getName_isVanceScheduler() {
        assertThat(handler.getName()).isEqualTo("vance-scheduler");
    }

    @Test
    void validate_validBody_hasNoFindings() {
        String yaml = """
                $meta:
                  kind: vance-scheduler
                description: "Daily morning briefing."
                cron: "0 0 8 * * MON-FRI"
                timezone: "Europe/Berlin"
                recipe: "default"
                """;

        assertThat(handler.validate(yaml, ctx("_vance/scheduler/morning-briefing.yaml")))
                .isEmpty();
    }

    @Test
    void validate_reportsBrokenCron_asSingleErrorFinding() {
        String yaml = """
                description: "Broken."
                cron: "not a cron"
                recipe: "default"
                """;

        var findings = handler.validate(yaml, ctx("_vance/scheduler/broken.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("vance-scheduler-parse");
        assertThat(findings.get(0).location()).isEqualTo("_vance/scheduler/broken.yaml");
        assertThat(findings.get(0).message()).contains("cron");
    }

    @Test
    void validate_reportsMissingTrigger() {
        String yaml = """
                description: "No trigger field."
                recipe: "default"
                """;

        var findings = handler.validate(yaml, ctx(null));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).code()).isEqualTo("vance-scheduler-parse");
        assertThat(findings.get(0).message()).contains("trigger");
    }

    @Test
    void validate_draftOutsideSchedulerPrefix_validatesTheSame() {
        // Kind and location are independent: a draft copy somewhere else
        // in the project gets the same validation as the active doc.
        String yaml = """
                $meta:
                  kind: vance-scheduler
                description: "Draft."
                at: "2026-05-14T08:00:00"
                recipe: "default"
                """;

        assertThat(handler.validate(yaml, ctx("drafts/scheduler-draft.yaml"))).isEmpty();
    }
}
