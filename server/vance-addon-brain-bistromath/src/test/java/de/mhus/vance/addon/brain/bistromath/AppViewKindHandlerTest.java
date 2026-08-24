package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Registering {@code app-view} is what puts a view document within reach of
 * {@code kind_validate}. Before it, the only thing that checked a view was
 * {@code app_rebuild} — which needs a whole app around the document, so a view
 * written on its own went unchecked until somebody opened the app.
 */
class AppViewKindHandlerTest {

    private final AppViewKindHandler handler = new AppViewKindHandler();

    private static KindValidationContext ctx(String path) {
        return new KindValidationContext("acme", "test1", path, "application/yaml", null);
    }

    @Test
    void validate_wellFormedView_findsNothing() {
        String yaml = """
                type: page
                title: Invoices
                children:
                  - { type: text, text: hello }
                """;

        assertThat(handler.validate(yaml, ctx("apps/x/main.yaml"))).isEmpty();
    }

    /**
     * The finding carries the parser's own message, which names the path inside
     * the document. Re-wording it here would only make it shorter and vaguer.
     */
    @Test
    void validate_brokenWidget_reportsWhereInTheDocument() {
        String yaml = """
                type: page
                children:
                  - { type: carousel }
                """;

        List<Finding> findings = handler.validate(yaml, ctx("apps/x/main.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("unknown widget `carousel`");
        assertThat(findings.get(0).message()).contains("children[0]");
    }

    @Test
    void validate_brokenYaml_isAFindingRatherThanAThrow() {
        List<Finding> findings = handler.validate("a: [unclosed\n", ctx("apps/x/main.yaml"));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("not valid YAML");
    }

    /**
     * Detection has to claim narrowly: a detector that matches loosely wins
     * over more specific kinds that sort after it, and the write is then typed
     * wrong with no error anywhere.
     */
    @Test
    void detects_onlyARootWhoseTypeIsPage() {
        assertThat(handler.detects("type: page\nchildren: []\n")).isTrue();
        assertThat(handler.detects("type: invoice\namount: 3\n")).isFalse();
        assertThat(handler.detects("- one\n- two\n")).isFalse();
        assertThat(handler.detects("a: [unclosed\n")).isFalse();
        assertThat(handler.detects("")).isFalse();
    }
}
