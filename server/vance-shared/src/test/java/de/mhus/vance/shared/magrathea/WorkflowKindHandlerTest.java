package de.mhus.vance.shared.magrathea;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkflowKindHandlerTest {

    private static final String MINIMAL = """
            start: begin
            states:
              begin:
                type: terminal
            """;

    private final WorkflowKindHandler handler = new WorkflowKindHandler();

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void validWorkflow_hasNoFindings() {
        assertThat(handler.validate(MINIMAL, ctx("_vance/workflows/x.yaml"))).isEmpty();
    }

    @Test
    void validWorkflowOutsideTheActivePath_isStillValid() {
        // Kind and location are independent — only _vance/workflows/ makes a
        // workflow startable, it does not make it a workflow.
        assertThat(handler.validate(MINIMAL, ctx("drafts/idea.yaml"))).isEmpty();
    }

    @Test
    void metaHeaderIsIgnoredByTheParser() {
        String withMeta = "$meta:\n  kind: vance-workflow\n" + MINIMAL;
        assertThat(handler.validate(withMeta, ctx("_vance/workflows/x.yaml"))).isEmpty();
    }

    @Test
    void missingStartState_isAnError() {
        String yaml = "states:\n  begin:\n    type: terminal\n";
        List<Finding> findings = handler.validate(yaml, ctx("_vance/workflows/x.yaml"));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(findings.get(0).code()).isEqualTo("vance-workflow-parse");
        assertThat(findings.get(0).location()).isEqualTo("_vance/workflows/x.yaml");
    }

    @Test
    void transitionToUndeclaredState_isAnError() {
        String yaml = """
                start: begin
                states:
                  begin:
                    type: terminal
                    on:
                      success: nowhere
                """;
        List<Finding> findings = handler.validate(yaml, ctx("_vance/workflows/x.yaml"));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("nowhere");
    }

    @Test
    void keyWrittenWithoutAValue_isNotAParseError() {
        // Half-finished edit: `recipe:` with nothing behind it. YAML reads that
        // as null, and a null in the type-specific spec map used to blow up
        // Map.copyOf — the reader saw "workflow YAML invalid: null" instead of
        // their unfinished line.
        String yaml = """
                start: work
                states:
                  work:
                    type: agent_task
                    recipe:
                """;
        assertThat(handler.validate(yaml, ctx("_vance/workflows/x.yaml"))).isEmpty();
    }

    @Test
    void emptyBody_isAnError() {
        assertThat(handler.validate("", ctx("_vance/workflows/x.yaml")))
                .singleElement()
                .extracting(Finding::level)
                .isEqualTo(Finding.Level.ERROR);
    }
}
