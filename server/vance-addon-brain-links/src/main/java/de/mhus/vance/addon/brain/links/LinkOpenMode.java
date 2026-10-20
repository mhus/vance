package de.mhus.vance.addon.brain.links;

import org.jspecify.annotations.Nullable;

/**
 * How a link opens when the reader clicks its card.
 *
 * <p>Stored in the manifest at two levels — the list default under
 * {@code links.open} and a per-entry override on the row itself. Resolution is
 * a coalesce: the entry's value wins, then the list's, then {@link #TAB} (the
 * behaviour the app shipped with). The mode is reader environment, not
 * content: it changes nothing about what is stored and travels with neither
 * the share path nor the generated index.
 *
 * <p>{@code frame} embeds the page in a pane inside the app, with
 * back/forward/reload above it. A pane is honest about its limits: sites that
 * send {@code X-Frame-Options: DENY} or a {@code frame-ancestors} CSP refuse
 * to be embedded and the pane stays empty — undetectable from the embedding
 * page, which is why the frame view always offers an "open in a new tab"
 * escape hatch.
 */
public enum LinkOpenMode {

    /** A new browser tab — the behaviour the app shipped with. */
    TAB("tab"),

    /** The same tab. Replaces the whole workspace; the browser back button returns. */
    SAME("same"),

    /** An embedded pane inside the app, with back/forward/reload above it. */
    FRAME("frame");

    private final String wire;

    LinkOpenMode(String wire) {
        this.wire = wire;
    }

    /** The manifest/JSON name. */
    public String wireName() {
        return wire;
    }

    /**
     * Lenient, like every hand-editable manifest value: an unknown word is
     * {@code null} — the reader falls back to the list default, then
     * {@link #TAB} — never an exception, because a manifest has to open so it
     * can be fixed. The write paths (REST, tools) are strict instead, so an
     * invalid value never enters through an official path;
     * {@code links_validate} reports what the lenient reader dropped.
     */
    public static @Nullable LinkOpenMode parse(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        for (LinkOpenMode mode : values()) {
            if (mode.wire.equals(s)) return mode;
        }
        return null;
    }
}
