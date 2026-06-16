package de.mhus.vance.shared.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.action.ScriptSource;
import de.mhus.vance.api.action.TriggerAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Disjunction enforcement, sub-field validation, and YAML-shape edge
 * cases for the unified trigger-action parser. Mirrors what
 * scheduler / event / workflow-task loaders feed in after stripping
 * trigger-specific keys (cron, at, on, …).
 */
class TriggerActionParserTest {

    private final TriggerActionParser parser = new TriggerActionParser();

    // ──────────────────── Recipe variant ────────────────────

    @Test
    void recipe_minimal_parses() {
        TriggerAction action = parser.parse(Map.of("recipe", "analyze"));

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Recipe.class, r -> {
            assertThat(r.recipe()).isEqualTo("analyze");
            assertThat(r.initialMessage()).isNull();
            assertThat(r.params()).isNull();
            assertThat(r.runAs()).isNull();
        });
    }

    @Test
    void recipe_with_all_fields_parses() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("recipe", "analyze");
        yaml.put("initialMessage", "Run the briefing for 07:55");
        yaml.put("params", Map.of("model", "default:fast", "validation", false));
        yaml.put("runAs", "mike");

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Recipe.class, r -> {
            assertThat(r.recipe()).isEqualTo("analyze");
            assertThat(r.initialMessage()).isEqualTo("Run the briefing for 07:55");
            assertThat(r.params()).containsEntry("model", "default:fast")
                    .containsEntry("validation", false);
            assertThat(r.runAs()).isEqualTo("mike");
        });
    }

    @Test
    void recipe_blank_name_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of("recipe", "  ")))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .singleElement()
                        .satisfies(err -> {
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.MISSING_FIELD);
                            assertThat(err.field()).isEqualTo("recipe");
                        }));
    }

    // ──────────────────── Script variant (DOCUMENT) ────────────────────

    @Test
    void script_document_minimal_parses() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "scripts/daily.js"));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class, s -> {
            assertThat(s.source()).isEqualTo(ScriptSource.DOCUMENT);
            assertThat(s.path()).isEqualTo("scripts/daily.js");
            assertThat(s.dirName()).isNull();
            assertThat(s.timeoutSeconds()).isNull();
            assertThat(s.params()).isNull();
        });
    }

    @Test
    void script_document_with_dirName_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "scripts/daily.js",
                        "dirName", "scratch"));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> assertThat(err.field()).isEqualTo("script.dirName")));
    }

    @Test
    void script_source_case_insensitive() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "Document",
                        "path", "scripts/daily.js"));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class,
                s -> assertThat(s.source()).isEqualTo(ScriptSource.DOCUMENT));
    }

    // ──────────────────── Script variant (WORKSPACE) ────────────────────

    @Test
    void script_workspace_full_parses() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "workspace",
                        "dirName", "scratch",
                        "path", "gen/process.js",
                        "timeoutSeconds", 60,
                        "params", Map.of("cutoff", "07:55")));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class, s -> {
            assertThat(s.source()).isEqualTo(ScriptSource.WORKSPACE);
            assertThat(s.dirName()).isEqualTo("scratch");
            assertThat(s.path()).isEqualTo("gen/process.js");
            assertThat(s.timeoutSeconds()).isEqualTo(60);
            assertThat(s.params()).containsEntry("cutoff", "07:55");
        });
    }

    @Test
    void script_workspace_without_dirName_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "workspace",
                        "path", "gen/process.js"));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("script.dirName");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.MISSING_FIELD);
                        }));
    }

    @Test
    void script_unknown_source_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "ftp",
                        "path", "x.js"));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("script.source");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_VALUE);
                        }));
    }

    @Test
    void script_missing_source_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of("path", "x.js"));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> assertThat(err.field()).isEqualTo("script.source")));
    }

    @Test
    void script_missing_path_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of("source", "document"));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> assertThat(err.field()).isEqualTo("script.path")));
    }

    @Test
    void script_zero_timeout_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "x.js",
                        "timeoutSeconds", 0));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("timeoutSeconds");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_VALUE);
                        }));
    }

    @Test
    void script_timeoutSeconds_as_string_parses() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "x.js",
                        "timeoutSeconds", "30"));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class,
                s -> assertThat(s.timeoutSeconds()).isEqualTo(30));
    }

    @Test
    void script_params_only_under_script_block_works() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "x.js",
                        "params", Map.of("a", 1)));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class,
                s -> assertThat(s.params()).containsEntry("a", 1));
    }

    @Test
    void script_params_only_at_top_level_works() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of("source", "document", "path", "x.js"),
                "params", Map.of("a", 1));

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Script.class,
                s -> assertThat(s.params()).containsEntry("a", 1));
    }

    @Test
    void script_params_at_both_levels_rejected() {
        Map<String, Object> yaml = Map.of(
                "script", Map.of(
                        "source", "document",
                        "path", "x.js",
                        "params", Map.of("a", 1)),
                "params", Map.of("b", 2));

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("params");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_VALUE);
                        }));
    }

    // ──────────────────── Workflow variant ────────────────────

    @Test
    void workflow_minimal_parses() {
        TriggerAction action = parser.parse(Map.of("workflow", "pr-review"));

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Workflow.class, w -> {
            assertThat(w.workflow()).isEqualTo("pr-review");
            assertThat(w.params()).isNull();
            assertThat(w.runAs()).isNull();
        });
    }

    @Test
    void workflow_with_params_and_runAs_parses() {
        Map<String, Object> yaml = Map.of(
                "workflow", "pr-review",
                "params", Map.of("pr_url", "https://..."),
                "runAs", "ci-bot");

        TriggerAction action = parser.parse(yaml);

        assertThat(action).isInstanceOfSatisfying(TriggerAction.Workflow.class, w -> {
            assertThat(w.workflow()).isEqualTo("pr-review");
            assertThat(w.params()).containsEntry("pr_url", "https://...");
            assertThat(w.runAs()).isEqualTo("ci-bot");
        });
    }

    // ──────────────────── Disjunction errors ────────────────────

    @Test
    void empty_map_rejected_as_NONE_SET() {
        assertThatThrownBy(() -> parser.parse(Map.of()))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .singleElement()
                        .satisfies(err -> assertThat(err.kind())
                                .isEqualTo(ActionValidationError.Kind.NONE_SET)));
    }

    @Test
    void null_map_rejected() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(ActionParseException.class);
    }

    @Test
    void recipe_and_workflow_both_set_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of(
                "recipe", "analyze",
                "workflow", "audit")))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .singleElement()
                        .satisfies(err -> assertThat(err.kind())
                                .isEqualTo(ActionValidationError.Kind.MULTIPLE_SET)));
    }

    @Test
    void recipe_and_script_both_set_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of(
                "recipe", "analyze",
                "script", Map.of("source", "document", "path", "x.js"))))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .singleElement()
                        .satisfies(err -> assertThat(err.kind())
                                .isEqualTo(ActionValidationError.Kind.MULTIPLE_SET)));
    }

    @Test
    void all_three_set_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of(
                "recipe", "a",
                "script", Map.of("source", "document", "path", "x.js"),
                "workflow", "w")))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .singleElement()
                        .satisfies(err -> assertThat(err.kind())
                                .isEqualTo(ActionValidationError.Kind.MULTIPLE_SET)));
    }

    // ──────────────────── Type errors ────────────────────

    @Test
    void recipe_as_number_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of("recipe", 42)))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("recipe");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_TYPE);
                        }));
    }

    @Test
    void script_as_string_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of("script", "scripts/x.js")))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("script");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_TYPE);
                        }));
    }

    @Test
    void params_as_string_rejected() {
        assertThatThrownBy(() -> parser.parse(Map.of(
                "recipe", "analyze",
                "params", "not a map")))
                .isInstanceOf(ActionParseException.class)
                .satisfies(e -> assertThat(((ActionParseException) e).errors())
                        .anySatisfy(err -> {
                            assertThat(err.field()).isEqualTo("params");
                            assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.BAD_TYPE);
                        }));
    }

    // ──────────────────── validate(...) hook ────────────────────

    @Test
    void validate_on_valid_action_returns_empty() {
        TriggerAction a = TriggerAction.Recipe.of("analyze", null, null, null);
        List<ActionValidationError> errors = parser.validate(a);
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_on_null_returns_single_error() {
        List<ActionValidationError> errors = parser.validate(null);
        assertThat(errors).singleElement().satisfies(err ->
                assertThat(err.kind()).isEqualTo(ActionValidationError.Kind.NONE_SET));
    }
}
