package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.brain.script.ScriptHarness.ToolCall;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of the {@code school-essay-script-kit} fixture's
 * {@code assemble.js} — the deterministic persist + concat path the
 * skill body hands off to once the LLM has done research and drafting.
 *
 * <p>Each test runs in &lt; 1 s via {@link ScriptHarness}. The E2E
 * companion (a real LLM driving the full skill body) is intentionally
 * not the inner-dev loop — it's slow and the LLM's variability hides
 * unrelated script bugs. The unit tests here pin everything except
 * the LLM picking the tool.
 */
class SchoolEssayAssembleScriptUnitTest {

    private static final Path ASSEMBLE_JS = Path.of(
            "../../../../qa/kits/school-essay-script-kit/documents/skills/"
                    + "school-essay-script/scripts/assemble.js");

    private static final String EXPECTED_MARKER = "SCHOOL-ESSAY-SCRIPT-XJ4K9-ASSEMBLED";

    @Test
    void assemble_writes_all_eight_essay_files_in_canonical_order()
            throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(samplePayload("Sollten Schulnoten abgeschafft werden?"))
                .mockTool("doc_create",
                        params -> Map.of("path", params.get("path"),
                                "size", ((String) params.get("content")).length()))
                .build();

        ScriptResult result = harness.run();

        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertThat(value).containsEntry("ok", true)
                .containsEntry("marker", EXPECTED_MARKER);
        // 1 research-question + 1 sources + 5 chapters + 1 final = 8 files
        @SuppressWarnings("unchecked")
        List<String> filesWritten = (List<String>) value.get("filesWritten");
        assertThat(filesWritten).containsExactly(
                "essay/research-question.md",
                "essay/sources.md",
                "essay/chapters/01-einleitung.md",
                "essay/chapters/02-pro.md",
                "essay/chapters/03-contra.md",
                "essay/chapters/04-vergleich.md",
                "essay/chapters/05-fazit.md",
                "essay/final-essay.md");

