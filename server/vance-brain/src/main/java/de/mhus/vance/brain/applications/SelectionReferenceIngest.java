package de.mhus.vance.brain.applications;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.SelectionReference;
import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.shared.net.SafeLink;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turns the {@link SelectionReference} an app's UI declared into something
 * safe to persist and to replay into a prompt.
 *
 * <h2>Why a gate at all</h2>
 * The reference is written by the app remote in the reader's browser, and
 * two of its three fields carry text the app itself did not author: a feed
 * headline comes from an archive, a hit title from a foreign index. Both
 * end up on a line of a Markdown-shaped prompt that the model reads on
 * every later turn — a newline in a headline would start a heading of its
 * own inside it. Same collapse-and-cap the tool path already applies to the
 * very same fields.
 *
 * <p>The URL is checked against {@link SafeLink} rather than parsed: the
 * reference is rendered as a link for a human and handed to the model as
 * something it may {@code web_fetch}, so the scheme is what matters. A
 * {@code javascript:} or {@code data:} address has no business in either
 * role.
 *
 * <h2>The invariant</h2>
 * A label with no address is not a reference — it cannot be followed, and
 * persisting it would put a permanent line into the prompt that says only
 * "there was something". Such an input is dropped whole, which restores
 * exactly the behaviour of not having the feature: nothing is written.
 */
public final class SelectionReferenceIngest {

    /**
     * Longest label kept. A headline is a sentence; anything past this is
     * either an abstract that was pasted into the wrong field or an attempt
     * to spend the prompt budget of every future turn.
     */
    static final int MAX_LABEL_CHARS = 200;

    /**
     * Longest address kept. Real URLs with tracking tails get long; a
     * kilobyte of one is not an address any more.
     */
    static final int MAX_URL_CHARS = 1000;

    /**
     * The only scheme a {@code vanceUri} may use. Checked as a prefix
     * because the rest of the grammar (relative vs. absolute, the
     * {@code ?entry=} handle) is the resolver's business, not this gate's —
     * see {@code specification/public/document-refs.md}.
     */
    private static final String VANCE_SCHEME = "vance:";

    private SelectionReferenceIngest() {}

    /**
     * Harden the reference carried by {@code active}, or return {@code null}
     * when the turn carried none, when it is unusable, or when nothing
     * survives the checks.
     */
    public static @Nullable SelectionReference from(@Nullable ActiveAppContext active) {
        return active == null ? null : sanitize(active.getSelectionRef());
    }

    /** @see #from(ActiveAppContext) */
    public static @Nullable SelectionReference sanitize(@Nullable SelectionReference raw) {
        if (raw == null) return null;
        String label = cap(UntrustedContent.collapseWhitespace(raw.getLabel()), MAX_LABEL_CHARS);
        if (label.isEmpty()) return null;

        String vanceUri = trimmed(raw.getVanceUri());
        if (vanceUri != null
                && (!vanceUri.startsWith(VANCE_SCHEME) || vanceUri.length() > MAX_URL_CHARS)) {
            vanceUri = null;
        }
        String url = SafeLink.safe(trimmed(raw.getUrl()));
        if (url != null && url.length() > MAX_URL_CHARS) url = null;

        // Label-only is not a reference — see the class comment.
        if (vanceUri == null && url == null) return null;

        return SelectionReference.builder().label(label).vanceUri(vanceUri).url(url).build();
    }

    /**
     * The {@code meta} map for a USER chat message built from this turn's
     * active-app hint — empty when the turn pointed at nothing, so every
     * persist site can pass the result unconditionally.
     */
    public static Map<String, Object> metaFor(@Nullable ActiveAppContext active) {
        SelectionReference ref = from(active);
        if (ref == null) return new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(
                de.mhus.vance.shared.chat.ChatMessageDocument.META_SELECTION_REFERENCE,
                toMeta(ref));
        return meta;
    }

    /**
     * The persisted shape: a plain map, because it lives inside the loosely
     * typed {@code meta} of a chat message and is read back defensively by
     * {@code ChatMessageDocument.selectionReference()}.
     */
    public static Map<String, Object> toMeta(SelectionReference ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", ref.getLabel());
        if (ref.getVanceUri() != null) m.put("vanceUri", ref.getVanceUri());
        if (ref.getUrl() != null) m.put("url", ref.getUrl());
        return m;
    }

    private static @Nullable String trimmed(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }
}
