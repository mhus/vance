package de.mhus.vance.addon.brain.workpage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for {@link WorkPageParser} + {@link WorkPageSerializer}.
 * One block-kind per test plus a combined fixture, asserting that
 * {@code parse(serialize(blocks))} returns the same block list as
 * {@code blocks} (parser-stable normal form).
 */
class WorkPageRoundTripTest {

    private final WorkPageParser parser = new WorkPageParser();
    private final WorkPageSerializer serializer = new WorkPageSerializer();

    @Test
    void paragraphAndHeading_roundTrip() {
        List<Block> in = List.of(
                new Block.Heading(1, "Übersicht"),
                new Block.Paragraph("Erster Absatz."),
                new Block.Heading(2, "Details"));
        assertRoundTrip(in);
    }

    @Test
    void bulletAndNumberedList_roundTrip() {
        List<Block> in = List.of(
                new Block.BulletList(List.of("alpha", "beta")),
                new Block.NumberedList(List.of("eins", "zwei", "drei")));
        assertRoundTrip(in);
    }

    @Test
    void todoList_roundTrip() {
        List<Block> in = List.of(
                new Block.TodoList(List.of(new Block.TodoItem(false, "offen"), new Block.TodoItem(true, "erledigt"))));
        assertRoundTrip(in);
    }

    @Test
    void code_roundTrip() {
        List<Block> in = List.of(new Block.Code("java", "int x = 1;\nSystem.out.println(x);"));
        assertRoundTrip(in);
    }

    @Test
    void quote_roundTrip() {
        List<Block> in = List.of(new Block.Quote("zwei\nzeilen"));
        assertRoundTrip(in);
    }

    @Test
    void divider_andImage_roundTrip() {
        List<Block> in = List.of(new Block.Image("Logo", "https://example.com/logo.png"), new Block.Divider());
        assertRoundTrip(in);
    }

    @Test
    void table_roundTrip() {
        List<Block> in = List.of(
                new Block.Table(List.of("Fach", "Note"), List.of(List.of("DBS", "1.7"), List.of("IT-Recht", "2.3"))));
        assertRoundTrip(in);
    }

    @Test
    void callout_roundTrip() {
        List<Block> in = List.of(new Block.Callout("info", "Klausurtermin", "Datenbanken-Klausur am 2026-07-12"));
        assertRoundTrip(in);
    }

    @Test
    void toggle_roundTrip() {
        List<Block> in = List.of(new Block.Toggle("Details", "Mehrzeiliger\nInhalt"));
        assertRoundTrip(in);
    }

    @Test
    void dataviewEmbed_roundTrip() {
        List<Block> in = List.of(new Block.DataviewEmbed("../noten.dataview.yaml"));
        assertRoundTrip(in);
    }

    @Test
    void linkCard_roundTrip() {
        List<Block> in = List.of(new Block.LinkCard("https://example.com", "Example", "Domain für Beispiele"));
        assertRoundTrip(in);
    }

    @Test
    void embed_roundTrip() {
        List<Block> in = List.of(new Block.Embed("vance:/apps/ws/data/durchschnitt.md?kind=text"));
        assertRoundTrip(in);
    }

    @Test
    void form_roundTrip_dataOnly() {
        List<Block> in =
                List.of(new Block.Form("vance:/apps/ws/data/noten.records.json?kind=records", null, false, null));
        assertRoundTrip(in);
    }

    @Test
    void form_roundTrip_preservesSaveScriptSessionAndFormDef() {
        // Regression: the fence saveScript/session/form definition must survive
        // parse → serialize (previously dropped by the lossy Block model).
        java.util.Map<String, Object> field = new java.util.LinkedHashMap<>();
        field.put("name", "note");
        field.put("type", "integer");
        java.util.Map<String, Object> form = new java.util.LinkedHashMap<>();
        form.put("single", false);
        form.put("fields", List.of(field));
        assertRoundTrip(List.of(
                new Block.Form("vance:/apps/ws/data/noten.records.json?kind=records", "vance:calc.js", true, form)));
    }

