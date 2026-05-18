package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.script.ScriptHarness.ToolCall;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the {@code school-essay-script-loop-kit}'s
 * {@code write.js}. Pins the orchestration: one {@code process_run}
 * per chapter, in canonical order, with a steerContent that carries
 * the topic + sources + pros/contras + a growing recap of previous
 * chapters.
 *
 * <p>{@code process_run} is mocked to return a deterministic stub
 * body per chapter ("CHAPTER-BODY-FOR-<NAME>"), which is what the
 * harness records as the would-be ASSISTANT reply. That lets us
 * assert the {@code doc_write_text} payloads and the chapter-recap
 * propagation without spinning up real LLM turns.
 */
class SchoolEssayLoopScriptUnitTest {

    private static final Path WRITE_JS = Path.of(
            "../../../../qa/kits/school-essay-script-loop-kit/documents/skills/"
                    + "school-essay-script-loop/scripts/write.js");

    private static final String EXPECTED_MARKER =
            "SCHOOL-ESSAY-SCRIPT-LOOP-PT8M2-WRITTEN";

    private static final List<String> CHAPTER_ORDER = List.of(
            "einleitung", "pro", "contra", "vergleich", "fazit");

    @Test
    void write_spawns_one_processRun_per_chapter_in_canonical_order()
            throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Sollten Schulnoten abgeschafft werden?"))
                .mockTool("doc_write_text",
                        p -> Map.of("path", p.get("path"), "size", 0))
                .mockTool("process_run", processRunStub())
                .build();

        ScriptResult result = harness.run();

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertThat(value).containsEntry("ok", true)
                .containsEntry("marker", EXPECTED_MARKER);

