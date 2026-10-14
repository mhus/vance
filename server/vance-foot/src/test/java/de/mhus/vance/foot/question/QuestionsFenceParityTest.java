package de.mhus.vance.foot.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.question.QuestionDto;
import de.mhus.vance.api.question.QuestionOptionDto;
import de.mhus.vance.api.question.QuestionSetDto;
import de.mhus.vance.api.question.QuestionWeight;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The CLI parser against the shared corpus {@code test-fixtures/kind-codecs/questions/}
 * — the same fixtures the Java codec (vance-shared) and the TS codec read.
 * Success fixtures compare the canonical body (without {@code $meta}),
 * {@code expectedError} fixtures must be rejected with that text.
 */
class QuestionsFenceParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final File FIXTURE_DIR = new File("../../test-fixtures/kind-codecs/questions");

    @TestFactory
    List<DynamicTest> questionsParityCorpus() throws IOException {
        File[] files = FIXTURE_DIR.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("No fixtures in " + FIXTURE_DIR.getCanonicalPath());
        }
        Arrays.sort(files);
        List<DynamicTest> tests = new ArrayList<>();
        for (File file : files) {
            JsonNode fixture = MAPPER.readTree(Files.readString(file.toPath()));
            tests.add(DynamicTest.dynamicTest(
                    file.getName() + " — " + fixture.get("description").asString(), () -> runFixture(fixture)));
        }
        return tests;
    }

    private void runFixture(JsonNode fixture) {
        String input = fixture.get("input").asString();
        if (fixture.has("expectedError")) {
            assertThatThrownBy(() -> QuestionsFence.parse(input))
                    .isInstanceOf(QuestionsFenceException.class)
                    .hasMessageContaining(fixture.get("expectedError").asString());
            return;
        }
        ObjectNode expected = (ObjectNode) fixture.get("expected").deepCopy();
        expected.remove("$meta");
        JsonNode actual = MAPPER.valueToTree(canonical(QuestionsFence.parse(input)));
        assertThat(actual).isEqualTo(expected);
    }

    /** The canonical body form the codecs serialise. */
    private static Map<String, Object> canonical(QuestionSetDto set) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!set.getId().isBlank()) {
            body.put("id", set.getId());
        }
        if (set.getIntro() != null) {
            body.put("intro", set.getIntro());
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        for (QuestionDto q : set.getQuestions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", q.getKey());
            m.put("text", q.getText());
            m.put("kind", q.getKind().name().toLowerCase(Locale.ROOT));
            if (!q.getOptions().isEmpty()) {
                List<Object> options = new ArrayList<>();
                for (QuestionOptionDto o : q.getOptions()) {
                    options.add(
                            o.getDescription() == null
                                    ? o.getLabel()
                                    : Map.of("label", o.getLabel(), "description", o.getDescription()));
                }
                m.put("options", options);
            }
            if (q.getProposal() != null) {
                m.put("proposal", q.getProposal());
            }
            if (q.getContext() != null) {
                m.put("context", q.getContext());
            }
            if (q.getWeight() != QuestionWeight.IMPORTANT) {
                m.put("weight", q.getWeight().name().toLowerCase(Locale.ROOT));
            }
            questions.add(m);
        }
        body.put("questions", questions);
        return body;
    }
}
