package de.mhus.vance.brain.guard;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.Map;
import org.graalvm.polyglot.Engine;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validation surface of the {@code vance-guard} document kind. The
 * handler delegates to the parse-only {@link JsValidationService}, so a
 * finding is exactly what the GraalJS parser rejects — the same
 * {@code Source.newBuilder + Context.parse} the guard executor would run.
 * Semantic problems stay runtime, as the validator documents.
 */
class GuardDocKindHandlerTest {

    private static Engine engine;
    private static GuardDocKindHandler handler;

    @BeforeAll
    static void buildEngine() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        handler = new GuardDocKindHandler(new JsValidationService(engine));
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

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
        public Map<String, Object> readYaml(String path) {
            return null;
        }
    };

    private static KindValidationContext ctx(String docPath) {
        return new KindValidationContext("t", "p", docPath, "text/javascript", NO_REFS);
    }

    @Test
    void detectsPath_claimsTheGuardTree() {
        assertThat(handler.detectsPath("_vance/guards/llm-judge.js")).isTrue();
        assertThat(handler.detectsPath("_vance/guards-old/x.js")).isFalse();
        assertThat(handler.detectsPath("_vance/prompts/arthur-prompt.md")).isFalse();
    }

    @Test
    void getName_isVanceGuard() {
        assertThat(handler.getName()).isEqualTo("vance-guard");
    }

    @Test
    void validate_validGuardScript_hasNoFindings() {
        String js = """
                const judge = vance.params.judge;
                const prompt = vance.params.prompt;
                if (judge && prompt) {
                  const res = vance.llm.callForJson('completion-guard', 'Evaluate.', {
                    judge: judge,
                  });
                  if (res && res.fire) {
                    vance.guard.continueWith(prompt);
                  }
                }
                """;

        assertThat(handler.validate(js, ctx("_vance/guards/my-guard.js"))).isEmpty();
    }

    @Test
    void validate_topLevelReturn_isAnError() {
        // The bug class the bundled llm-judge.js shipped with: a top-level
        // return is a SyntaxError in GraalJS — at the fail-open points the
        // broken guard silently never fires, so it must surface at edit time.
        var findings = handler.validate("if (!x) return;\n", ctx("_vance/guards/legacy-shape.js"));

        assertThat(findings).filteredOn(f -> f.code().equals("guard-js-syntax")).hasSize(1);
    }

    @Test
    void validate_emptyScript_isAnError() {
        var findings = handler.validate("   \n", ctx("_vance/guards/empty.js"));

        assertThat(findings)
                .filteredOn(f -> f.code().equals("guard-js-syntax"))
                .allSatisfy(f -> assertThat(f.level()).isEqualTo(Finding.Level.ERROR));
    }

    @Test
    void validate_syntaxError_isAnErrorWithLocation() {
        // A COMMAND-point guard fails closed on script errors — the finding
        // surfaces that before the wiring ever runs.
        var findings = handler.validate("const x = {\n", ctx("_vance/guards/broken.js"));

        assertThat(findings).filteredOn(f -> f.code().equals("guard-js-syntax")).allSatisfy(f -> {
            assertThat(f.level()).isEqualTo(Finding.Level.ERROR);
            assertThat(f.message()).contains("the guard never runs");
        });
    }
}
