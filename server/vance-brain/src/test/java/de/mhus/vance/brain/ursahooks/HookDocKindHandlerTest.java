package de.mhus.vance.brain.ursahooks;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-hook} document kind. The handler
 * delegates to the canonical {@link UrsaHookYamlParser}, so a finding means
 * exactly what {@link UrsaHookLoader} does with the same body: skip, logged,
 * silently missing. The event path segment is checked first — a hook under an
 * unknown event never reaches a single body problem.
 */
class HookDocKindHandlerTest {

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

    private final HookDocKindHandler handler = new HookDocKindHandler(new UrsaHookYamlParser());

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "application/yaml", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheHookTree() {
        assertThat(handler.detectsPath("_vance/hooks/process.completed/notify.yaml"))
                .isTrue();
    }

    @Test
    void detectsPath_neverClaimsOutsideTheTree() {
        assertThat(handler.detectsPath("_vance/hooks-old/x.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/recipes/analyze.yaml")).isFalse();
        assertThat(handler.detectsPath("_vance/hooks")).isFalse();
    }

    @Test
    void getName_isVanceHook() {
        assertThat(handler.getName()).isEqualTo("vance-hook");
    }

    @Test
    void validate_minimalRecipeAction_hasNoFindings() {
        String yaml = """
                description: Notify the owner.
                recipe: arthur
                """;

        assertThat(handler.validate(yaml, ctx("_vance/hooks/process.completed/notify.yaml")))
                .isEmpty();
    }

    @Test
    void validate_unknownEventSegment_isAnErrorBeforeAnyBodyCheck() {
        var findings = handler.validate("recipe: arthur\n", ctx("_vance/hooks/bogus.event/x.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("hook-event-unknown"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_legacySchema_isAnError() {
        // type: js|llm is the pre-unification schema — the parser refuses it
        // with a migration hint, and so does the loader.
        var findings = handler.validate(
                "type: js\nscript: |\n  log('x')\n", ctx("_vance/hooks/process.completed/legacy.yaml"));

        assertThat(findings).filteredOn(f -> f.code().equals("hook-parse")).allSatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.message()).contains("skips this hook");
        });
    }

    @Test
    void validate_bodyWithoutAnyAction_isAnError() {
        var findings = handler.validate("description: Nothing to do.\n", ctx("_vance/hooks/insight.saved/empty.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("hook-parse"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_brokenYaml_isAnError() {
        var findings = handler.validate("recipe: [\n", ctx("_vance/hooks/process.completed/b.yaml"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("hook-parse"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }
}
