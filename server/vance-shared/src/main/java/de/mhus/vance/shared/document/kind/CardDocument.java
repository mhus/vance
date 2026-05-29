package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: card} document — the unit of work
 * on a Kanban board.
 *
 * <p>The body is free-form Markdown (description, acceptance criteria,
 * notes, links). Structural data lives in the typed fields below and
 * lands either in the Markdown front-matter or as YAML/JSON top-level
 * keys, depending on the chosen mime type.
 *
 * @param kind        always {@code "card"}.
 * @param title       short headline shown on the board.
 * @param priority    free-form (e.g. {@code "low"}, {@code "med"},
 *                    {@code "high"}, {@code "critical"}); the board
 *                    renderer treats {@code high}/{@code critical} as
 *                    standouts and everything else as normal.
 * @param assignee    optional user identifier (login, email, name).
 * @param labels      arbitrary classification tags. {@code blocked}
 *                    is reserved — the manifest's {@code blockedTag}
 *                    setting picks the label that flags a card as
 *                    blocked.
 * @param dueDate     ISO-date string ({@code yyyy-MM-dd}); pass-through.
 * @param estimate    story-point or hour estimate. Pure number — the
 *                    renderer doesn't pretend to know the unit.
 * @param blocked     when {@code true} the card is considered blocked
 *                    even without the {@code blocked} label.
 * @param body        Markdown body. Empty when the card has no
 *                    description / acceptance criteria / notes.
 * @param extra       unknown front-matter / top-level keys, passthrough.
 *
 * <p>Spec: {@code specification/doc-kind-card.md}.
 */
public record CardDocument(
        String kind,
        String title,
        @Nullable String priority,
        @Nullable String assignee,
        List<String> labels,
        @Nullable String dueDate,
        @Nullable Double estimate,
        boolean blocked,
        String body,
        Map<String, Object> extra) {

    public CardDocument {
        if (kind == null || kind.isBlank()) kind = "card";
        if (title == null) title = "";
        if (labels == null) labels = new ArrayList<>();
        if (body == null) body = "";
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static CardDocument empty() {
        return new CardDocument("card", "", null, null,
                new ArrayList<>(), null, null, false, "",
                new LinkedHashMap<>());
    }
}
