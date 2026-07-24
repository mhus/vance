package de.mhus.vance.addon.brain.issues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: issue} document.
 *
 * @param kind     always {@code "issue"}.
 * @param number   stable monotonic id (#42); server-assigned, not editable.
 * @param title    headline; falls back to the filename stem.
 * @param state    {@code "open"} / {@code "closed"} — a field, not a folder.
 * @param labels   free classification tags; mirrored onto native doc tags.
 * @param assignee optional user identifier.
 * @param priority optional, free-form.
 * @param body     Markdown description. Comments are NOT here — they live as
 *                 {@link de.mhus.vance.shared.document.DocumentNote}s.
 * @param extra    unknown front-matter keys, passthrough.
 */
public record IssueDocument(
        String kind,
        int number,
        String title,
        String state,
        List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        String body,
        Map<String, Object> extra) {

    public static final String KIND = "issue";
    public static final String STATE_OPEN = "open";
    public static final String STATE_CLOSED = "closed";

    public IssueDocument {
        if (kind == null || kind.isBlank()) kind = KIND;
        if (title == null) title = "";
        if (state == null || state.isBlank()) state = STATE_OPEN;
        if (labels == null) labels = new ArrayList<>();
        if (body == null) body = "";
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public boolean isOpen() {
        return STATE_OPEN.equalsIgnoreCase(state);
    }

    public static IssueDocument empty() {
        return new IssueDocument(KIND, 0, "", STATE_OPEN,
                new ArrayList<>(), null, null, "", new LinkedHashMap<>());
    }
}