    @Test
    void input_roundTrip_multilineAndSingle() {
        assertRoundTrip(List.of(new Block.Input("vance:/notes/intro.md?kind=text", true, null, false)));
        assertRoundTrip(List.of(new Block.Input("vance:/notes/name.txt?kind=text", false, "vance:onsave.js", true)));
    }

    @Test
    void button_roundTrip() {
        assertRoundTrip(List.of(new Block.Button("script", "vance:run.js", "Recompute")));
    }

    @Test
    void button_roundTrip_builtInActionTypes() {
        // form-resolve / form-reset carry no script — the serializer must not
        // write an empty `script:` key for them.
        assertRoundTrip(List.of(new Block.Button("form-resolve", "", "Auflösen")));
        assertRoundTrip(List.of(new Block.Button("form-reset", "", null)));
    }

    @Test
    void field_roundTrip_choiceWithSolutionValueAndVerdict() {
        assertRoundTrip(List.of(new Block.Field(
                "q1",
                "choice",
                "Was gehört zur 3NF?",
                List.of("Keine transitiven Abhängigkeiten", "Jede Zeile ist einzigartig"),
                0,
                1,
                "wrong",
                "Schau dir transitive Abhängigkeiten nochmal an.",
                null)));
    }

    @Test
    void field_roundTrip_multiDropdownAndText() {
        assertRoundTrip(List.of(
                new Block.Field(
                        "q2",
                        "multi",
                        "Welche sind Schlüssel?",
                        List.of("a", "b", "c"),
                        List.of(0, 2),
                        List.of(1),
                        null,
                        null,
                        null),
                new Block.Field("q3", "dropdown", "Welcher Typ?", List.of("x", "y"), 1, null, null, null, null),
                new Block.Field(
                        "q4",
                        "text",
                        "Erkläre die 3NF in einem Satz.",
                        List.of(),
                        "Keine transitiven Abhängigkeiten.",
                        "",
                        null,
                        null,
                        Map.of("criteria", "must mention transitive dependencies"))));
    }

    @Test
    void toc_roundTrip() {
        assertRoundTrip(List.of(new Block.Toc()));
    }

    @Test
    void columns_roundTrip_withNestedBlocksAndWidth() {
        // A nested code block (triple backtick) forces the outer columns
        // fence to grow to four backticks — exercises the length-aware
        // parser. Second column carries an explicit width.
        List<Block> in = List.of(new Block.Columns(List.of(
                new Block.Column(
                        null,
                        List.of(
                                new Block.Heading(2, "Links"),
                                new Block.Paragraph("Erste Spalte."),
                                new Block.Code("js", "const x = 1;"))),
                new Block.Column(
                        0.4,
                        List.of(
                                new Block.Paragraph("Zweite Spalte."),
                                new Block.Embed("vance:/apps/ws/data/result.md?kind=text"))))));
        assertRoundTrip(in);
    }

    @Test
    void unknownFence_isPreserved() {
        List<Block> in = List.of(new Block.UnknownFence("vance-future", "key: value\nother: 42"));
        assertRoundTrip(in);
    }

    @Test
    void mixedDocument_roundTrip() {
        List<Block> in = List.of(
                new Block.Heading(1, "Semester WS 2026"),
                new Block.Paragraph("Übersicht aller Klausuren und Hausarbeiten."),
                new Block.TodoList(List.of(
                        new Block.TodoItem(false, "Klausur Datenbanken"),
                        new Block.TodoItem(true, "Hausarbeit IT-Recht"))),
                new Block.Callout("info", "Info", "Termine sind bestätigt."),
                new Block.DataviewEmbed("noten.dataview.yaml"),
                new Block.Divider(),
                new Block.Paragraph("Ende."));
        assertRoundTrip(in);
    }

    private void assertRoundTrip(List<Block> blocks) {
        String md = serializer.serialize(blocks);
        List<Block> parsed = parser.parse(md);
        assertThat(parsed)
                .as("Round-trip should reproduce blocks. Markdown:\n%s", md)
                .containsExactlyElementsOf(blocks);
    }
}
