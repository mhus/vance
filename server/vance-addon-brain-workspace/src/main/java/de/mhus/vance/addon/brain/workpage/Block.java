package de.mhus.vance.addon.brain.workpage;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Block-Tree primitive for the {@code kind: workpage} document. Sealed —
 * the parser, serializer and tools exhaustively switch over the
 * permitted subtypes.
 *
 * <p>Content stays in raw Markdown / plain-text form. Inline-formatting
 * (bold, italic, links inside paragraph text) is rendered by the
 * frontend, never by Java — keeping the model simple and round-trip-
 * stable.
 */
public sealed interface Block
        permits Block.Paragraph, Block.Heading, Block.BulletList,
        Block.NumberedList, Block.TodoList, Block.Quote, Block.Code,
        Block.Divider, Block.Image, Block.Table, Block.Callout,
        Block.Toggle, Block.DataviewEmbed, Block.LinkCard,
        Block.Embed, Block.Form, Block.Input, Block.Toc, Block.Columns,
        Block.UnknownFence {

    /** Free-form text block (default for any non-special line group). */
    record Paragraph(String text) implements Block {}

    /** {@code #} / {@code ##} / {@code ###} — level 1..3. */
    record Heading(int level, String text) implements Block {}

    /** Unordered list — each entry is a single-line text. */
    record BulletList(List<String> items) implements Block {}

    /** Ordered list (numbers in source are normalised to {@code 1.}, {@code 2.}, …). */
    record NumberedList(List<String> items) implements Block {}

    /** GFM checkboxes — {@code - [ ]} / {@code - [x]}. */
    record TodoList(List<TodoItem> items) implements Block {}

    record TodoItem(boolean checked, String text) {}

    /** {@code > …} — blockquote, multi-line joined with newlines. */
    record Quote(String text) implements Block {}

    /** Fenced code block. {@code lang} may be empty / null. */
    record Code(@Nullable String lang, String code) implements Block {}

    /** Thematic break — {@code ---}. */
    record Divider() implements Block {}

    /** Markdown image — {@code ![alt](src)}. */
    record Image(String alt, String src) implements Block {}

    /** Pipe-table. {@code headers} may be empty for header-less tables. */
    record Table(List<String> headers, List<List<String>> rows) implements Block {}

    /** {@code ```vance-callout} fence. */
    record Callout(String severity, @Nullable String title, String body)
            implements Block {}

    /**
     * {@code ```vance-toggle} fence. Body is raw Markdown which the
     * editor parses recursively for nested rendering.
     */
    record Toggle(String summary, String body) implements Block {}

    /** {@code ```vance-dataview} fence — embed reference. */
    record DataviewEmbed(String source) implements Block {}

    /** {@code ```vance-link} fence — visual link card. */
    record LinkCard(String href, @Nullable String title,
                    @Nullable String description) implements Block {}

    /**
     * {@code ```vance-embed} fence — a kind-aware card referencing another
     * Vance document by {@code vance:} URI.
     */
    record Embed(String uri) implements Block {}

    /**
     * {@code ```vance-form} fence — reactive-data form bound to a
     * {@code kind: records} document ({@code config} = its {@code vance:} URI).
     */
    record Form(String config) implements Block {}

    /**
     * {@code ```vance-input} fence — single editable text value bound to a
     * text document ({@code config} = its {@code vance:} URI); {@code multiline}
     * toggles textarea vs. single line.
     */
    record Input(String config, boolean multiline) implements Block {}

    /** {@code ```vance-toc} fence — auto table-of-contents (no body). */
    record Toc() implements Block {}

    /**
     * {@code ```vance-columns} fence — a horizontal multi-column layout.
     * Each column carries an optional relative {@code width} and its own
     * nested block list (recursively parsed / serialized).
     */
    record Columns(List<Column> columns) implements Block {}

    /** One column of a {@link Columns} block. */
    record Column(@Nullable Double width, List<Block> blocks) {}

    /**
     * Unknown / forward-compat fence. Rendered as a placeholder; never
     * dropped on round-trip.
     */
    record UnknownFence(String infoString, String body) implements Block {}
}
