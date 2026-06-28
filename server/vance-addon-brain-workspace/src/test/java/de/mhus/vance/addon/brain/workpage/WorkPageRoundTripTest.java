package de.mhus.vance.addon.brain.workpage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        List<Block> in = List.of(new Block.TodoList(List.of(
                new Block.TodoItem(false, "offen"),
                new Block.TodoItem(true, "erledigt"))));
        assertRoundTrip(in);
    }

    @Test
    void code_roundTrip() {
        List<Block> in = List.of(new Block.Code("java",
                "int x = 1;\nSystem.out.println(x);"));
        assertRoundTrip(in);
    }

    @Test
    void quote_roundTrip() {
        List<Block> in = List.of(new Block.Quote("zwei\nzeilen"));
        assertRoundTrip(in);
    }

    @Test
    void divider_andImage_roundTrip() {
        List<Block> in = List.of(
                new Block.Image("Logo", "https://example.com/logo.png"),
                new Block.Divider());
        assertRoundTrip(in);
    }

    @Test
    void table_roundTrip() {
        List<Block> in = List.of(new Block.Table(
                List.of("Fach", "Note"),
                List.of(List.of("DBS", "1.7"), List.of("IT-Recht", "2.3"))));
        assertRoundTrip(in);
    }

    @Test
    void callout_roundTrip() {
        List<Block> in = List.of(new Block.Callout(
                "info", "Klausurtermin",
                "Datenbanken-Klausur am 2026-07-12"));
        assertRoundTrip(in);
    }

    @Test
    void toggle_roundTrip() {
        List<Block> in = List.of(new Block.Toggle(
                "Details", "Mehrzeiliger\nInhalt"));
        assertRoundTrip(in);
    }

    @Test
    void dataviewEmbed_roundTrip() {
        List<Block> in = List.of(new Block.DataviewEmbed("../noten.dataview.yaml"));
        assertRoundTrip(in);
    }

    @Test
    void linkCard_roundTrip() {
        List<Block> in = List.of(new Block.LinkCard(
                "https://example.com", "Example", "Domain für Beispiele"));
        assertRoundTrip(in);
    }

    @Test
    void unknownFence_isPreserved() {
        List<Block> in = List.of(new Block.UnknownFence(
                "vance-future",
                "key: value\nother: 42"));
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