        // Same number of doc_create calls, same paths, same order.
        List<ToolCall> calls = harness.toolCalls();
        assertThat(calls).hasSize(8);
        assertThat(calls).extracting(ToolCall::name)
                .containsOnly("doc_create");
        assertThat(calls).extracting(c -> (String) c.params().get("path"))
                .containsExactlyElementsOf(filesWritten);
    }

    @Test
    void assemble_research_question_uses_topic_verbatim() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(samplePayload("Sind Hausaufgaben heute noch sinnvoll?"))
                .mockTool("doc_create", p -> Map.of())
                .build();

        harness.run();

        ToolCall rq = harness.toolCalls().get(0);
        assertThat((String) rq.params().get("path"))
                .isEqualTo("essay/research-question.md");
        assertThat((String) rq.params().get("content"))
                .contains("Sind Hausaufgaben heute noch sinnvoll?");
    }

    @Test
    void assemble_sources_md_lists_links_in_input_order() throws Exception {
        Map<String, Object> payload = samplePayload("Topic");
        payload.put("sources", List.of(
                Map.of("title", "FOCUS", "url", "https://focus.de/x"),
                Map.of("title", "ZDF", "url", "https://zdf.de/y"),
                Map.of("title", "no-url-source")));

        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(payload)
                .mockTool("doc_create", p -> Map.of())
                .build();

        harness.run();

        ToolCall sources = harness.toolCalls().get(1);
        assertThat((String) sources.params().get("path"))
                .isEqualTo("essay/sources.md");
        String body = (String) sources.params().get("content");
        assertThat(body)
                .contains("[FOCUS](https://focus.de/x)")
                .contains("[ZDF](https://zdf.de/y)")
                .contains("- no-url-source");
        // Order: FOCUS before ZDF before the bare-title entry.
        assertThat(body.indexOf("FOCUS"))
                .isLessThan(body.indexOf("ZDF"))
                .isLessThan(body.indexOf("no-url-source"));
    }

    @Test
    void assemble_final_essay_concatenates_chapters_with_separators()
            throws Exception {
        Map<String, Object> payload = samplePayload("Topic");
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(payload)
                .mockTool("doc_create", p -> Map.of())
                .build();

        harness.run();

        ToolCall finalCall = harness.toolCalls().get(7);
        assertThat((String) finalCall.params().get("path"))
                .isEqualTo("essay/final-essay.md");
        String finalBody = (String) finalCall.params().get("content");
        // H1 with the topic, then five sections separated by '---'.
        assertThat(finalBody).startsWith("# Topic");
        // 5 separators: 1 between the H1 and the first chapter, plus 4
        // between the five chapters.
        long separators = finalBody.lines()
                .filter(l -> l.equals("---")).count();
        assertThat(separators).isEqualTo(5);
        // All chapter bodies must appear verbatim.
        @SuppressWarnings("unchecked")
        Map<String, String> chapters =
                (Map<String, String>) payload.get("chapters");
        for (String chapter : chapters.values()) {
            assertThat(finalBody).contains(chapter.trim());
        }
    }

    @Test
    void assemble_logs_once_with_summary_fields() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(samplePayload("Topic"))
                .mockTool("doc_create", p -> Map.of())
                .build();

        harness.run();

        assertThat(harness.logRecords()).hasSize(1);
        String msg = harness.logRecords().get(0).getFormattedMessage();
        assertThat(msg).contains("school-essay-script assemble done")
                // VanceScriptApi formats the fields-map via Map.toString,
                // which yields `key: value` (colon, space) — not `key=value`.
                .contains("chapters: 5")
                .contains("files: 8");
    }

    @Test
    void assemble_missing_topic_fails_fast() throws Exception {
        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(Map.of("sources", List.of(), "chapters", fullChapters()))
                .mockTool("doc_create", p -> Map.of())
                .build();

        assertThatThrownBy(harness::run)
                .hasMessageContaining("topic is required");
        assertThat(harness.toolCalls())
                .as("script must fail before any doc_create call")
                .isEmpty();
    }

    @Test
    void assemble_short_chapter_body_fails_fast() throws Exception {
        Map<String, Object> chapters = fullChapters();
        // Truncate one chapter below the 50-char floor so the
        // validator trips. None of the doc_create calls should
        // fire — fail-fast contract.
        chapters.put("pro", "too short");
        Map<String, Object> payload = samplePayload("Topic");
        payload.put("chapters", chapters);

        ScriptHarness harness = ScriptHarness.builder()
                .scriptFile(ASSEMBLE_JS)
                .args(payload)
                .mockTool("doc_create", p -> Map.of())
                .build();

        assertThatThrownBy(harness::run)
                .hasMessageContaining("chapters.pro");
        assertThat(harness.toolCalls()).isEmpty();
    }

    // ──────────────────── helpers ────────────────────

    private static Map<String, Object> samplePayload(String topic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topic", topic);
        payload.put("sources", List.of(
                Map.of("title", "Quelle 1", "url", "https://example.com/1")));
        payload.put("chapters", fullChapters());
        return payload;
    }

    private static Map<String, Object> fullChapters() {
        // Each chapter exceeds the 50-char floor in assemble.js.
        Map<String, Object> chapters = new LinkedHashMap<>();
        chapters.put("einleitung",
                "Diese Einleitung führt zum Thema hin und stellt die "
                        + "Forschungsfrage in den Kontext.");
        chapters.put("pro",
                "Die Pro-Argumente sind vielfältig und in mehreren "
                        + "Quellen belegt (vgl. ..., 2024).");
        chapters.put("contra",
                "Die Contra-Argumente liefern eine alternative Sicht "
                        + "auf das Thema (vgl. ..., 2023).");
        chapters.put("vergleich",
                "Im Vergleich beider Positionen wird deutlich, wo "
                        + "Gemeinsamkeiten und Unterschiede liegen.");
        chapters.put("fazit",
                "Das Fazit fasst die wichtigsten Erkenntnisse zusammen "
                        + "und ordnet sie ein.");
        return chapters;
    }
}
