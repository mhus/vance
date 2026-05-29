package de.mhus.vance.shared.document.kind;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-item status for {@code kind: checklist}. Each value maps to a
 * single-char marker in the Markdown form (`- [<char>] text`) and to
 * a stable enum-name in JSON/YAML form.
 *
 * <p>Spec: {@code specification/doc-kind-checklist.md} §2.2 (status
 * char mapping table).
 */
public enum ChecklistStatus {

    OPEN(' ', "open"),
    DONE('x', "done"),
    IN_PROGRESS('~', "in_progress"),
    REVIEW('/', "review"),
    BLOCKED('!', "blocked"),
    NEEDS_INFO('?', "needs_info"),
    DEFERRED('-', "deferred"),
    DELEGATED('>', "delegated"),
    WAITING('<', "waiting");

    private final char markdownChar;
    private final String wireName;

    ChecklistStatus(char markdownChar, String wireName) {
        this.markdownChar = markdownChar;
        this.wireName = wireName;
    }

    /** The single-character marker used in the Markdown checkbox slot. */
    public char markdownChar() {
        return markdownChar;
    }

    /** Stable name used in JSON/YAML serialisation. */
    public String wireName() {
        return wireName;
    }

    private static final Map<Character, ChecklistStatus> BY_CHAR = Map.ofEntries(
            Map.entry(' ', OPEN),
            Map.entry('x', DONE),
            Map.entry('X', DONE),
            Map.entry('~', IN_PROGRESS),
            Map.entry('/', REVIEW),
            Map.entry('!', BLOCKED),
            Map.entry('?', NEEDS_INFO),
            Map.entry('-', DEFERRED),
            Map.entry('>', DELEGATED),
            Map.entry('<', WAITING));

    private static final Map<String, ChecklistStatus> BY_WIRE = Map.ofEntries(
            Map.entry("open", OPEN),
            Map.entry("done", DONE),
            Map.entry("in_progress", IN_PROGRESS),
            Map.entry("review", REVIEW),
            Map.entry("blocked", BLOCKED),
            Map.entry("needs_info", NEEDS_INFO),
            Map.entry("deferred", DEFERRED),
            Map.entry("delegated", DELEGATED),
            Map.entry("waiting", WAITING));

    /** Resolve a Markdown checkbox char into a status. Unknown chars
     *  return {@code null} — callers preserve the original via
     *  {@code extra._statusChar}. Empty checkbox `[]` (zero-width)
     *  also returns {@code null}. */
    public static @Nullable ChecklistStatus fromMarkdownChar(char c) {
        return BY_CHAR.get(c);
    }

    /** Resolve a wire-name (JSON/YAML {@code status:} value) into a
     *  status. Unknown wire-names return {@code null}. */
    public static @Nullable ChecklistStatus fromWireName(@Nullable String wire) {
        if (wire == null) return null;
        return BY_WIRE.get(wire.toLowerCase(Locale.ROOT));
    }
}
