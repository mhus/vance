package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Java ↔ TS parity test for the {@code questions} kind-codec (Java side),
 * over the shared corpus {@code test-fixtures/kind-codecs/questions/} that
 * {@code questionsCodec.parity.test.ts} reads too. Success fixtures compare
 * the canonical JSON; {@code expectedError} fixtures must be rejected with a
 * message containing the given text.
 */
class QuestionsCodecParityTest {

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
            String name = file.getName() + " — " + fixture.get("description").asString();
            tests.add(DynamicTest.dynamicTest(name, () -> runFixture(fixture)));
        }
        return tests;
    }

    private void runFixture(JsonNode fixture) {
        String input = fixture.get("input").asString();
        String mime = fixture.get("mime").asString();
        if (fixture.has("expectedError")) {
            assertThatThrownBy(() -> QuestionsCodec.parse(input, mime))
                    .isInstanceOf(KindCodecException.class)
                    .hasMessageContaining(fixture.get("expectedError").asString());
            return;
        }
        JsonNode actual =
                MAPPER.readTree(QuestionsCodec.serialize(QuestionsCodec.parse(input, mime), "application/json"));
        JsonNode expected = fixture.get("expected");
        assertThat(ParityJson.equivalent(actual, expected))
                .as("JSON parity mismatch: expected=" + expected + " actual=" + actual)
                .isTrue();
    }
}
