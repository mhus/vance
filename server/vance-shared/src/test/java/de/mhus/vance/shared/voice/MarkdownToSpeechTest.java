package de.mhus.vance.shared.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Golden tests for {@link MarkdownToSpeech}. Each test covers one
 * strip rule from spec §10.3.
 */
class MarkdownToSpeechTest {

    @Test
    void strip_plainText_isIdempotent() {
        assertThat(MarkdownToSpeech.strip("Hallo Welt."))
                .isEqualTo("Hallo Welt.");
    }

    @Test
    void strip_link_dropsUrlKeepsText() {
        assertThat(MarkdownToSpeech.strip("Siehe [Quartalsbericht](vance:/q1.pdf) hier."))
                .isEqualTo("Siehe Quartalsbericht hier.");
    }

    @Test
    void strip_imageLink_keepsAltText() {
        assertThat(MarkdownToSpeech.strip("![Architektur](vance:/arch.png)"))
                .isEqualTo("Architektur");
    }

    @Test
    void strip_linkWithEmptyText_synthesisesHostMention() {
        assertThat(MarkdownToSpeech.strip("[](https://example.com/path)"))
                .isEqualTo("Link zu example.com");
    }

    @Test
    void strip_fencedCodeBlock_replacedByLineCountHint() {
        String md = """
                Hier ist ein Snippet:
                ```java
                public class Foo {
                  void bar() {}
                }
                ```
                Ende.""";
        assertThat(MarkdownToSpeech.strip(md))
                .contains("(Code-Block mit 3 Zeilen)")
                .doesNotContain("public class")
                .startsWith("Hier ist ein Snippet:")
                .endsWith("Ende.");
    }

    @Test
    void strip_pipeTable_replacedByDimensionHint() {
        String md = """
                Vorher.
                | A | B | C |
                | - | - | - |
                | 1 | 2 | 3 |
                | 4 | 5 | 6 |
                Nachher.""";
        assertThat(MarkdownToSpeech.strip(md))
                .contains("(Tabelle mit 3 Zeilen, 3 Spalten)")
                .contains("Vorher.")
                .contains("Nachher.")
                .doesNotContain("| 1 |");
    }

    @Test
    void strip_header_addsTrailingPeriod() {
        assertThat(MarkdownToSpeech.strip("## Einleitung"))
                .isEqualTo("Einleitung.");
    }

    @Test
    void strip_bulletList_collapsesWithConnectors() {
        String md = """
                Punkte:
                - Eins
                - Zwei
                - Drei""";
        assertThat(MarkdownToSpeech.strip(md))
                .isEqualTo("Punkte:\nErstens: Eins; Zweitens: Zwei; Drittens: Drei");
    }

    @Test
    void strip_numberedList_collapsesWithGermanNumbers() {
        String md = """
                1. Anfangen
                2. Weitermachen
                3. Aufhören""";
        assertThat(MarkdownToSpeech.strip(md))
                .isEqualTo("Eins: Anfangen; Zwei: Weitermachen; Drei: Aufhören");
    }

    @Test
    void strip_boldAndItalic_keepsTextDropsMarkers() {
        assertThat(MarkdownToSpeech.strip("Das ist **wichtig** und _kursiv_."))
                .isEqualTo("Das ist wichtig und kursiv.");
    }

    @Test
    void strip_inlineCode_dropsBackticks() {
        assertThat(MarkdownToSpeech.strip("Verwende `null` statt `undefined`."))
                .isEqualTo("Verwende null statt undefined.");
    }

    @Test
    void strip_horizontalRule_becomesPause() {
        assertThat(MarkdownToSpeech.strip("Abschnitt eins\n---\nAbschnitt zwei"))
                .contains(". .");
    }

    @Test
    void strip_htmlTags_removed() {
        assertThat(MarkdownToSpeech.strip("Text mit <span>Markup</span> drin."))
                .isEqualTo("Text mit Markup drin.");
    }

    @Test
    void strip_footnoteRef_removed() {
        assertThat(MarkdownToSpeech.strip("Text mit Fußnote[^1] dahinter."))
                .isEqualTo("Text mit Fußnote dahinter.");
    }

    @Test
    void strip_blockquote_removesMarker() {
        assertThat(MarkdownToSpeech.strip("> Zitat hier."))
                .isEqualTo("Zitat hier.");
    }

    @Test
    void strip_emptyAndNull_safe() {
        assertThat(MarkdownToSpeech.strip("")).isEqualTo("");
        assertThat(MarkdownToSpeech.strip(null)).isEqualTo("");
    }

    @Test
    void strip_combined_messageWithRichContent() {
        String md = """
                Hier ist deine Mindmap als Document:
                [Q1-Strategie](vance:/documents/q1/strategie.md?kind=mindmap)

                Zusätzlich ein Code-Schnipsel:
                ```python
                print("hi")
                ```

                Punkte:
                - Erweitern
                - Validieren""";
        String voice = MarkdownToSpeech.strip(md);
        assertThat(voice)
                .contains("Q1-Strategie")
                .doesNotContain("vance:")
                .contains("(Code-Block mit 1 Zeilen)")
                .doesNotContain("print(")
                .contains("Erstens: Erweitern")
                .contains("Zweitens: Validieren");
    }
}
