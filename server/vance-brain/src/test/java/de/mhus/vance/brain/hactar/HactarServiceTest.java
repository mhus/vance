package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.hactar.HactarService.Severity;
import de.mhus.vance.brain.hactar.HactarService.ValidationIssue;
import de.mhus.vance.brain.hactar.HactarService.ValidationRequest;
import de.mhus.vance.brain.hactar.HactarService.ValidationResult;
import de.mhus.vance.brain.script.JsValidationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests für {@link HactarServiceImpl}. Verwendet einen echten
 * {@link JsValidationService} (mit shared GraalJS-Engine) für
 * realistische Parse-Resultate; {@link LightLlmService} ist gemockt
 * — wir testen das Mapping zwischen LLM-Reply und
 * {@link ValidationResult}, nicht den LightLlm-Stack selbst.
 */
class HactarServiceTest {

    private static final String TENANT = "acme";

    private static Engine engine;
    private static JsValidationService jsValidation;

    private LightLlmService lightLlm;
    private HactarService service;

    @BeforeAll
    static void buildEngine() {
        engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        jsValidation = new JsValidationService(engine);
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        service = new HactarServiceImpl(jsValidation, lightLlm);
    }

    // ──────────────────── validate — happy paths ────────────────────

    @Test
    void validate_acceptsCleanScriptWithoutHeader() {
        ValidationResult r = service.validate(req("function f(x){return x+1;}"));

        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
        assertThat(r.duration().isNegative()).isFalse();
    }

    @Test
    void validate_acceptsCleanScriptWithFullHeader() {
        String code = """
                /**
                 * @description Email bot
                 * @timeout 30m
                 * @statements 10M
                 * @requiresTools imap_fetch, mail_mark_read
                 * @allowTools imap_fetch, mail_mark_read, mail_move
                 */
                (function() { return 42; })();
                """;

        ValidationResult r = service.validate(req(code,
                Set.of("imap_fetch", "mail_mark_read", "mail_move")));

        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void validate_skipsToolIntersectWhenCallerAllowSetIsNull() {
        String code = """
                /**
                 * @requiresTools imap_fetch
                 */
                42;
                """;

        ValidationResult r = service.validate(req(code, null));

        assertThat(r.ok()).isTrue();
    }

    // ──────────────────── validate — failure paths ────────────────────

    @Test
    void validate_reportsSyntaxErrorWithLineColumn() {
        ValidationResult r = service.validate(req("function broken(a) { return a +"));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).hasSize(1);
        ValidationIssue issue = r.issues().get(0);
        assertThat(issue.severity()).isEqualTo(Severity.ERROR);
        assertThat(issue.code()).isEqualTo("syntax");
        assertThat(issue.message()).isNotBlank();
    }

    @Test
    void validate_reportsInvalidHeader() {
        String code = """
                /**
                 * @timeout not-a-duration
                 */
                42;
                """;

        ValidationResult r = service.validate(req(code));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).anyMatch(i ->
                i.code().equals("invalid_header")
                        && i.message().contains("@timeout"));
    }

    @Test
    void validate_reportsMissingRequiredTool() {
        String code = """
                /**
                 * @requiresTools mail_fetch, mail_send
                 */
                (function() { return 1; })();
                """;

        // Caller has mail_fetch but NOT mail_send.
        ValidationResult r = service.validate(req(code, Set.of("mail_fetch")));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues())
                .anyMatch(i -> i.code().equals("missing_required_tool")
                        && i.message().contains("mail_send"))
                .noneMatch(i -> i.code().equals("missing_required_tool")
                        && i.message().contains("mail_fetch"));
    }

    @Test
    void validate_aggregatesMultipleErrors() {
        String code = """
                /**
                 * @requiresTools missing_tool
                 */
                function broken(
                """;

        ValidationResult r = service.validate(req(code, Set.of()));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).extracting(ValidationIssue::code)
                .contains("syntax", "missing_required_tool");
    }

    @Test
    void validate_rejectsUnsupportedLanguage() {
        assertThatThrownBy(() -> service.validate(new ValidationRequest(
                "print('hi')", "py", "x.py", null, TENANT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("py");
    }

    @Test
    void validate_rejectsBlankFields() {
        assertThatThrownBy(() -> new ValidationRequest(
                "code", "", "name", null, TENANT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValidationRequest(
                null, "js", "name", null, TENANT, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValidationRequest(
                "code", "js", "name", null, "", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────── deepValidate ────────────────────

    @Test
    void deepValidate_skipsLlmWhenBasicValidateFails() {
        ValidationResult r = service.deepValidate(req("function broken("));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).anyMatch(i -> i.code().equals("syntax"));
        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void deepValidate_mapsOkReplyToOkResult() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", true,
                "issues", List.of()));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
        verify(lightLlm).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void deepValidate_mapsIssueListToValidationIssues() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", false,
                "issues", List.of(
                        Map.of(
                                "severity", "ERROR",
                                "code", "logic",
                                "message", "Loop condition wrong.",
                                "line", 12,
                                "column", 9),
                        Map.of(
                                "severity", "WARN",
                                "code", "api_misuse",
                                "message", "Snake-case mismatch."))));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).hasSize(2);
        ValidationIssue first = r.issues().get(0);
        assertThat(first.severity()).isEqualTo(Severity.ERROR);
        assertThat(first.code()).isEqualTo("logic");
        assertThat(first.line()).isEqualTo(12);
        assertThat(first.column()).isEqualTo(9);
        ValidationIssue second = r.issues().get(1);
        assertThat(second.severity()).isEqualTo(Severity.WARN);
    }

    @Test
    void deepValidate_acceptsBareStringIssues() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", false,
                "issues", List.of("Off-by-one in the array index calc.")));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).hasSize(1);
        ValidationIssue issue = r.issues().get(0);
        assertThat(issue.severity()).isEqualTo(Severity.ERROR);
        assertThat(issue.code()).isEqualTo("logic");
        assertThat(issue.message()).contains("Off-by-one");
    }

    @Test
    void deepValidate_treatsUnknownSeverityAsError() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", false,
                "issues", List.of(Map.of(
                        "severity", "FATAL",  // not a Severity enum value
                        "message", "Bad."))));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.ok()).isFalse();
        assertThat(r.issues()).hasSize(1);
        assertThat(r.issues().get(0).severity()).isEqualTo(Severity.ERROR);
    }

    @Test
    void deepValidate_returnsOkWhenReplyHasNoIssuesKey() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", true));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void deepValidate_skipsBlankMessageIssues() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "ok", false,
                "issues", List.of(
                        Map.of("severity", "ERROR", "message", ""),
                        Map.of("severity", "ERROR", "message", "Real problem."))));

        ValidationResult r = service.deepValidate(req("var x = 1;"));

        assertThat(r.issues()).hasSize(1);
        assertThat(r.issues().get(0).message()).isEqualTo("Real problem.");
    }

    // ──────────────────── helpers ────────────────────

    private static ValidationRequest req(String code) {
        return req(code, null);
    }

    private static ValidationRequest req(String code, Set<String> allowed) {
        return new ValidationRequest(
                code, "js", "test.js", allowed, TENANT, "proj-1", null);
    }
}
