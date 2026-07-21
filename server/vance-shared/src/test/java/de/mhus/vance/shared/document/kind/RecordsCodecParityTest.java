package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Java ↔ TS parity test for the {@code records} kind-codec (Java side).
 *
 * <p>Reads the SHARED fixture corpus at
 * {@code <repo>/test-fixtures/kind-codecs/records/*.json} — the very
 * same files consumed by {@code recordsCodec.parity.test.ts}
 * (vance-face). For each fixture it runs
 * {@code RecordsCodec.serialize(RecordsCodec.parse(input, mime), "application/json")},
 * parses that output as JSON and asserts it structurally equals the
 * fixture's {@code expected} node — comparing parsed JSON, so key order
 * and whitespace are irrelevant.
 *
 * <p>{@code expected} was authored from the TS codec; the Java side must
 * match it. This is a <b>drift-detection</b> harness: an unflagged fixture
 * that diverges fails here (never silently made to pass). A newly-found,
 * not-yet-fixed divergence may be recorded — not hidden — by flagging the
 * fixture {@code knownJavaDrift: true} (skipped with the reason in
 * {@code driftNote}); none is flagged today. See the corpus README.
 */
class RecordsCodecParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** From module basedir {@code server/vance-shared} the repo root is {@code ../..}. */
    private static final File FIXTURE_DIR =
            new File("../../test-fixtures/kind-codecs/records");

    @TestFactory
    List<DynamicTest> recordsParityCorpus() throws IOException {
        if (!FIXTURE_DIR.isDirectory()) {
            throw new IllegalStateException(
                    "Shared fixture corpus not found at "
                            + FIXTURE_DIR.getCanonicalPath()
                            + " — expected repo-root test-fixtures/kind-codecs/records");
        }
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
        // Escape hatch for a newly-found, not-yet-fixed divergence: flag the
        // fixture `knownJavaDrift: true` (+ driftNote) to record it in the
        // corpus without breaking the build. No fixture is flagged today —
        // the markdown-table form was ported to Java (RecordsCodec Phase-A).
        boolean knownDrift = fixture.path("knownJavaDrift").asBoolean(false);
        Assumptions.assumeFalse(
                knownDrift,
                "known Java↔TS drift: " + fixture.path("driftNote").asString(""));

        String input = fixture.get("input").asString();
        String mime = fixture.get("mime").asString();
        JsonNode expected = fixture.get("expected");

        RecordsDocument doc = RecordsCodec.parse(input, mime);
        String json = RecordsCodec.serialize(doc, "application/json");
        JsonNode actual = MAPPER.readTree(json);

        assertThat(actual).isEqualTo(expected);
    }
}
