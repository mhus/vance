package de.mhus.vance.shared.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte parity tests against the TypeScript serializer
 * ({@code client/packages/shared/src/damogran.ts}). The expected strings are
 * computed by hand from the TS logic (appendBlock structure + js-yaml core
 * scalar quoting); a divergence here means the two serializers would fight over
 * the block on the next client save.
 */
class ComposeBlockCodecTest {

    private static final String COMMENT = "# generated — compose run state (do not edit)";
    private static final String MANIFEST =
            "name: demo\ntasks:\n  - type: exec\n    cmd: echo hi\n";

    @Test
    void writeComposeOutputs_singleOutput_matchesTsByteForByte() {
        String out = ComposeBlockCodec.writeComposeOutputs(MANIFEST,
                List.of(new ComposeManagedOutput("out/a.txt", "vance-workspace:/ws/out/a.txt", null, null)));

        assertThat(out).isEqualTo(
                "name: demo\ntasks:\n  - type: exec\n    cmd: echo hi\n\n"
                        + COMMENT + "\n"
                        + "$output:\n"
                        + "  - path: out/a.txt\n"
                        + "    uri: vance-workspace:/ws/out/a.txt\n");
    }

    @Test
    void writeComposeOutputs_kindAndColonTitle_quotesTitleLikeJsYaml() {
        String out = ComposeBlockCodec.writeComposeOutputs("name: demo\n",
                List.of(new ComposeManagedOutput(
                        "r.md", "vance-workspace:/ws/r.md", "markdown", "Analyse: Ergebnis")));

        assertThat(out).isEqualTo(
                "name: demo\n\n"
                        + COMMENT + "\n"
                        + "$output:\n"
                        + "  - path: r.md\n"
                        + "    uri: vance-workspace:/ws/r.md\n"
                        + "    kind: markdown\n"
                        + "    title: 'Analyse: Ergebnis'\n");
    }

    @Test
    void writeComposeOutputs_multipleOutputs_blockSequence() {
        String out = ComposeBlockCodec.writeComposeOutputs("name: demo\n",
                List.of(
                        new ComposeManagedOutput("a", "u1", null, null),
                        new ComposeManagedOutput("b", "u2", null, null)));

        assertThat(out).isEqualTo(
                "name: demo\n\n"
                        + COMMENT + "\n"
                        + "$output:\n"
                        + "  - path: a\n    uri: u1\n"
                        + "  - path: b\n    uri: u2\n");
    }

    @Test
    void writeComposeOutputs_emptyList_stripsBlockAndKeepsTrailingNewline() {
        String withBlock = ComposeBlockCodec.writeComposeOutputs(MANIFEST,
                List.of(new ComposeManagedOutput("a", "u", null, null)));

        String cleared = ComposeBlockCodec.writeComposeOutputs(withBlock, List.of());

        assertThat(cleared).isEqualTo("name: demo\ntasks:\n  - type: exec\n    cmd: echo hi\n");
    }

    @Test
    void writeComposeRun_marker_matchesTsByteForByte() {
        String out = ComposeBlockCodec.writeComposeRun("name: demo",
                new ComposeRunMarker("cr-123", "2026-07-22T10:00:00Z"));

        assertThat(out).isEqualTo(
                "name: demo\n\n"
                        + COMMENT + "\n"
                        + "$run:\n"
                        + "  id: cr-123\n"
                        + "  startedAt: 2026-07-22T10:00:00Z\n");
    }

    @Test
    void writeComposeRun_noStartedAt_omitsIt() {
        String out = ComposeBlockCodec.writeComposeRun("name: demo",
                new ComposeRunMarker("cr-9", null));

        assertThat(out).isEqualTo(
                "name: demo\n\n" + COMMENT + "\n$run:\n  id: cr-9\n");
    }

    @Test
    void writeOverWrite_replacesBlock_isStable() {
        ComposeManagedOutput o = new ComposeManagedOutput("out/a.txt", "vance-workspace:/ws/out/a.txt", null, null);
        String once = ComposeBlockCodec.writeComposeOutputs(MANIFEST, List.of(o));
        String twice = ComposeBlockCodec.writeComposeOutputs(once, List.of(o));

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void runThenOutputs_supersedesRunMarker() {
        String withRun = ComposeBlockCodec.writeComposeRun(MANIFEST, new ComposeRunMarker("cr-1", null));
        String withOutputs = ComposeBlockCodec.writeComposeOutputs(withRun,
                List.of(new ComposeManagedOutput("a", "u", null, null)));

        assertThat(withOutputs).doesNotContain("$run:");
        assertThat(withOutputs).contains("$output:");
    }

    @Test
    void clearComposeManaged_removesBlock_keepsManifest() {
        String withBlock = ComposeBlockCodec.writeComposeRun(MANIFEST, new ComposeRunMarker("cr-1", null));

        String cleared = ComposeBlockCodec.clearComposeManaged(withBlock);

        assertThat(cleared).isEqualTo("name: demo\ntasks:\n  - type: exec\n    cmd: echo hi\n");
    }

    @Test
    void readComposeOutputs_roundTripsValues() {
        String doc = ComposeBlockCodec.writeComposeOutputs("name: demo\n",
                List.of(new ComposeManagedOutput("r.md", "vance-workspace:/ws/r.md", "markdown", "Analyse: Ergebnis")));

        List<ComposeManagedOutput> read = ComposeBlockCodec.readComposeOutputs(doc);

        assertThat(read).containsExactly(
                new ComposeManagedOutput("r.md", "vance-workspace:/ws/r.md", "markdown", "Analyse: Ergebnis"));
    }

    @Test
    void readFixedOutputs_readsUserAuthoredList() {
        String doc = "name: demo\noutput:\n  - path: fixed.txt\n    uri: vance-workspace:/ws/fixed.txt\n";

        List<ComposeManagedOutput> fixed = ComposeBlockCodec.readFixedOutputs(doc);

        assertThat(fixed).containsExactly(
                new ComposeManagedOutput("fixed.txt", "vance-workspace:/ws/fixed.txt", null, null));
    }

    @Test
    void readComposeRun_roundTripsMarker() {
        String doc = ComposeBlockCodec.writeComposeRun("name: demo", new ComposeRunMarker("cr-42", "2026-07-22T09:00:00Z"));

        ComposeRunMarker marker = ComposeBlockCodec.readComposeRun(doc);

        assertThat(marker).isEqualTo(new ComposeRunMarker("cr-42", "2026-07-22T09:00:00Z"));
    }

    @Test
    void readComposeRun_absent_returnsNull() {
        assertThat(ComposeBlockCodec.readComposeRun("name: demo\n")).isNull();
    }
}