        // Exactly 5 process_run calls, exactly in chapter order.
        List<ToolCall> spawns = harness.toolCalls().stream()
                .filter(t -> t.name().equals("process_run"))
                .toList();
        assertThat(spawns).hasSize(5);
        assertThat(spawns).extracting(t -> t.params().get("name"))
                .containsExactly(
                        "chapter-einleitung", "chapter-pro", "chapter-contra",
                        "chapter-vergleich", "chapter-fazit");
        // All sub-workers route through the 'ford' recipe.
        assertThat(spawns).allSatisfy(t ->
                assertThat(t.params()).containsEntry("recipe", "ford"));
    }

    @Test
    void write_steerContent_carries_topic_sources_pros_contras() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Hausaufgaben — pro oder contra?"))
                .mockTool("doc_write_text", p -> Map.of())
                .mockTool("process_run", processRunStub())
                .build();

        harness.run();

        ToolCall firstSpawn = harness.toolCalls().stream()
                .filter(t -> t.name().equals("process_run"))
                .findFirst().orElseThrow();
        String steer = (String) firstSpawn.params().get("steerContent");
        assertThat(steer)
                .contains("Hausaufgaben — pro oder contra?")
                .contains("Pro-Argument 1")          // from samplePayload
                .contains("Contra-Argument 1")
                .contains("FOCUS")                     // sample source
                .contains("Auftrag: Kapitel \"einleitung\"");
    }

    @Test
    void write_each_chapter_sees_recap_of_previous_chapters() throws Exception {
        // process_run replies with a per-chapter body. The script
        // builds a recap of all previously-completed chapters and
        // injects it into the next worker's steerContent. We capture
        // each steerContent and assert that the recap grows.
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Topic"))
                .mockTool("doc_write_text", p -> Map.of())
                .mockTool("process_run", processRunStub())
                .build();

        harness.run();

        List<ToolCall> spawns = harness.toolCalls().stream()
                .filter(t -> t.name().equals("process_run"))
                .toList();

        // First chapter has the "noch kein Kapitel" sentinel.
        assertThat((String) spawns.get(0).params().get("steerContent"))
                .contains("Noch kein Kapitel geschrieben");

        // Second chapter must see chapter #1's recap, but not its own.
        String secondSteer = (String) spawns.get(1).params().get("steerContent");
        assertThat(secondSteer)
                .contains("## einleitung")
                .doesNotContain("## pro");

        // Third chapter sees both #1 and #2.
        String thirdSteer = (String) spawns.get(2).params().get("steerContent");
        assertThat(thirdSteer)
                .contains("## einleitung")
                .contains("## pro")
                .doesNotContain("## contra");

        // Last chapter sees the four predecessors.
        String fifthSteer = (String) spawns.get(4).params().get("steerContent");
        assertThat(fifthSteer)
                .contains("## einleitung")
                .contains("## pro")
                .contains("## contra")
                .contains("## vergleich")
                .doesNotContain("## fazit");
    }

    @Test
    void write_persists_all_canonical_essay_paths() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Topic"))
                .mockTool("doc_write_text", p -> Map.of())
                .mockTool("process_run", processRunStub())
                .build();

        ScriptResult result = harness.run();

        @SuppressWarnings("unchecked")
        List<String> filesWritten = (List<String>)
                ((Map<String, Object>) result.value()).get("filesWritten");
        assertThat(filesWritten).containsExactly(
                "essay/research-question.md",
                "essay/sources.md",
                "essay/chapters/01-einleitung.md",
                "essay/chapters/02-pro.md",
                "essay/chapters/03-contra.md",
                "essay/chapters/04-vergleich.md",
                "essay/chapters/05-fazit.md",
                "essay/final-essay.md");

        // doc_write_text calls happen up-front for the meta-files,
        // inline for each chapter, then once for the final assembly.
        // Total 8 — same count as the simpler kit, just interleaved
        // with the process_run calls.
        long writes = harness.toolCalls().stream()
                .filter(t -> t.name().equals("doc_write_text"))
                .count();
        assertThat(writes).isEqualTo(8);
    }

    @Test
    void write_final_essay_concatenates_subworker_replies() throws Exception {
        // Capture each chapter body returned by process_run, then
        // verify all five appear verbatim in essay/final-essay.md.
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Topic"))
                .mockTool("doc_write_text", p -> Map.of())
                .mockTool("process_run", processRunStub())
                .build();

        harness.run();

        ToolCall finalCall = harness.toolCalls().stream()
                .filter(t -> t.name().equals("doc_write_text")
                        && "essay/final-essay.md".equals(t.params().get("path")))
                .findFirst().orElseThrow();
        String finalBody = (String) finalCall.params().get("content");
        for (String chapter : CHAPTER_ORDER) {
            assertThat(finalBody)
                    .as("final-essay must include the body for chapter %s", chapter)
                    .contains("CHAPTER-BODY-FOR-" + chapter.toUpperCase());
        }
        assertThat(finalBody).startsWith("# Topic");
    }

    @Test
    void write_processRun_returns_no_reply_fails_fast() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(samplePayload("Topic"))
                .mockTool("doc_write_text", p -> Map.of())
                // Empty reply on chapter "contra" — third in the order.
                .mockTool("process_run", params -> {
                    String name = String.valueOf(params.get("name"));
                    if (name.equals("chapter-contra")) {
                        return Map.of("processId", "p-fail", "status", "FAILED");
                    }
                    return processRunStub().apply(params);
                })
                .build();

        assertThatThrownBy(harness::run)
                .hasMessageContaining("contra")
                .hasMessageContaining("no reply");
    }

    @Test
    void write_missing_pros_or_contras_fails_fast() throws Exception {
        Map<String, Object> noPros = samplePayload("Topic");
        noPros.put("pros", List.of());

        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(WRITE_JS)
                .args(noPros)
                .mockTool("doc_write_text", p -> Map.of())
                .mockTool("process_run", processRunStub())
                .build();

        assertThatThrownBy(harness::run)
                .hasMessageContaining("pros and contras");
        // No process_run + no doc_write_text should fire — the
        // validator trips before the loop.
        assertThat(harness.toolCalls()).isEmpty();
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Builds a {@code process_run} stub that returns a deterministic
     * body per chapter, derived from the {@code name} parameter. The
     * body carries an uppercase marker so the final-essay assertion
     * can pin every chapter's presence by substring.
     */
    private static java.util.function.Function<Map<String, Object>, Map<String, Object>>
    processRunStub() {
        return params -> {
            String workerName = String.valueOf(params.get("name"));
            // workerName looks like "chapter-einleitung" → strip prefix.
            String chapter = workerName.startsWith("chapter-")
                    ? workerName.substring("chapter-".length())
                    : workerName;
            String body = "CHAPTER-BODY-FOR-" + chapter.toUpperCase()
                    + " — Lorem ipsum dolor sit amet (vgl. Quelle 1, 2024).";
            return Map.of(
                    "processId", "p-" + workerName,
                    "status", "DONE",
                    "engine", "ford",
                    "recipe", "ford",
                    "reply", body);
        };
    }

    private static Map<String, Object> samplePayload(String topic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topic", topic);
        payload.put("styleNotes",
                "Sachlich, ausgewogen, mit (vgl. ..., JJJJ)-Belegen.");
        payload.put("sources", List.of(
                Map.of("title", "FOCUS Online",
                        "url", "https://focus.de/x",
                        "snippet", "Pro/Contra-Übersicht"),
                Map.of("title", "Tagesschau",
                        "url", "https://tagesschau.de/y")));
        List<String> pros = new ArrayList<>();
        pros.add("Pro-Argument 1: Strukturierung des Lernens");
        pros.add("Pro-Argument 2: Vergleichbarkeit");
        List<String> contras = new ArrayList<>();
        contras.add("Contra-Argument 1: Druck auf Schüler");
        contras.add("Contra-Argument 2: Reduktion auf Zahlen");
        payload.put("pros", pros);
        payload.put("contras", contras);
        return payload;
    }
}
