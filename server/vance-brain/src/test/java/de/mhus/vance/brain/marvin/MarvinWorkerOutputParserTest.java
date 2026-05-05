package de.mhus.vance.brain.marvin;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.marvin.MarvinWorkerOutput;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.NewTaskSpec;
import de.mhus.vance.api.marvin.MarvinWorkerOutput.UserInputSpec;
import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.api.marvin.WorkerOutcome;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link MarvinWorkerOutputParser} — the validator
 * that pins the worker output contract from
 * {@code specification/marvin-engine.md} §5a. Pure logic, no LLM,
 * no Spring; we instantiate the parser with a fresh {@link ObjectMapper}.
 */
class MarvinWorkerOutputParserTest {

    private MarvinWorkerOutputParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarvinWorkerOutputParser(new ObjectMapper());
    }

    // ─── Happy-path outcomes ───────────────────────────────────────────

    @Test
    void done_withResult_parsesOk() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                Some prose before.
                {
                  "outcome": "DONE",
                  "result": "The answer is 42.",
                  "reason": "obvious"
                }
                """);

        assertThat(r.ok()).isTrue();
        MarvinWorkerOutput out = r.output();
        assertThat(out.getOutcome()).isEqualTo(WorkerOutcome.DONE);
        assertThat(out.getResult()).isEqualTo("The answer is 42.");
        assertThat(out.getReason()).isEqualTo("obvious");
        assertThat(out.getNewTasks()).isEmpty();
        assertThat(out.getUserInput()).isNull();
    }

    @Test
    void needsSubtasks_withWorkerChild_parsesNewTaskSpec() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "result": "Partial — needs decomposition.",
                  "newTasks": [
                    {"goal": "Verify quote in section 3",
                     "taskKind": "WORKER",
                     "taskSpec": {"recipe": "code-read", "steerContent": "look it up"}}
                  ],
                  "reason": "two distinct sub-problems"
                }
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getOutcome()).isEqualTo(WorkerOutcome.NEEDS_SUBTASKS);
        assertThat(r.output().getResult()).isEqualTo("Partial — needs decomposition.");
        assertThat(r.output().getNewTasks()).hasSize(1);
        NewTaskSpec t = r.output().getNewTasks().get(0);
        assertThat(t.getGoal()).isEqualTo("Verify quote in section 3");
        assertThat(t.getTaskKind()).isEqualTo(TaskKind.WORKER);
        assertThat(t.getTaskSpec())
                .containsEntry("recipe", "code-read")
                .containsEntry("steerContent", "look it up");
    }

    @Test
    void needsSubtasks_withExpandFromDocChild_preservesNestedTaskSpecMaps() {
        // The doc-driven trigger pattern (§7a.3 (b)) — worker emits an
        // EXPAND_FROM_DOC entry referencing a freshly written outline doc.
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "newTasks": [
                    {
                      "goal": "Materialize chapters from the outline doc",
                      "taskKind": "EXPAND_FROM_DOC",
                      "taskSpec": {
                        "documentRef": {"name": "chapters-outline"},
                        "childTemplate": {
                          "goal": "Write: {{item.text}}",
                          "taskKind": "WORKER",
                          "taskSpec": {"recipe": "write-section"}
                        }
                      }
                    }
                  ]
                }
                """);

        assertThat(r.ok()).isTrue();
        NewTaskSpec t = r.output().getNewTasks().get(0);
        assertThat(t.getTaskKind()).isEqualTo(TaskKind.EXPAND_FROM_DOC);
        @SuppressWarnings("unchecked")
        Map<String, Object> docRef = (Map<String, Object>) t.getTaskSpec().get("documentRef");
        assertThat(docRef).containsEntry("name", "chapters-outline");
        @SuppressWarnings("unchecked")
        Map<String, Object> childTemplate =
                (Map<String, Object>) t.getTaskSpec().get("childTemplate");
        assertThat(childTemplate)
                .containsEntry("goal", "Write: {{item.text}}")
                .containsEntry("taskKind", "WORKER");
    }

    @Test
    void needsUserInput_withFullSpec_parsesUserInputSpec() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_USER_INPUT",
                  "result": "I'm stuck on the baseline choice.",
                  "userInput": {
                    "type": "DECISION",
                    "title": "Which baseline?",
                    "body": "Pick A or B.",
                    "criticality": "NORMAL",
                    "payload": {"options": ["a", "b"]}
                  }
                }
                """);

        assertThat(r.ok()).isTrue();
        UserInputSpec ui = r.output().getUserInput();
        assertThat(ui).isNotNull();
        assertThat(ui.getType()).isEqualTo("DECISION");
        assertThat(ui.getTitle()).isEqualTo("Which baseline?");
        assertThat(ui.getBody()).isEqualTo("Pick A or B.");
        assertThat(ui.getCriticality()).isEqualTo("NORMAL");
        assertThat(ui.getPayload()).containsKey("options");
    }

    @Test
    void blockedByProblem_withProblemText_parsesOk() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "BLOCKED_BY_PROBLEM",
                  "problem": "API key missing",
                  "reason": "cannot proceed without provider creds"
                }
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getOutcome()).isEqualTo(WorkerOutcome.BLOCKED_BY_PROBLEM);
        assertThat(r.output().getProblem()).isEqualTo("API key missing");
    }

    // ─── Empty / malformed input ───────────────────────────────────────

    @Test
    void nullInput_failsWithEmptyMessage() {
        MarvinWorkerOutputParser.Result r = parser.parse(null);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("empty");
    }

    @Test
    void blankInput_failsWithEmptyMessage() {
        MarvinWorkerOutputParser.Result r = parser.parse("   \n  \t");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("empty");
    }

    @Test
    void noJsonObjectAnywhere_failsWithSchemaHint() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "Just prose. No JSON in sight.");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("does not contain a JSON object");
    }

    @Test
    void malformedJson_failsWithJacksonMessage() {
        // Has braces (so extractJsonObject returns a substring) but the
        // content between them isn't valid JSON — exercises the Jackson
        // parse-failure branch.
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"DONE\" \"missing-comma\": true}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("JSON is not valid");
    }

    // ─── Outcome validation ────────────────────────────────────────────

    @Test
    void missingOutcomeField_failsWithRequiredHint() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"result\": \"answer\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'outcome'").contains("missing");
    }

    @Test
    void unknownOutcomeValue_failsWithAllowedList() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"MAYBE\", \"result\": \"x\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error())
                .contains("MAYBE")
                .contains("DONE")
                .contains("NEEDS_SUBTASKS");
    }

    @Test
    void doneWithoutResult_failsRequiringResult() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"DONE\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'result'").contains("DONE");
    }

    @Test
    void blockedWithoutProblem_failsRequiringProblem() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"BLOCKED_BY_PROBLEM\", \"reason\": \"oops\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'problem'").contains("BLOCKED_BY_PROBLEM");
    }

    @Test
    void needsSubtasksWithEmptyArray_failsRequiringNonEmpty() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"NEEDS_SUBTASKS\", \"newTasks\": []}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'newTasks'").contains("non-empty");
    }

    @Test
    void needsSubtasksWithoutNewTasksKey_failsRequiringArray() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"NEEDS_SUBTASKS\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'newTasks'");
    }

    @Test
    void needsUserInputWithoutObject_failsRequiringUserInput() {
        MarvinWorkerOutputParser.Result r = parser.parse(
                "{\"outcome\": \"NEEDS_USER_INPUT\", \"result\": \"partial\"}");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("'userInput'");
    }

    // ─── JSON extraction edge cases ────────────────────────────────────

    @Test
    void multipleJsonObjects_lastOneWins() {
        // Earlier object is an "example" the worker quoted; the schema
        // postfix says the LAST trailing object is the real one.
        MarvinWorkerOutputParser.Result r = parser.parse("""
                Earlier example for context:
                {"outcome": "DONE", "result": "wrong-old-answer"}

                Now the real answer:
                {"outcome": "DONE", "result": "correct-final-answer"}
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getResult()).isEqualTo("correct-final-answer");
    }

    @Test
    void jsonInFencedCodeBlock_extractsCleanly() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                Here is my structured reply:

                ```json
                {
                  "outcome": "DONE",
                  "result": "fenced-answer"
                }
                ```
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getResult()).isEqualTo("fenced-answer");
    }

    @Test
    void nestedBraces_braceCounterFindsOuterObject() {
        // The taskSpec's nested object has its own braces — the parser
        // walks back from the LAST '}' counting depth, so it correctly
        // identifies the outer envelope, not the inner taskSpec.
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "newTasks": [
                    {"goal": "x", "taskKind": "WORKER",
                     "taskSpec": {"recipe": "ford", "params": {"model": "default:fast"}}}
                  ]
                }
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getNewTasks()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>)
                r.output().getNewTasks().get(0).getTaskSpec().get("params");
        assertThat(params).containsEntry("model", "default:fast");
    }

    // ─── newTasks lenient parsing ──────────────────────────────────────

    @Test
    void newTaskSpec_blankGoalEntries_silentlySkipped() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "newTasks": [
                    {"goal": "", "taskKind": "WORKER"},
                    {"goal": "real one", "taskKind": "WORKER",
                     "taskSpec": {"recipe": "ford"}},
                    {"goal": "   ", "taskKind": "WORKER"}
                  ]
                }
                """);

        // Validation passes because the array is non-empty at the JSON
        // level; the lenient per-entry filter then drops the two blank
        // entries, leaving one.
        assertThat(r.ok()).isTrue();
        assertThat(r.output().getNewTasks()).hasSize(1);
        assertThat(r.output().getNewTasks().get(0).getGoal()).isEqualTo("real one");
    }

    @Test
    void newTaskSpec_unknownTaskKind_entrySkippedNotFailed() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "newTasks": [
                    {"goal": "good", "taskKind": "WORKER"},
                    {"goal": "bad",  "taskKind": "NOT_A_KIND"}
                  ]
                }
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getNewTasks()).hasSize(1);
        assertThat(r.output().getNewTasks().get(0).getGoal()).isEqualTo("good");
    }

    @Test
    void newTaskSpec_omittedTaskKind_defaultsToWorker() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_SUBTASKS",
                  "newTasks": [
                    {"goal": "no kind specified",
                     "taskSpec": {"recipe": "ford"}}
                  ]
                }
                """);

        assertThat(r.ok()).isTrue();
        assertThat(r.output().getNewTasks()).hasSize(1);
        assertThat(r.output().getNewTasks().get(0).getTaskKind())
                .isEqualTo(TaskKind.WORKER);
    }

    // ─── userInput partial fields ──────────────────────────────────────

    @Test
    void userInput_onlyTitleProvided_otherFieldsKeepDefaults() {
        MarvinWorkerOutputParser.Result r = parser.parse("""
                {
                  "outcome": "NEEDS_USER_INPUT",
                  "result": "partial",
                  "userInput": {"title": "Question?"}
                }
                """);

        assertThat(r.ok()).isTrue();
        UserInputSpec ui = r.output().getUserInput();
        assertThat(ui).isNotNull();
        assertThat(ui.getTitle()).isEqualTo("Question?");
        assertThat(ui.getType()).isEqualTo("FEEDBACK"); // builder default
        assertThat(ui.getBody()).isNull();
        assertThat(ui.getCriticality()).isNull();
        assertThat(ui.getPayload()).isEmpty();
    }
}
