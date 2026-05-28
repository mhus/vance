package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Codec round-trip and edge-case behaviour for {@code kind: diagram}.
 * The source string is opaque — these tests only assert on the wrapper
 * (front-matter, fence detection, dialect/header preservation,
 * preamble/postamble capture). Mermaid syntax correctness is the
 * renderer's problem, not the codec's.
 */
class DiagramCodecTest {

    private static final String MD_MIME = "text/markdown";
    private static final String JSON_MIME = "application/json";
    private static final String YAML_MIME = "application/yaml";

    // ── parse: Markdown happy paths ───────────────────────────────

    @Test
    void parseMarkdown_canonicalFlowchart_returnsSource() {
        String body = """
                ---
                kind: diagram
                ---

                ```mermaid
                flowchart TD
                  A --> B
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);

        assertThat(doc.kind()).isEqualTo("diagram");
        assertThat(doc.dialect()).isEqualTo("mermaid");
        assertThat(doc.source()).isEqualTo("flowchart TD\n  A --> B");
        assertThat(doc.diagram().theme()).isEqualTo(DiagramTheme.DEFAULT);
        assertThat(doc.diagram().look()).isEqualTo(DiagramLook.CLASSIC);
    }

    @Test
    void parseMarkdown_withNestedHeaderBlock_promotesThemeAndLook() {
        String body = """
                ---
                kind: diagram
                diagram:
                  theme: dark
                  look: handDrawn
                  fontFamily: monospace
                ---

                ```mermaid
                sequenceDiagram
                  Alice->>Bob: Hi
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);

        assertThat(doc.diagram().theme()).isEqualTo(DiagramTheme.DARK);
        assertThat(doc.diagram().look()).isEqualTo(DiagramLook.HAND_DRAWN);
        assertThat(doc.diagram().fontFamily()).isEqualTo("monospace");
    }

    @Test
    void parseMarkdown_emptyInfoFence_acceptedAsMermaid() {
        // LLMs occasionally forget the info string. Don't punish the
        // common case — treat a single info-less fence as the dialect.
        String body = """
                ```
                flowchart LR
                  A --> B
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);
        assertThat(doc.source()).isEqualTo("flowchart LR\n  A --> B");
    }

    @Test
    void parseMarkdown_preambleAndPostamble_captured() {
        String body = """
                ---
                kind: diagram
                ---

                Intro paragraph before.

                ```mermaid
                flowchart TD
                  A --> B
                ```

                Trailing notes after.
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);

        assertThat(doc.source()).isEqualTo("flowchart TD\n  A --> B");
        assertThat(doc.extra()).containsEntry(DiagramCodec.EXTRA_PREAMBLE, "Intro paragraph before.");
        assertThat(doc.extra()).containsEntry(DiagramCodec.EXTRA_POSTAMBLE, "Trailing notes after.");
    }

    @Test
    void parseMarkdown_multipleFences_firstWinsRestUnparsed() {
        String body = """
                ```mermaid
                flowchart TD
                  A --> B
                ```

                ```mermaid
                flowchart LR
                  X --> Y
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);

        assertThat(doc.source()).isEqualTo("flowchart TD\n  A --> B");
        String unparsed = (String) doc.extra().get(DiagramCodec.EXTRA_UNPARSED_BODY);
        assertThat(unparsed).contains("flowchart LR");
        assertThat(unparsed).contains("X --> Y");
    }

    @Test
    void parseMarkdown_noFence_yieldsEmptySource() {
        String body = """
                ---
                kind: diagram
                ---

                Just prose, no fence at all.
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);
        assertThat(doc.source()).isEmpty();
        assertThat(doc.extra()).containsEntry(DiagramCodec.EXTRA_PREAMBLE, "Just prose, no fence at all.");
    }

    @Test
    void parseMarkdown_unknownTheme_clampedToDefault() {
        String body = """
                ---
                kind: diagram
                diagram:
                  theme: hot-pink-flamingo
                ---

                ```mermaid
                flowchart TD
                  A --> B
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);
        assertThat(doc.diagram().theme()).isEqualTo(DiagramTheme.DEFAULT);
    }

    @Test
    void parseMarkdown_dialectOverride_preservedAndUsedForFenceMatching() {
        String body = """
                ---
                kind: diagram
                dialect: d2
                ---

                ```d2
                A -> B
                ```
                """;

        DiagramDocument doc = DiagramCodec.parse(body, MD_MIME);
        assertThat(doc.dialect()).isEqualTo("d2");
        assertThat(doc.source()).isEqualTo("A -> B");
    }

    // ── parse: JSON / YAML ────────────────────────────────────────

    @Test
    void parseJson_metaWrapperAndSource() {
        String body = """
                {
                  "$meta": { "kind": "diagram" },
                  "diagram": { "theme": "dark" },
                  "source": "flowchart TD\\n  A --> B\\n"
                }
                """;

        DiagramDocument doc = DiagramCodec.parse(body, JSON_MIME);

        assertThat(doc.kind()).isEqualTo("diagram");
        assertThat(doc.diagram().theme()).isEqualTo(DiagramTheme.DARK);
        assertThat(doc.source()).isEqualTo("flowchart TD\n  A --> B\n");
    }

    @Test
    void parseYaml_metaWrapperAndSource() {
        String body = """
                $meta:
                  kind: diagram
                diagram:
                  look: handDrawn
                source: |
                  sequenceDiagram
                    Alice->>Bob: Hi
                """;

        DiagramDocument doc = DiagramCodec.parse(body, YAML_MIME);

        assertThat(doc.kind()).isEqualTo("diagram");
        assertThat(doc.diagram().look()).isEqualTo(DiagramLook.HAND_DRAWN);
        assertThat(doc.source()).startsWith("sequenceDiagram");
    }

    @Test
    void parseJson_emptyBody_yieldsEmptyDocument() {
        DiagramDocument doc = DiagramCodec.parse("", JSON_MIME);
        assertThat(doc.source()).isEmpty();
        assertThat(doc.kind()).isEqualTo("diagram");
    }

    @Test
    void parseJson_invalidJson_throwsKindCodecException() {
        assertThatThrownBy(() -> DiagramCodec.parse("{ not json", JSON_MIME))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    // ── serialise ─────────────────────────────────────────────────

    @Test
    void serializeMarkdown_defaultHeader_omitsDiagramBlock() {
        DiagramDocument doc = new DiagramDocument(
                "diagram",
                DiagramDocument.DEFAULT_DIALECT,
                DiagramHeader.defaults(),
                "flowchart TD\n  A --> B",
                new LinkedHashMap<>());

        String out = DiagramCodec.serialize(doc, MD_MIME);

        assertThat(out).contains("kind: diagram");
        assertThat(out).doesNotContain("diagram:");
        assertThat(out).doesNotContain("dialect:");
        assertThat(out).contains("```mermaid");
        assertThat(out).contains("flowchart TD\n  A --> B");
    }

    @Test
    void serializeMarkdown_nonDefaultHeader_emitsDiagramBlock() {
        DiagramHeader header = new DiagramHeader(
                DiagramTheme.DARK, DiagramLook.HAND_DRAWN, null, new LinkedHashMap<>());
        DiagramDocument doc = new DiagramDocument(
                "diagram", "mermaid", header, "flowchart TD\n  A --> B", new LinkedHashMap<>());

        String out = DiagramCodec.serialize(doc, MD_MIME);

        assertThat(out).contains("diagram:");
        assertThat(out).contains("theme: dark");
        assertThat(out).contains("look: handDrawn");
    }

    @Test
    void serializeMarkdown_nonDefaultDialect_emitsDialectKey() {
        DiagramDocument doc = new DiagramDocument(
                "diagram", "d2", DiagramHeader.defaults(), "A -> B", new LinkedHashMap<>());

        String out = DiagramCodec.serialize(doc, MD_MIME);

        assertThat(out).contains("dialect: d2");
        assertThat(out).contains("```d2");
    }

    @Test
    void serializeJson_canonicalKeyOrder() {
        DiagramHeader header = new DiagramHeader(
                DiagramTheme.DARK, DiagramLook.CLASSIC, null, new LinkedHashMap<>());
        DiagramDocument doc = new DiagramDocument(
                "diagram", "mermaid", header, "flowchart TD\n  A --> B\n", new LinkedHashMap<>());

        String out = DiagramCodec.serialize(doc, JSON_MIME);

        // $meta first, then diagram, then source.
        int metaIdx = out.indexOf("\"$meta\"");
        int diagramIdx = out.indexOf("\"diagram\"");
        int sourceIdx = out.indexOf("\"source\"");
        assertThat(metaIdx).isLessThan(diagramIdx);
        assertThat(diagramIdx).isLessThan(sourceIdx);
    }

    @Test
    void serializeJson_defaultHeader_omitsDiagramKey() {
        DiagramDocument doc = DiagramDocument.empty();
        String out = DiagramCodec.serialize(doc, JSON_MIME);
        // The header block is absent, but "kind": "diagram" legitimately
        // carries the word — assert on the structural key form.
        assertThat(out).doesNotContain("\"diagram\" :");
        assertThat(out).doesNotContain("\"diagram\":");
        assertThat(out).doesNotContain("\"dialect\"");
    }

    // ── round-trips ───────────────────────────────────────────────

    @Test
    void roundTrip_markdown_preservesPreambleAndPostamble() {
        String body = """
                ---
                kind: diagram
                diagram:
                  theme: dark
                ---

                Setup notes.

                ```mermaid
                flowchart TD
                  A --> B
                ```

                Closing remarks.
                """;

        DiagramDocument first = DiagramCodec.parse(body, MD_MIME);
        String written = DiagramCodec.serialize(first, MD_MIME);
        DiagramDocument second = DiagramCodec.parse(written, MD_MIME);

        assertThat(second.diagram().theme()).isEqualTo(DiagramTheme.DARK);
        assertThat(second.source()).isEqualTo("flowchart TD\n  A --> B");
        assertThat(second.extra()).containsEntry(DiagramCodec.EXTRA_PREAMBLE, "Setup notes.");
        assertThat(second.extra()).containsEntry(DiagramCodec.EXTRA_POSTAMBLE, "Closing remarks.");
    }

    @Test
    void roundTrip_json_preservesAll() {
        String body = """
                {
                  "$meta": { "kind": "diagram" },
                  "diagram": { "theme": "forest", "look": "handDrawn", "fontFamily": "serif" },
                  "source": "stateDiagram-v2\\n  [*] --> S1\\n"
                }
                """;

        DiagramDocument first = DiagramCodec.parse(body, JSON_MIME);
        String written = DiagramCodec.serialize(first, JSON_MIME);
        DiagramDocument second = DiagramCodec.parse(written, JSON_MIME);

        assertThat(second.diagram().theme()).isEqualTo(DiagramTheme.FOREST);
        assertThat(second.diagram().look()).isEqualTo(DiagramLook.HAND_DRAWN);
        assertThat(second.diagram().fontFamily()).isEqualTo("serif");
        assertThat(second.source()).isEqualTo("stateDiagram-v2\n  [*] --> S1\n");
    }

    @Test
    void roundTrip_yaml_preservesAll() {
        String body = """
                $meta:
                  kind: diagram
                diagram:
                  theme: neutral
                source: |
                  erDiagram
                    A ||--o{ B : has
                """;

        DiagramDocument first = DiagramCodec.parse(body, YAML_MIME);
        String written = DiagramCodec.serialize(first, YAML_MIME);
        DiagramDocument second = DiagramCodec.parse(written, YAML_MIME);

        assertThat(second.diagram().theme()).isEqualTo(DiagramTheme.NEUTRAL);
        assertThat(second.source()).isEqualTo("erDiagram\n  A ||--o{ B : has\n");
    }

    // ── extra pass-through ────────────────────────────────────────

    @Test
    void parseJson_unknownTopLevelKey_landsInExtra() {
        String body = """
                {
                  "$meta": { "kind": "diagram" },
                  "source": "flowchart TD\\n  A --> B",
                  "futureField": { "key": "value" }
                }
                """;

        DiagramDocument doc = DiagramCodec.parse(body, JSON_MIME);
        assertThat(doc.extra()).containsKey("futureField");
    }

    @Test
    void parseJson_unknownHeaderField_landsInHeaderExtra() {
        String body = """
                {
                  "$meta": { "kind": "diagram" },
                  "diagram": { "theme": "dark", "futureOption": 42 },
                  "source": "flowchart TD\\n  A --> B"
                }
                """;

        DiagramDocument doc = DiagramCodec.parse(body, JSON_MIME);
        assertThat(doc.diagram().extra()).containsEntry("futureOption", 42);
    }

    // ── empty / defensive ─────────────────────────────────────────

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> DiagramCodec.parse("anything", "text/csv"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void supports_recognisesMimeFamily() {
        assertThat(DiagramCodec.supports("text/markdown")).isTrue();
        assertThat(DiagramCodec.supports("application/json")).isTrue();
        assertThat(DiagramCodec.supports("application/yaml")).isTrue();
        assertThat(DiagramCodec.supports("text/yaml")).isTrue();
        assertThat(DiagramCodec.supports("text/csv")).isFalse();
        assertThat(DiagramCodec.supports(null)).isFalse();
    }

    @Test
    void diagramHeaderDefaults_isDefault_isTrueForFreshDefaults() {
        assertThat(DiagramHeader.defaults().isDefault()).isTrue();
    }

    @Test
    void diagramHeaderDefaults_isDefault_isFalseWhenThemeChanged() {
        DiagramHeader h = new DiagramHeader(
                DiagramTheme.DARK, DiagramLook.CLASSIC, null, new LinkedHashMap<>());
        assertThat(h.isDefault()).isFalse();
    }
}
