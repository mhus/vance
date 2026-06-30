package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Canonical splitter/renderer for the fenced {@code --- key: value --- body}
 * front-matter that markdown and plain-text documents share:
 *
 * <pre>
 * ---
 * onSave: update.js
 * session: true
 * ---
 * the actual content...
 * </pre>
 *
 * <p>Where {@link MarkdownHeaderStrategy} only projects the header onto the
 * {@link DocumentDocument} (kind + values), this utility additionally
 * <b>splits off the body</b> and can <b>re-render</b> a preserved header — so
 * callers that edit the body (the {@code vance-input} block) keep the header
 * intact, and callers that edit the config keep the body intact.
 *
 * <p>Only flat {@code key: value} lines are supported (no nesting, no lists),
 * comments start with {@code #}. Raw key casing is preserved for round-trip
 * fidelity; lookups via {@link #get} / {@link #getBoolean} are
 * case-insensitive. A missing first fence or an unterminated header yields a
 * {@link #hasHeader() header-less} instance whose body is the whole input.
 */
public final class FrontMatter {

    private static final String FENCE = "---";

    private final boolean hasHeader;
    private final LinkedHashMap<String, String> entries;
    private String body;

    private FrontMatter(boolean hasHeader, LinkedHashMap<String, String> entries, String body) {
        this.hasHeader = hasHeader;
        this.entries = entries;
        this.body = body;
    }

    /**
     * Split {@code content} into header entries + body. When the content does
     * not open with a {@code ---} fence (or the fence is never closed) the
     * result is header-less and {@link #body()} returns the full content.
     */
    public static FrontMatter parse(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return new FrontMatter(false, new LinkedHashMap<>(), content == null ? "" : content);
        }
        int firstNl = content.indexOf('\n');
        String firstLine = firstNl < 0 ? content : content.substring(0, firstNl);
        if (!FENCE.equals(firstLine.trim())) {
            return new FrontMatter(false, new LinkedHashMap<>(), content);
        }

        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        int pos = firstNl < 0 ? content.length() : firstNl + 1;
        while (pos <= content.length()) {
            int nl = content.indexOf('\n', pos);
            String line = nl < 0 ? content.substring(pos) : content.substring(pos, nl);
            String trimmed = line.trim();
            if (FENCE.equals(trimmed)) {
                String body = nl < 0 ? "" : content.substring(nl + 1);
                return new FrontMatter(true, entries, body);
            }
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String key = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if (!key.isEmpty()) entries.put(key, value);
                }
            }
            if (nl < 0) break;
            pos = nl + 1;
        }
        // Unterminated fence — not a header.
        return new FrontMatter(false, new LinkedHashMap<>(), content);
    }

    public boolean hasHeader() {
        return hasHeader;
    }

    /** The raw header entries (insertion-ordered, original key casing). */
    public Map<String, String> entries() {
        return entries;
    }

    public String body() {
        return body;
    }

    /** Replace the body, keeping the header entries. */
    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }

    /** Case-insensitive lookup of a header value ({@code null} if absent). */
    public @Nullable String get(String key) {
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** A header value parsed as boolean ({@code true} only for {@code "true"}). */
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }

    /**
     * Upsert a header value (case-insensitive on the key, preserving its
     * original casing/position when it already exists). A {@code null} or
     * blank value removes the key.
     */
    public void set(String key, @Nullable String value) {
        String existing = null;
        for (String k : entries.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                existing = k;
                break;
            }
        }
        if (value == null || value.isBlank()) {
            if (existing != null) entries.remove(existing);
            return;
        }
        entries.put(existing != null ? existing : key, value.strip());
    }

    /** Set or clear a boolean header value. */
    public void setBoolean(String key, boolean value) {
        set(key, value ? "true" : null);
    }

    /**
     * Re-assemble {@code <header>\n<body>}. With no entries the body is
     * returned verbatim (no empty fence block is emitted).
     */
    public String render() {
        if (entries.isEmpty()) return body;
        StringBuilder sb = new StringBuilder();
        sb.append(FENCE).append('\n');
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sb.append(FENCE).append('\n');
        sb.append(body);
        return sb.toString();
    }
}
