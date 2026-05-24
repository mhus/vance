package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.marvin.ConcludeOutput;
import de.mhus.vance.api.marvin.PostChildrenAction;
import de.mhus.vance.api.marvin.PostChildrenOutput;
import de.mhus.vance.api.marvin.ReflectAction;
import de.mhus.vance.api.marvin.ReflectOutput;
import de.mhus.vance.api.marvin.ScopeAction;
import de.mhus.vance.api.marvin.ScopeOutput;
import de.mhus.vance.api.marvin.ValidateOutput;
import de.mhus.vance.api.marvin.ValidateVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PhaseOutputParserTest {

    private PhaseOutputParser parser;

    @BeforeEach
    void setUp() {
        parser = new PhaseOutputParser(new ObjectMapper());
    }

    // ─── SCOPE ───

    @Test
    void scope_callRecipe_parses() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"CALL_RECIPE",
                 "recipeCall":{"recipe":"web-research",
                               "steerContent":"recherchiere"},
                 "reason":"need data"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().action()).isEqualTo(ScopeAction.CALL_RECIPE);
        assertThat(r.output().recipeCall().recipe()).isEqualTo("web-research");
        assertThat(r.output().recipeCall().steerContent()).isEqualTo("recherchiere");
    }

    @Test
    void scope_proceedToConclude_parses() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"PROCEED_TO_CONCLUDE","reason":"have answer"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().action()).isEqualTo(ScopeAction.PROCEED_TO_CONCLUDE);
    }

    @Test
    void scope_callRecipe_missingRecipeCall_fails() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"CALL_RECIPE","reason":"oops"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("recipeCall");
    }

    @Test
    void scope_needsSubtasks_emptyArray_fails() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"NEEDS_SUBTASKS","newTasks":[],"reason":"split"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("non-empty");
    }

    @Test
    void scope_invalidAction_fails() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"INVALID","reason":"x"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("INVALID");
    }

    @Test
    void scope_blockedByProblem_missingProblem_fails() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("""
                {"action":"BLOCKED_BY_PROBLEM","reason":"x"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("problem");
    }

    @Test
    void scope_blank_fails() {
        PhaseOutputParser.Result<ScopeOutput> r = parser.parseScope("");
        assertThat(r.ok()).isFalse();
    }

    // ─── REFLECT ───

    @Test
    void reflect_proceedToConclude_parses() {
        PhaseOutputParser.Result<ReflectOutput> r = parser.parseReflect("""
                {"action":"PROCEED_TO_CONCLUDE","reason":"ok"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().action()).isEqualTo(ReflectAction.PROCEED_TO_CONCLUDE);
    }

    // ─── POST_CHILDREN ───

    @Test
    void postChildren_proceedToConclude_parses() {
        PhaseOutputParser.Result<PostChildrenOutput> r = parser.parsePostChildren("""
                {"action":"PROCEED_TO_CONCLUDE","reason":"children ok"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().action()).isEqualTo(PostChildrenAction.PROCEED_TO_CONCLUDE);
    }

    @Test
    void postChildren_needsSubtasks_emptyTasks_fails() {
        PhaseOutputParser.Result<PostChildrenOutput> r = parser.parsePostChildren("""
                {"action":"NEEDS_SUBTASKS","reason":"split"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("newTasks");
    }

    // ─── CONCLUDE ───

    @Test
    void conclude_withResult_parses() {
        PhaseOutputParser.Result<ConcludeOutput> r = parser.parseConclude("""
                {"result":"# Answer\\nFinal text.","reason":"done"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().result()).startsWith("# Answer");
    }

    @Test
    void conclude_missingResult_fails() {
        PhaseOutputParser.Result<ConcludeOutput> r = parser.parseConclude("""
                {"reason":"oops"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("result");
    }

    @Test
    void conclude_withPostActions_parses() {
        PhaseOutputParser.Result<ConcludeOutput> r = parser.parseConclude("""
                {"result":"answer",
                 "postActions":[
                   {"tool":"doc_write_text",
                    "args":{"path":"r/x.md","content":"{{ node.result }}"}}],
                 "reason":"done"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().postActions()).hasSize(1);
        assertThat(r.output().postActions().get(0).tool()).isEqualTo("doc_write_text");
    }

    // ─── VALIDATE ───

    @Test
    void validate_pass_parses() {
        PhaseOutputParser.Result<ValidateOutput> r = parser.parseValidate("""
                {"verdict":"PASS","reason":"complete"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().verdict()).isEqualTo(ValidateVerdict.PASS);
    }

    @Test
    void validate_retryConclude_parsesIssuesAndHint() {
        PhaseOutputParser.Result<ValidateOutput> r = parser.parseValidate("""
                {"verdict":"RETRY_CONCLUDE",
                 "issues":["missing source citation","unclear conclusion"],
                 "hint":"add citations",
                 "reason":"incomplete"}
                """);
        assertThat(r.ok()).isTrue();
        assertThat(r.output().verdict()).isEqualTo(ValidateVerdict.RETRY_CONCLUDE);
        assertThat(r.output().issues()).hasSize(2);
        assertThat(r.output().hint()).isEqualTo("add citations");
    }

    @Test
    void validate_invalidVerdict_fails() {
        PhaseOutputParser.Result<ValidateOutput> r = parser.parseValidate("""
                {"verdict":"MAYBE","reason":"x"}
                """);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("MAYBE");
    }
}
