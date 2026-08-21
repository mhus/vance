package de.mhus.vance.brain.milliways;

import de.mhus.vance.shared.document.DocumentRef;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What is being shared: a document, a link, a quoted snippet, and a title
 * labelling whichever of them is there. All optional, additive — a search hit
 * is link plus snippet, a highlighted passage is document plus snippet, and a
 * plain document share is what it always was.
 *
 * <p>Every {@link ShareHandler} receives the <em>same</em> subject and decides
 * itself what to do with it. Milliways does not know — and does not need to
 * know — whether a way leads inside or outside the house.
 *
 * <p>{@link DocumentRef} is reused rather than reinvented: it is the type
 * {@code DocumentRefResolver} already produces, so an authored {@code vance:}
 * reference could fill a subject later without an intermediate shape.
 */
public record ShareSubject(
        @Nullable String title,
        @Nullable String link,
        @Nullable String snippet,
        @Nullable DocumentRef document) {

    public ShareSubject {
        // `title` is the label of the thing, not the thing. Without this a
        // title plus a reason would be a valid share, and Milliways would be a
        // note sender — arrived at by degeneration rather than by decision.
        if (link == null && snippet == null && document == null) {
            throw new ShareException(
                    "Nothing to show: a share needs a document, a link or a snippet");
        }
    }

    public static ShareSubject ofDocument(DocumentRef document) {
        return new ShareSubject(null, null, null, document);
    }

    public boolean hasDocument() {
        return document != null;
    }

    /** Path of the referenced document, or {@code null}. */
    public @Nullable String documentPath() {
        return document == null ? null : document.path();
    }

    /** Which parts are present — the audit trail's answer to "what was shared". */
    public List<String> parts() {
        List<String> out = new ArrayList<>(3);
        if (document != null) out.add("document");
        if (link != null) out.add("link");
        if (snippet != null) out.add("snippet");
        return List.copyOf(out);
    }
}
