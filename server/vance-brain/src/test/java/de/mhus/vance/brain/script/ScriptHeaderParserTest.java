package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the parser rules from {@code specification/script-engine.md}
 * §3.5. Pure parse — no GraalJS-Context involved, no clamping (that
 * lives in {@link GraaljsScriptExecutor}).
 */
class ScriptHeaderParserTest {

    @Test
    void parse_no_header_returns_empty() {
        ScriptHeader h = ScriptHeaderParser.parse("var x = 1;", "test");
        assertThat(h.isPresent()).isFalse();
        assertThat(h.timeout()).isNull();
        assertThat(h.statementLimit()).isNull();
        assertThat(h.allowTools()).isEmpty();
        assertThat(h.requiresTools()).isEmpty();
    }

    @Test
    void parse_first_block_only_later_blocks_ignored() {
        String code = """
                /**
                 * @timeout 10m
                 */
                /**
                 * @timeout 1h
                 */
                42
                """;
        ScriptHeader h = ScriptHeaderParser.parse(code, "test");
        assertThat(h.timeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void parse_duration_formats() {
        assertThat(parseTimeout("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(parseTimeout("10m")).isEqualTo(Duration.ofMinutes(10));
        assertThat(parseTimeout("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(parseTimeout("600")).isEqualTo(Duration.ofSeconds(600));
        // Underscores allowed as thousands-sep.
        assertThat(parseTimeout("1_800")).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    void parse_statement_count_formats() {
        assertThat(parseStatements("1000")).isEqualTo(1_000L);
        assertThat(parseStatements("100k")).isEqualTo(100_000L);
        assertThat(parseStatements("5M")).isEqualTo(5_000_000L);
        assertThat(parseStatements("1_000_000")).isEqualTo(1_000_000L);
    }

    @Test
    void parse_allowTools_comma_and_whitespace_separated() {
        ScriptHeader commas = ScriptHeaderParser.parse("""
                /**
                 * @allowTools  doc_write_text, process_run, web_search
                 */
                """, "test");
        assertThat(commas.allowTools())
                .containsExactly("doc_write_text", "process_run", "web_search");

        ScriptHeader spaces = ScriptHeaderParser.parse("""
                /**
                 * @allowTools doc_write_text  process_run   web_search
                 */
                """, "test");
        assertThat(spaces.allowTools())
                .containsExactly("doc_write_text", "process_run", "web_search");
    }

    @Test
    void parse_requiresTools_separate_from_allowTools() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                /**
                 * @requiresTools process_run
                 * @allowTools    process_run, doc_write_text
                 */
                """, "test");
        assertThat(h.requiresTools()).containsExactly("process_run");
        assertThat(h.allowTools())
                .containsExactly("process_run", "doc_write_text");
    }

    @Test
    void parse_description_and_version() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                /**
                 * @description School-essay chapter-loop orchestrator
                 * @version     1.2.0
                 */
                """, "test");
        assertThat(h.description())
                .isEqualTo("School-essay chapter-loop orchestrator");
        assertThat(h.version()).isEqualTo("1.2.0");
    }

    @Test
    void parse_unknown_tag_is_silently_ignored() {
        // Unknown tags warn-log but never throw. The script still
        // gets a valid header for the known parts.
        ScriptHeader h = ScriptHeaderParser.parse("""
                /**
                 * @timeout 10m
                 * @notARealTag whatever
                 */
                """, "test");
        assertThat(h.timeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void parse_duplicate_tag_last_wins() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                /**
                 * @timeout 30s
                 * @timeout 5m
                 */
                """, "test");
        assertThat(h.timeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void parse_malformed_duration_throws_INVALID_HEADER() {
        assertThatThrownBy(() -> parseTimeout("abc"))
                .isInstanceOf(ScriptExecutionException.class)
                .satisfies(e -> assertThat(
                        ((ScriptExecutionException) e).errorClass())
                        .isEqualTo(ScriptExecutionException.ErrorClass.INVALID_HEADER))
                .hasMessageContaining("@timeout");
    }

    @Test
    void parse_negative_duration_throws_INVALID_HEADER() {
        assertThatThrownBy(() -> parseTimeout("-5s"))
                .isInstanceOf(ScriptExecutionException.class);
    }

    @Test
    void parse_zero_statements_throws_INVALID_HEADER() {
        assertThatThrownBy(() -> parseStatements("0"))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("@statements");
    }

    @Test
    void parse_handles_jsdoc_continuation_stars() {
        // JSDoc style: every line inside the block starts with " * ".
        // The parser must strip that before matching @tag.
        ScriptHeader h = ScriptHeaderParser.parse("""
                /**
                 *  Some prose intro line
                 *
                 *  @timeout 5m
                 *  @description Has leading-star prefix
                 */
                """, "test");
        assertThat(h.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(h.description())
                .isEqualTo("Has leading-star prefix");
    }

    @Test
    void parse_blank_code_returns_empty() {
        assertThat(ScriptHeaderParser.parse("", "test").isPresent()).isFalse();
        assertThat(ScriptHeaderParser.parse("   \n  ", "test").isPresent()).isFalse();
        assertThat(ScriptHeaderParser.parse(null, "test").isPresent()).isFalse();
    }

    @Test
    void parse_block_after_first_executable_statement_is_ignored() {
        // Header must precede the first statement. A doc-block that
        // sits between code lines is regular doc, not a header.
        ScriptHeader h = ScriptHeaderParser.parse("""
                var x = 1;
                /**
                 * @timeout 10m
                 */
                """, "test");
        // Header pattern is anchored at start-of-input so this falls
        // through to "no header" — empty result.
        assertThat(h.isPresent()).isFalse();
    }

    // ──────────────────── helpers ────────────────────

    private static Duration parseTimeout(String value) {
        ScriptHeader h = ScriptHeaderParser.parse(
                "/**\n * @timeout " + value + "\n */\n", "test");
        return h.timeout();
    }

    private static long parseStatements(String value) {
        ScriptHeader h = ScriptHeaderParser.parse(
                "/**\n * @statements " + value + "\n */\n", "test");
        return h.statementLimit();
    }
}
