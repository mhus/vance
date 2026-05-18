package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;

import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsValidationService}. Shares a single shared
 * GraalJS {@link Engine} across the class to mirror the Spring
 * configuration in {@link ScriptEngineConfig} — building one engine
 * per test runs ~3× slower without changing semantics.
 */
class JsValidationServiceTest {

    private static Engine engine;
    private static JsValidationService service;

    @BeforeAll
    static void buildEngine() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        service = new JsValidationService(engine);
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

    @Test
    void validate_acceptsValidScript() {
        JsValidationService.JsValidationResult result =
                service.validate("function add(a, b) { return a + b; }", "valid.js");

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validate_acceptsScriptWithJsdocHeader() {
        String code = """
                /**
                 * @timeout 30s
                 * @requiresTools doc_write_text
                 */
                (function () { return 42; })();
                """;
        JsValidationService.JsValidationResult result =
                service.validate(code, "with-header.js");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_acceptsModernJsSyntax() {
        // Arrow fn + spread + template literal — quick check that the
        // GraalJS parser handles ES2017+ without an explicit version
        // option (default = latest stable).
        String code = "const f = (...xs) => `n=${xs.length}`; f(1,2,3);";
        JsValidationService.JsValidationResult result =
                service.validate(code, "modern.js");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_reportsSyntaxErrorWithLineColumn() {
        // Missing closing brace.
        String code = "function broken(a) { return a + ;\n";
        JsValidationService.JsValidationResult result =
                service.validate(code, "broken.js");

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSize(1);
        JsValidationService.JsValidationError err = result.errors().get(0);
        assertThat(err.sourceName()).isEqualTo("broken.js");
        // Line should be 1-based and >= 1 when the parser pins a
        // SourceSection. (GraalJS pins line=1 for this particular
        // failure — but the contract only guarantees line >= 0.)
        assertThat(err.line()).isGreaterThanOrEqualTo(0);
        assertThat(err.message()).isNotBlank();
    }

    @Test
    void validate_reportsUnterminatedString() {
        JsValidationService.JsValidationResult result =
                service.validate("var x = \"unterminated;", "string.js");

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void validate_emptyCode_returnsError() {
        JsValidationService.JsValidationResult result =
                service.validate("", "empty.js");

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).containsIgnoringCase("empty");
    }

    @Test
    void validate_blankCode_returnsError() {
        JsValidationService.JsValidationResult result =
                service.validate("   \n  \t  ", "blank.js");

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void validate_nullCode_returnsError() {
        JsValidationService.JsValidationResult result =
                service.validate(null, "null.js");

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void validate_nullSourceName_usesPlaceholder() {
        JsValidationService.JsValidationResult result =
                service.validate("var x = 1;", null);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_blankSourceName_usesPlaceholder() {
        // Source name shouldn't affect ok-status; this test pins
        // that an empty name doesn't crash the Source.newBuilder.
        JsValidationService.JsValidationResult result =
                service.validate("var x = 1;", "   ");

        assertThat(result.ok()).isTrue();
    }

    @Test
    void validate_doesNotExecuteCode() {
        // Side-effecting code that would crash if eval-ed (no host
        // access, no thread access) — must parse cleanly because we
        // only parse.
        String code = """
                throw new Error('this should NOT be thrown — parse only');
                """;
        JsValidationService.JsValidationResult result =
                service.validate(code, "side-effect.js");

        assertThat(result.ok()).isTrue();
    }
}
