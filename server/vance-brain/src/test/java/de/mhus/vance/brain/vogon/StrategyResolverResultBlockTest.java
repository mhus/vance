package de.mhus.vance.brain.vogon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.vogon.ResultSpec;
import de.mhus.vance.api.vogon.StrategySpec;
import org.junit.jupiter.api.Test;

/**
 * Boot-validation for the {@code result:} block per
 * {@code specification/vogon-engine.md} §3.2 — fields can't
 * self-reference (cyclic scope), text can only reference declared
 * field keys.
 */
class StrategyResolverResultBlockTest {

    private static final String MIN_PHASES =
            "phases:\n"
            + "  - name: noop\n"
            + "    type: gate\n"
            + "    gate: { requires: [done] }\n";

    @Test
    void parse_resultBlockWithFieldsAndText() {
        String yaml =
                "name: t\n"
                + MIN_PHASES
                + "result:\n"
                + "  fields:\n"
                + "    docPath: \"${flags.draftPath}\"\n"
                + "    count: \"${flags.wordCount}\"\n"
                + "  text: |\n"
                + "    Report unter ${result.docPath} mit ${result.count} Wörtern.\n";
        StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test.yaml");
        assertThat(spec.getResult()).isNotNull();
        ResultSpec r = spec.getResult();
        assertThat(r.getFields())
                .containsEntry("docPath", "${flags.draftPath}")
                .containsEntry("count", "${flags.wordCount}");
        assertThat(r.getText()).contains("${result.docPath}").contains("${result.count}");
        assertThat(r.getOnFailure()).isNull();
    }

    @Test
    void parse_onFailureBlock() {
        String yaml =
                "name: t\n"
                + MIN_PHASES
                + "result:\n"
                + "  fields:\n"
                + "    ok: \"yes\"\n"
                + "  text: \"all good\"\n"
                + "  onFailure:\n"
                + "    fields:\n"
                + "      reason: \"${flags.failureReason}\"\n"
                + "    text: \"failed: ${result.reason}\"\n";
        StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test.yaml");
        assertThat(spec.getResult().getOnFailure()).isNotNull();
        assertThat(spec.getResult().getOnFailure().getFields())
                .containsEntry("reason", "${flags.failureReason}");
    }

    @Test
    void parse_rejectsResultRefInsideFields() {
        String yaml =
                "name: t\n"
                + MIN_PHASES
                + "result:\n"
                + "  fields:\n"
                + "    a: \"static\"\n"
                + "    b: \"${result.a}\"\n";  // ← cyclic
        assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test.yaml"))
                .hasMessageContaining("cyclic scope")
                .hasMessageContaining("fields[b]");
    }

    @Test
    void parse_rejectsTextRefToUndeclaredField() {
        String yaml =
                "name: t\n"
                + MIN_PHASES
                + "result:\n"
                + "  fields:\n"
                + "    a: \"static\"\n"
                + "  text: \"${result.notDeclared}\"\n";
        assertThatThrownBy(() -> StrategyResolver.parseStrategy(yaml, "test.yaml"))
                .hasMessageContaining("notDeclared")
                .hasMessageContaining("not declared in the sibling fields");
    }

    @Test
    void parse_acceptsTextRefToNestedFieldPath() {
        // text uses ${result.user.id} — only the top-level "user"
        // key needs to be declared in fields; the nested .id path
        // is resolved at runtime via the value's structure.
        String yaml =
                "name: t\n"
                + MIN_PHASES
                + "result:\n"
                + "  fields:\n"
                + "    user: \"${flags.userObject}\"\n"
                + "  text: \"user-id: ${result.user.id}\"\n";
        StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test.yaml");
        assertThat(spec.getResult().getText())
                .contains("${result.user.id}");
    }

    @Test
    void parse_strategyWithoutResultBlock() {
        String yaml = "name: t\n" + MIN_PHASES;
        StrategySpec spec = StrategyResolver.parseStrategy(yaml, "test.yaml");
        assertThat(spec.getResult()).isNull();
    }
}
